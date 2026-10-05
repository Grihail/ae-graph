package com.grihail.aegraph.ae;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

import com.google.common.collect.ImmutableList;
import com.grihail.aegraph.AEGraphConfig;
import com.grihail.aegraph.core.Problem;
import com.grihail.aegraph.core.Recipe;
import com.grihail.aegraph.remote.RemotePattern;

import appeng.api.AEApi;
import appeng.api.config.FuzzyMode;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.crafting.MECraftingInventory;
import appeng.util.Platform;
import appeng.util.item.AEItemStack;

/**
 * Turns the patterns of an ME network into a {@link Problem}: all patterns that can contribute to the target,
 * directly or through any number of intermediate steps (backward closure over pattern inputs).
 */
final class ModelBuilder {

    static final class Model {

        Problem problem;
        /** Item id -> item type (stack size meaningless). For a virtual item: the item written in the pattern. */
        final List<IAEItemStack> keys = new ArrayList<>();
        /** Recipe index -> pattern; null for a virtual substitution step (no AE2 pattern behind it). */
        final List<ICraftingPatternDetails> patterns = new ArrayList<>();
        final boolean[] fluid;
        /** "Any item a substituting pattern accepts here" - exists only in the plan, never in ME or the CPU. */
        final boolean[] virtual;
        MECraftingInventory storage;
        boolean truncated;

        Model(boolean[] fluid, boolean[] virtual) {
            this.fluid = fluid;
            this.virtual = virtual;
        }
    }

    private static final String FLUID_DROP_CLASS = "com.glodblock.github.common.item.ItemFluidDrop";

    private final Map<IAEItemStack, Integer> ids = new HashMap<>();
    private final List<IAEItemStack> keys = new ArrayList<>();
    private final List<Boolean> virtual = new ArrayList<>();
    /** Virtual item id -> the real items that may stand in for it (the pattern's own item first). */
    private final Map<Integer, List<Integer>> substitutes = new LinkedHashMap<>();
    private final Map<String, Integer> virtualIds = new HashMap<>();

    private World world;
    private MECraftingInventory storage;
    private IItemList<IAEItemStack> craftables;

    private ModelBuilder() {}

    /** Must run on the server thread: reads grid state. */
    static Model build(IGrid grid, BaseActionSource source, IAEItemStack target, World world) {
        final ModelBuilder builder = new ModelBuilder();
        builder.world = world;
        return builder.run(grid, source, target);
    }

    private int id(IAEItemStack stack) {
        Integer id = ids.get(stack);
        if (id == null) {
            final IAEItemStack key = stack.copy();
            key.setStackSize(1);
            id = keys.size();
            ids.put(key, id);
            keys.add(key);
            virtual.add(false);
        }
        return id;
    }

