package com.grihail.aegraph.ae;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.grihail.aegraph.remote.RemoteCrafting;

import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import appeng.me.cache.CraftingGridCache;

/**
 * The patterns of a network, read from AE2's crafting grid. {@code craftingMethods} holds every pattern;
 * {@code craftableItems} is AE2's index by output item, which may keep only one pattern per item and priority.
 */
public final class GridPatterns {

    private static Field craftingMethods;
    private static Field craftableItems;

    private GridPatterns() {}

    /** Every pattern of the network, by output item (exclusive Remote Crafting Buses applied). Read-only. */
    public static Map<IAEItemStack, ImmutableList<ICraftingPatternDetails>> of(ICraftingGrid grid) {
        if (!(grid instanceof CraftingGridCache)) return Collections.emptyMap();
        final Collection<ICraftingPatternDetails> all = all((CraftingGridCache) grid);
        final Map<IAEItemStack, List<ICraftingPatternDetails>> lists = new LinkedHashMap<>();
        for (final ICraftingPatternDetails details : all) {
            for (final IAEItemStack out : details.getOutputs()) {
                if (out == null) continue;
                final IAEItemStack key = out.copy();
                key.reset();
                key.setCraftable(true);
                final List<ICraftingPatternDetails> list = lists.computeIfAbsent(key, k -> new ArrayList<>(2));
                if (!list.contains(details)) list.add(details);
            }
        }
        final Map<IAEItemStack, ImmutableList<ICraftingPatternDetails>> byOutput = new LinkedHashMap<>();
        for (final Map.Entry<IAEItemStack, List<ICraftingPatternDetails>> e : lists.entrySet()) {
            byOutput.put(e.getKey(), ImmutableList.copyOf(e.getValue()));
        }
        RemoteCrafting.filterExclusive(byOutput, all);
        return Collections.unmodifiableMap(byOutput);
    }

    /** Every pattern of the network. */
    @SuppressWarnings("unchecked")
    public static Collection<ICraftingPatternDetails> all(CraftingGridCache cache) {
        try {
            if (craftingMethods == null) craftingMethods = field("craftingMethods");
            return ((Map<ICraftingPatternDetails, ?>) craftingMethods.get(cache)).keySet();
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("AE2's CraftingGridCache has no pattern list", e);
        }
    }

    /** AE2's own index by output item, the one its calculator reads (modifiable). */
    @SuppressWarnings("unchecked")
    public static Map<IAEItemStack, ImmutableList<ICraftingPatternDetails>> index(CraftingGridCache cache) {
        try {
            if (craftableItems == null) craftableItems = field("craftableItems");
            return (Map<IAEItemStack, ImmutableList<ICraftingPatternDetails>>) craftableItems.get(cache);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("AE2's CraftingGridCache has no pattern map", e);
        }
    }

    private static Field field(String name) throws NoSuchFieldException {
        final Field f = CraftingGridCache.class.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }
}
