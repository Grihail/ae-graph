package com.grihail.aegraph.ae;

import java.util.List;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import com.grihail.aegraph.core.Plan;
import com.grihail.aegraph.core.Problem;
import com.grihail.aegraph.core.Recipe;

import appeng.api.AEApi;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingMedium;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.crafting.v2.CraftingJobV2;
import appeng.me.cache.CraftingGridCache;

/**
 * Plan graph as NBT, for the viewer GUI (sent to the client and stored in the co-processor). Only items and
 * patterns that take part in the plan are included.
 */
final class PlanSnapshot {

    private PlanSnapshot() {}

    static NBTTagCompound build(IGrid grid, ModelBuilder.Model model, Plan plan, CraftingJobV2 stock, long created) {
        final Problem problem = model.problem;
        final int n = problem.itemCount;
        final long[] produced = new long[n];
        final long[] consumed = new long[n];
        for (int p = 0; p < plan.runs.length; p++) {
            final long runs = plan.runs[p];
            if (runs <= 0) continue;
            final Recipe recipe = problem.recipes.get(p);
            for (int q = 0; q < recipe.outItems.length; q++) produced[recipe.outItems[q]] += runs * recipe.outAmounts[q];
            for (int q = 0; q < recipe.inItems.length; q++) consumed[recipe.inItems[q]] += runs * recipe.inAmounts[q];
        }

        // Compact item ids: only items that appear in the plan.
        final int[] index = new int[n];
        final NBTTagList items = new NBTTagList();
        int count = 0;
        long takeItems = 0;
        long takeFluid = 0;
        for (int i = 0; i < n; i++) {
            index[i] = -1;
            final boolean used = i == problem.target || produced[i] > 0 || consumed[i] > 0 || plan.extract[i] > 0
                    || plan.missing[i] > 0;
            if (!used) continue;
            index[i] = count++;
            final NBTTagCompound tag = new NBTTagCompound();
            final ItemStack stack = model.keys.get(i).getItemStack();
            stack.stackSize = 1;
            tag.setTag("item", stack.writeToNBT(new NBTTagCompound()));
            tag.setBoolean("fluid", model.fluid[i]);
            if (model.virtual[i]) tag.setBoolean("any", true);
            tag.setLong("take", plan.extract[i]);
            tag.setLong("missing", plan.missing[i]);
            tag.setLong("produced", produced[i]);
            tag.setLong("consumed", consumed[i]);
            items.appendTag(tag);
            if (i != problem.target) {
                if (model.fluid[i]) takeFluid += plan.extract[i];
                else takeItems += plan.extract[i];
            }
        }

        final CraftingGridCache cache = grid.getCache(ICraftingGrid.class) instanceof CraftingGridCache
                ? (CraftingGridCache) grid.getCache(ICraftingGrid.class)
                : null;
        final NBTTagList recipes = new NBTTagList();
        for (int p = 0; p < plan.runs.length; p++) {
            final long runs = plan.runs[p];
            if (runs <= 0) continue;
            final Recipe recipe = problem.recipes.get(p);
            final ICraftingPatternDetails pattern = model.patterns.get(p);
            final NBTTagCompound tag = new NBTTagCompound();
            tag.setLong("runs", runs);
            tag.setTag("in", stacks(recipe.inItems, recipe.inAmounts, index));
            tag.setTag("out", stacks(recipe.outItems, recipe.outAmounts, index));
            if (pattern == null) {
                tag.setBoolean("substitution", true);
                recipes.appendTag(tag);
                continue;
            }
            tag.setBoolean("crafting", pattern.isCraftable());
            final ItemStack patternItem = pattern.getPattern();
            if (patternItem != null) tag.setTag("pattern", patternItem.writeToNBT(new NBTTagCompound()));
            final ItemStack icon = crafterIcon(cache, pattern);
            if (icon != null) tag.setTag("icon", icon.writeToNBT(new NBTTagCompound()));
            recipes.appendTag(tag);
        }

        final NBTTagCompound root = new NBTTagCompound();
        root.setInteger("target", index[problem.target]);
        root.setLong("amount", problem.targetAmount);
        root.setTag("items", items);
        root.setTag("recipes", recipes);
        root.setLong("takeItems", takeItems);
        root.setLong("takeFluid", takeFluid);
        root.setBoolean("missing", plan.hasMissing());
        root.setLong("created", created);
        if (stock != null && stock.isDone() && !stock.isCancelled()) {
            final IItemList<IAEItemStack> list = AEApi.instance().storage().createItemList();
            stock.populatePlan(list);
            long stockItems = 0;
            long stockFluid = 0;
            for (final IAEItemStack s : list) {
                if (ModelBuilder.isFluidDrop(s)) stockFluid += s.getStackSize();
                else stockItems += s.getStackSize();
            }
            root.setLong("stockItems", stockItems);
            root.setLong("stockFluid", stockFluid);
            root.setBoolean("stockMissing", stock.isSimulation());
        }
        return root;
    }

    private static NBTTagList stacks(int[] items, long[] amounts, int[] index) {
        final NBTTagList list = new NBTTagList();
        for (int k = 0; k < items.length; k++) {
            final NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("i", index[items[k]]);
            tag.setLong("a", amounts[k]);
            list.appendTag(tag);
        }
        return list;
    }

    private static ItemStack crafterIcon(CraftingGridCache cache, ICraftingPatternDetails pattern) {
        if (cache == null) return null;
        try {
            final List<ICraftingMedium> mediums = cache.getMediums(pattern);
            for (final ICraftingMedium medium : mediums) {
                final ItemStack icon = medium.getCrafterIcon();
                if (icon != null) return icon;
            }
        } catch (Throwable ignored) {
            // icon is cosmetic
        }
        return null;
    }
}