    private Model run(IGrid grid, BaseActionSource source, IAEItemStack target) {
        final ICraftingGrid craftingGrid = grid.getCache(ICraftingGrid.class);
        final IStorageGrid storageGrid = grid.getCache(IStorageGrid.class);
        storage = new MECraftingInventory(storageGrid.getItemInventory(), source, false, false, false);
        final Map<IAEItemStack, ImmutableList<ICraftingPatternDetails>> byOutput = craftingGrid.getCraftingPatterns();
        craftables = AEApi.instance().storage().createItemList();
        for (final IAEItemStack key : byOutput.keySet()) craftables.add(key.copy().setStackSize(1));

        final int targetId = id(target);
        final Set<ICraftingPatternDetails> chosen = new LinkedHashSet<>();
        final List<long[][]> io = new ArrayList<>(); // per chosen pattern: {inIds, inAmts, outIds, outAmts}
        final ArrayDeque<Integer> queue = new ArrayDeque<>();
        final Set<Integer> expanded = new java.util.HashSet<>();
        queue.add(targetId);
        boolean truncated = false;

        while (!queue.isEmpty()) {
            final int item = queue.poll();
            if (!expanded.add(item)) continue;
            final List<Integer> alternatives = substitutes.get(item);
            if (alternatives != null) {
                for (final int real : alternatives) {
                    if (!expanded.contains(real)) queue.add(real);
                }
                continue;
            }
            final List<ICraftingPatternDetails> producers = byOutput.get(keys.get(item));
            if (producers == null) continue;
            for (final ICraftingPatternDetails pattern : producers) {
                if (chosen.contains(pattern)) continue;
                if (chosen.size() >= AEGraphConfig.maxPatterns) {
                    truncated = true;
                    break;
                }
                final long[][] recipe = describe(pattern, targetId);
                if (recipe == null) continue;
                chosen.add(pattern);
                io.add(recipe);
                for (final long in : recipe[0]) {
                    if (!expanded.contains((int) in)) queue.add((int) in);
                }
            }
        }

        final int n = keys.size();
        final long[] available = new long[n];
        final double[] weight = new double[n];
        final boolean[] craftable = new boolean[n];
        final double[] missingCost = new double[n];
        final boolean[] fluid = new boolean[n];
        final boolean[] isVirtual = new boolean[n];
        String[] names = AEGraphConfig.logPlans ? new String[n] : null;
        for (int i = 0; i < n; i++) {
            final IAEItemStack key = keys.get(i);
            isVirtual[i] = virtual.get(i);
            fluid[i] = isFluidDrop(key);
            if (i != targetId && !isVirtual[i]) {
                final IAEItemStack stored = storage.getItemList().findPrecise(key);
                available[i] = stored == null ? 0 : Math.max(0, stored.getStackSize());
            }
            weight[i] = weight(key, fluid[i]);
            craftable[i] = isVirtual[i] || byOutput.containsKey(key);
            missingCost[i] = Math.max(weight[i], 1e-3) * AEGraphConfig.missingPenalty;
            // A virtual item is reported missing only when none of its real items can be had.
            if (isVirtual[i]) missingCost[i] *= 10;
            if (names != null) names[i] = (isVirtual[i] ? "any " : "") + displayName(key);
        }
        missingCost[targetId] = Math.max(1e8, AEGraphConfig.missingPenalty * 1e4);

        final Model model = new Model(fluid, isVirtual);
        final List<Recipe> recipes = new ArrayList<>();
        int index = 0;
        for (final ICraftingPatternDetails pattern : chosen) {
            final long[][] r = io.get(index++);
            recipes.add(new Recipe(toInt(r[0]), r[1], toInt(r[2]), r[3], runCost(pattern, r, weight),
                    cpuOrder(pattern), pattern));
            model.patterns.add(pattern);
        }
        // Substitution steps: real item -> "any of them", free and instant (the CPU picks the item itself).
        long order = Long.MIN_VALUE;
        for (final Map.Entry<Integer, List<Integer>> e : substitutes.entrySet()) {
            for (final int real : e.getValue()) {
                recipes.add(new Recipe(new int[] { real }, new long[] { 1 }, new int[] { e.getKey() },
                        new long[] { 1 }, 0, order++, null));
                model.patterns.add(null);
            }
        }
        model.keys.addAll(keys);
        model.storage = storage;
        model.truncated = truncated;
        model.problem = new Problem(n, targetId, target.getStackSize(), available, weight, craftable, missingCost,
                recipes, names);
        return model;
    }

    /**
     * The items AE2's CPU would accept instead of {@code input} for this pattern (same check as
     * {@code CraftingCPUCluster.getExtractItems}: ore dictionary / damage fuzzy match, and for crafting patterns
     * the recipe must still work with it). Only items that are stored or craftable count.
     */
    private List<IAEItemStack> alternatives(ICraftingPatternDetails pattern, IAEItemStack input, int targetId) {
        final List<IAEItemStack> found = new ArrayList<>();
        final Set<IAEItemStack> seen = new java.util.HashSet<>();
        seen.add(input.copy().setStackSize(1));
        int slot = -1;
        if (pattern.isCraftable()) {
            final IAEItemStack[] slots = pattern.getInputs();
            for (int k = 0; slots != null && k < slots.length && slot < 0; k++) {
                if (slots[k] != null && slots[k].isSameType(input)) slot = k;
            }
            if (slot < 0) return found;
        }
        final List<IAEItemStack> candidates = new ArrayList<>();
        for (final IAEItemStack s : storage.getItemList().findFuzzy(input, FuzzyMode.IGNORE_ALL)) {
            if (s.getStackSize() > 0) candidates.add(s);
        }
        candidates.addAll(craftables.findFuzzy(input, FuzzyMode.IGNORE_ALL));
        for (final IAEItemStack candidate : candidates) {
            final IAEItemStack key = candidate.copy().setStackSize(1);
            if (!seen.add(key)) continue;
            if (ids.containsKey(key) && ids.get(key) == targetId) continue;
            if (slot >= 0) {
                try {
                    if (!pattern.isValidItemForSlot(slot, key.getItemStack(), world)) continue;
                } catch (Throwable t) {
                    continue;
                }
            }
            found.add(key);
        }
        return found;
    }

    /** Virtual item for "input of this pattern, or anything it accepts instead". */
    private int virtualItem(ICraftingPatternDetails pattern, IAEItemStack input, int inputId,
            List<IAEItemStack> alternatives) {
        final StringBuilder signature = new StringBuilder().append(inputId);
        final List<Integer> real = new ArrayList<>();
        real.add(inputId);
        for (final IAEItemStack alt : alternatives) {
            final int id = id(alt);
            real.add(id);
            signature.append(',').append(id);
        }
        // Patterns accepting exactly the same set share one virtual item.
        final String key = signature.toString();
        final Integer existing = virtualIds.get(key);
        if (existing != null) return existing;
        final int id = keys.size();
        keys.add(input.copy().setStackSize(1));
        virtual.add(true);
        substitutes.put(id, real);
        virtualIds.put(key, id);
        return id;
    }

