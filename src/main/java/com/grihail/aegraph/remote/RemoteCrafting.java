package com.grihail.aegraph.remote;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.grihail.aegraph.AEGraphMod;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.me.cluster.implementations.CraftingCPUCluster;

/** Glue between AE2's crafting code and {@link PartRemoteBus}. Called from {@link com.grihail.aegraph.ae.Hooks}. */
public final class RemoteCrafting {

    private RemoteCrafting() {}

    // --- exclusive mode ------------------------------------------------------------------------------------

    /**
     * End of {@code CraftingGridCache.updatePatterns} ({@code craftable}: AE2's map by output item) and
     * {@link com.grihail.aegraph.ae.GridPatterns#of}: for an item that an exclusive remote bus offers, only the
     * exclusive remote patterns stay. Every calculator (AE2's and the graph planner) reads such a map, so network A
     * crafts the item only through the other network.
     */
    public static void filterExclusive(Map<IAEItemStack, ImmutableList<ICraftingPatternDetails>> craftable,
            Collection<ICraftingPatternDetails> allPatterns) {
        if (craftable == null || allPatterns == null) return;
        // Taken from all patterns of the network: AE2 may keep only one pattern per item and priority in the map.
        final Map<IAEItemStack, List<ICraftingPatternDetails>> exclusive = new HashMap<>();
        for (final ICraftingPatternDetails details : allPatterns) {
            if (!(details instanceof RemotePattern) || !((RemotePattern) details).isExclusive()) continue;
            for (final IAEItemStack out : details.getOutputs()) {
                if (out == null) continue;
                final IAEItemStack key = out.copy();
                key.reset();
                key.setCraftable(true);
                exclusive.computeIfAbsent(key, k -> new ArrayList<>(2)).add(details);
            }
        }
        for (final Map.Entry<IAEItemStack, List<ICraftingPatternDetails>> e : exclusive.entrySet()) {
            craftable.put(e.getKey(), ImmutableList.copyOf(e.getValue()));
        }
    }

    // --- CPU waits ----------------------------------------------------------------------------------------

    private static boolean ready;
    private static boolean broken;
    private static Field waitingForField;

    private static synchronized void init() throws ReflectiveOperationException {
        if (ready) return;
        waitingForField = CraftingCPUCluster.class.getDeclaredField("waitingFor");
        waitingForField.setAccessible(true);
        ready = true;
    }

    /** True while the CPU waits for an item that a remote bus is still getting from the other network. */
    public static boolean waitsForRemote(CraftingCPUCluster cpu, IGrid grid) {
        if (broken || grid == null || PartRemoteBus.LIVE.isEmpty()) return false;
        try {
            if (!ready) init();
            final IItemList<IAEItemStack> waiting = waitingFor(cpu);
            if (waiting.isEmpty()) return false;
            for (final PartRemoteBus bus : new ArrayList<>(PartRemoteBus.LIVE)) {
                if (bus.getOwnGrid() != grid) continue;
                for (final IAEItemStack s : waiting) {
                    if (s.getStackSize() > 0 && bus.owes(s)) return true;
                }
            }
        } catch (Throwable t) {
            AEGraphMod.LOG.warn("Could not check remote crafting waits", t);
        }
        return false;
    }

    /** How many of the item the CPUs of a network still wait for; -1 if AE2's internals can't be read. */
    public static long waitingAmount(IGrid grid, IAEItemStack item) {
        if (broken) return -1;
        try {
            if (!ready) init();
            long total = 0;
            for (final ICraftingCPU cpu : ((ICraftingGrid) grid.getCache(ICraftingGrid.class)).getCpus()) {
                if (!(cpu instanceof CraftingCPUCluster)) continue;
                final IAEItemStack s = waitingFor((CraftingCPUCluster) cpu).findPrecise(item);
                if (s != null && s.getStackSize() > 0) total += s.getStackSize();
            }
            return total;
        } catch (Throwable t) {
            AEGraphMod.LOG.warn("Could not read what the crafting CPUs wait for", t);
            return -1;
        }
    }

    @SuppressWarnings("unchecked")
    private static IItemList<IAEItemStack> waitingFor(CraftingCPUCluster cpu) throws IllegalAccessException {
        return (IItemList<IAEItemStack>) waitingForField.get(cpu);
    }
}
