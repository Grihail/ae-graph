package com.grihail.aegraph.core;

import java.util.List;

/**
 * A crafting request translated into graph form: items are integer ids, patterns are {@link Recipe}s.
 */
public final class Problem {

    public final int itemCount;
    public final int target;
    public final long targetAmount;
    /** How many of each item may be taken from ME storage. The target is never taken from storage. */
    public final long[] available;
    /**
     * Base price of one unit of an item (1 for an item, 0.001 for a mB of fluid, or a configured override). The
     * planner turns it into the real cost of taking the item from storage, see {@link Planner}.
     */
    public final double[] weight;
    /** Whether some pattern in the graph produces the item. */
    public final boolean[] craftable;
    /** Penalty for one unit that is neither in storage nor craftable. */
    public final double[] missingCost;
    public final List<Recipe> recipes;
    /** Human readable item names, only for logs. May be null. */
    public final String[] itemNames;

    public Problem(int itemCount, int target, long targetAmount, long[] available, double[] weight,
            boolean[] craftable, double[] missingCost, List<Recipe> recipes, String[] itemNames) {
        if (available.length != itemCount || weight.length != itemCount || craftable.length != itemCount
                || missingCost.length != itemCount) {
            throw new IllegalArgumentException("Per-item arrays must have itemCount entries");
        }
        if (target < 0 || target >= itemCount) {
            throw new IllegalArgumentException("Bad target id " + target);
        }
        this.itemCount = itemCount;
        this.target = target;
        this.targetAmount = targetAmount;
        this.available = available;
        this.weight = weight;
        this.craftable = craftable;
        this.missingCost = missingCost;
        this.recipes = recipes;
        this.itemNames = itemNames;
    }

    public String name(int item) {
        return itemNames != null && itemNames[item] != null ? itemNames[item] : "#" + item;
    }
}