    /** {inIds, inAmounts, outIds, outAmounts}, or null if the pattern is unusable for this request. */
    private long[][] describe(ICraftingPatternDetails pattern, int targetId) {
        final IAEItemStack[] inputs = pattern.getCondensedInputs();
        final IAEItemStack[] outputs = pattern.getCondensedOutputs();
        if (inputs == null || outputs == null || outputs.length == 0) return null;
        final LinkedHashMap<Integer, Long> in = new LinkedHashMap<>();
        final LinkedHashMap<Integer, Long> out = new LinkedHashMap<>();
        final boolean substitute = AEGraphConfig.substitutes && pattern.canSubstitute();
        for (final IAEItemStack stack : inputs) {
            if (stack == null || stack.getStackSize() <= 0) continue;
            int id = id(stack);
            // The CPU hands every arriving target item to the requester, a pattern consuming it would never run.
            if (id == targetId) return null;
            if (substitute) {
                final List<IAEItemStack> alternatives = alternatives(pattern, stack, targetId);
                if (!alternatives.isEmpty()) id = virtualItem(pattern, stack, id, alternatives);
            }
            in.merge(id, stack.getStackSize(), Long::sum);
        }
        for (final IAEItemStack stack : outputs) {
            if (stack == null || stack.getStackSize() <= 0) continue;
            out.merge(id(stack), stack.getStackSize(), Long::sum);
        }
        if (out.isEmpty()) return null;
        if (pattern.isCraftable()) {
            // Crafting-table patterns give back container items (buckets, reusable tools); the CPU expects them.
            final IAEItemStack[] slots = pattern.getInputs();
            if (slots != null) {
                for (final IAEItemStack slot : slots) {
                    if (slot == null) continue;
                    final ItemStack container = Platform.getContainerItem(slot.getItemStack());
                    if (container == null || container.stackSize <= 0) continue;
                    out.merge(id(AEItemStack.create(container)), (long) container.stackSize, Long::sum);
                }
            }
        }
        return new long[][] { ids(in), amounts(in), ids(out), amounts(out) };
    }

    /**
     * A craft of another network (Remote Crafting Bus) is "nothing -> item" here: its inputs live in the other
     * network and are not planned. Unless the bus is exclusive it costs {@code remoteCraftCost} times the value
     * of what it makes, so this network's own routes come first and the other network covers what they can't.
     */
    private static double runCost(ICraftingPatternDetails pattern, long[][] io, double[] weight) {
        if (!(pattern instanceof RemotePattern) || ((RemotePattern) pattern).isExclusive()) {
            return AEGraphConfig.patternRunCost;
        }
        double value = 0;
        for (int k = 0; k < io[2].length; k++) value += weight[(int) io[2][k]] * io[3][k];
        return AEGraphConfig.patternRunCost + AEGraphConfig.remoteCraftCost * value;
    }

    private static double weight(IAEItemStack key, boolean fluid) {
        if (!AEGraphConfig.costOverrides.isEmpty()) {
            final Object name = Item.itemRegistry.getNameForObject(key.getItem());
            if (name != null) {
                final Double withMeta = AEGraphConfig.costOverrides.get(name + "@" + key.getItemDamage());
                if (withMeta != null) return withMeta;
                final Double plain = AEGraphConfig.costOverrides.get(name.toString());
                if (plain != null) return plain;
            }
        }
        return fluid ? AEGraphConfig.fluidCostPerMb : AEGraphConfig.defaultItemCost;
    }

    /** Same order as the TreeMap in CraftingCPUCluster: priority, then hash code. */
    private static long cpuOrder(ICraftingPatternDetails pattern) {
        return ((long) pattern.getPriority() << 32) + ((long) pattern.hashCode() - Integer.MIN_VALUE);
    }

    /** ae2fc stores fluids in patterns and storage as "fluid drop" items, one item per mB. */
    static boolean isFluidDrop(IAEItemStack key) {
        return key.getItem() != null && FLUID_DROP_CLASS.equals(key.getItem().getClass().getName());
    }

    static String displayName(IAEItemStack key) {
        try {
            return key.getItemStack().getDisplayName();
        } catch (Throwable t) {
            final Object name = key.getItem() == null ? null : Item.itemRegistry.getNameForObject(key.getItem());
            return name + "@" + key.getItemDamage();
        }
    }

    private static long[] ids(Map<Integer, Long> map) {
        final long[] r = new long[map.size()];
        int k = 0;
        for (final int id : map.keySet()) r[k++] = id;
        return r;
    }

    private static long[] amounts(Map<Integer, Long> map) {
        final long[] r = new long[map.size()];
        int k = 0;
        for (final long a : map.values()) r[k++] = a;
        return r;
    }

    private static int[] toInt(long[] a) {
        final int[] r = new int[a.length];
        for (int k = 0; k < a.length; k++) r[k] = (int) a[k];
        return r;
    }
}
