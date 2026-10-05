package com.grihail.aegraph.client;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

/**
 * Plan graph read from a snapshot, with a layered layout: columns by distance from the requested item (which ends
 * up rightmost), rows ordered by the barycenter heuristic to keep edges from crossing.
 */
final class PlanGraphModel {

    static final int COL_W = 74;
    static final int ROW_H = 34;
    static final int NODE = 20;

    static final class Node {

        final boolean recipe;
        final int index;
        int column;
        double order;
        int x, y;
        ItemStack stack;
        // item data
        boolean fluid;
        /** Any item a substituting pattern accepts in place of this one. */
        boolean any;
        long take, missing, produced, consumed;
        // recipe data
        long runs, remaining = -1;
        boolean crafting;
        /** Not a pattern: a real item standing in for an "any" item. */
        boolean substitution;
        final List<long[]> in = new ArrayList<>(); // {itemNode, amount}
        final List<long[]> out = new ArrayList<>();
        final List<Node> neighbours = new ArrayList<>();

        Node(boolean recipe, int index) {
            this.recipe = recipe;
            this.index = index;
        }
    }

    final NBTTagCompound data;
    final List<Node> nodes = new ArrayList<>();
    final List<Node> itemNodes = new ArrayList<>();
    final List<Node> recipeNodes = new ArrayList<>();
    Node targetNode;
    int graphW, graphH;

    PlanGraphModel(NBTTagCompound data) {
        this.data = data;
        load();
        layout();
    }

    private void load() {
        final NBTTagList items = data.getTagList("items", 10);
        for (int i = 0; i < items.tagCount(); i++) {
            final NBTTagCompound tag = items.getCompoundTagAt(i);
            final Node node = new Node(false, i);
            node.stack = ItemStack.loadItemStackFromNBT(tag.getCompoundTag("item"));
            node.fluid = tag.getBoolean("fluid");
            node.any = tag.getBoolean("any");
            node.take = tag.getLong("take");
            node.missing = tag.getLong("missing");
            node.produced = tag.getLong("produced");
            node.consumed = tag.getLong("consumed");
            itemNodes.add(node);
            nodes.add(node);
        }
        final int target = data.getInteger("target");
        targetNode = target >= 0 && target < itemNodes.size() ? itemNodes.get(target) : null;
        final NBTTagList recipes = data.getTagList("recipes", 10);
        for (int r = 0; r < recipes.tagCount(); r++) {
            final NBTTagCompound tag = recipes.getCompoundTagAt(r);
            final Node node = new Node(true, r);
            node.runs = tag.getLong("runs");
            if (tag.hasKey("remaining")) node.remaining = tag.getLong("remaining");
            node.crafting = tag.getBoolean("crafting");
            node.substitution = tag.getBoolean("substitution");
            readStacks(tag.getTagList("in", 10), node.in, itemNodes.size());
            readStacks(tag.getTagList("out", 10), node.out, itemNodes.size());
            if (tag.hasKey("icon")) node.stack = ItemStack.loadItemStackFromNBT(tag.getCompoundTag("icon"));
            if (node.stack == null && !node.out.isEmpty()) node.stack = itemNodes.get((int) node.out.get(0)[0]).stack;
            for (final long[] e : node.in) link(node, itemNodes.get((int) e[0]));
            for (final long[] e : node.out) link(node, itemNodes.get((int) e[0]));
            recipeNodes.add(node);
            nodes.add(node);
        }
    }

    private static void readStacks(NBTTagList list, List<long[]> into, int itemCount) {
        for (int k = 0; k < list.tagCount(); k++) {
            final NBTTagCompound tag = list.getCompoundTagAt(k);
            final int i = tag.getInteger("i");
            if (i >= 0 && i < itemCount) into.add(new long[] { i, tag.getLong("a") });
        }
    }

    private static void link(Node a, Node b) {
        a.neighbours.add(b);
        b.neighbours.add(a);
    }

    /** Columns by distance from the target (backwards along inputs), then barycentric ordering inside columns. */
    private void layout() {
        for (final Node n : nodes) n.column = -1;
        final ArrayDeque<Node> queue = new ArrayDeque<>();
        if (targetNode != null) {
            targetNode.column = 0;
            queue.add(targetNode);
        }
        while (!queue.isEmpty()) {
            final Node n = queue.poll();
            if (!n.recipe) {
                for (final Node r : recipeNodes) {
                    if (r.column < 0 && producesItem(r, n)) {
                        r.column = n.column + 1;
                        queue.add(r);
                    }
                }
            } else {
                for (final long[] e : n.in) {
                    final Node item = itemNodes.get((int) e[0]);
                    if (item.column < 0) {
                        item.column = n.column + 1;
                        queue.add(item);
                    }
                }
            }
        }
        // Pure byproducts sit just right of the pattern that makes them.
        boolean changed = true;
        while (changed) {
            changed = false;
            for (final Node r : recipeNodes) {
                if (r.column < 0) continue;
                for (final long[] e : r.out) {
                    final Node item = itemNodes.get((int) e[0]);
                    if (item.column < 0) {
                        item.column = Math.max(0, r.column - 1);
                        changed = true;
                    }
                }
            }
        }
        int maxColumn = 0;
        for (final Node n : nodes) {
            if (n.column < 0) n.column = 0;
            maxColumn = Math.max(maxColumn, n.column);
        }

        final List<List<Node>> columns = new ArrayList<>();
        for (int c = 0; c <= maxColumn; c++) columns.add(new ArrayList<>());
        for (final Node n : nodes) {
            n.order = n.recipe ? 1000 + n.index : n.index;
            columns.get(n.column).add(n);
        }
        for (final List<Node> column : columns) sortAndRank(column);
        for (int sweep = 0; sweep < 6; sweep++) {
            final boolean leftward = sweep % 2 == 0;
            for (int k = 1; k <= maxColumn; k++) {
                final int c = leftward ? k : maxColumn - k;
                final int towards = leftward ? c - 1 : c + 1;
                for (final Node n : columns.get(c)) {
                    double sum = 0;
                    int count = 0;
                    for (final Node m : n.neighbours) {
                        if (m.column == towards) {
                            sum += m.order;
                            count++;
                        }
                    }
                    if (count > 0) n.order = sum / count;
                }
                sortAndRank(columns.get(c));
            }
        }

        int tallest = 0;
        for (final List<Node> column : columns) tallest = Math.max(tallest, column.size());
        graphH = Math.max(1, tallest) * ROW_H;
        graphW = (maxColumn + 1) * COL_W;
        for (final List<Node> column : columns) {
            final int offset = (graphH - column.size() * ROW_H) / 2;
            for (int k = 0; k < column.size(); k++) {
                final Node n = column.get(k);
                n.x = (maxColumn - n.column) * COL_W + (COL_W - NODE) / 2;
                n.y = offset + k * ROW_H + (ROW_H - NODE) / 2 - 4;
            }
        }
    }

    /** {column, x, y} of an item or recipe node. */
    int[] position(boolean recipe, int index) {
        final Node n = (recipe ? recipeNodes : itemNodes).get(index);
        return new int[] { n.column, n.x, n.y };
    }

    private static void sortAndRank(List<Node> column) {
        Collections.sort(column, Comparator.comparingDouble(n -> n.order));
        for (int k = 0; k < column.size(); k++) column.get(k).order = k;
    }

    private static boolean producesItem(Node recipe, Node item) {
        for (final long[] e : recipe.out) {
            if (e[0] == item.index) return true;
        }
        return false;
    }
}
