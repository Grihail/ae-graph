package com.grihail.aegraph.remote;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import com.google.common.collect.ImmutableList;
import com.grihail.aegraph.AEGraphMod;

import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingMedium;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.me.cache.CraftingGridCache;
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

    // --- running remote steps ------------------------------------------------------------------------------

    private static boolean ready;
    private static boolean broken;
    private static Field tasksField;
    private static Field valueField;
    private static Field waitingForField;
    private static Field isCompleteField;
    private static Field machineSrcField;
    private static Method postChangeMethod;
    private static Method postStatusMethod;
    private static Method markDirtyMethod;

    private static synchronized void init() throws ReflectiveOperationException {
        if (ready) return;
        final Class<CraftingCPUCluster> c = CraftingCPUCluster.class;
        tasksField = field(c, "tasks");
        waitingForField = field(c, "waitingFor");
        isCompleteField = field(c, "isComplete");
        machineSrcField = field(c, "machineSrc");
        postChangeMethod = c.getDeclaredMethod("postChange", IAEItemStack.class, BaseActionSource.class);
        postChangeMethod.setAccessible(true);
        postStatusMethod = c.getDeclaredMethod("postCraftingStatusChange", IAEItemStack.class);
        postStatusMethod.setAccessible(true);
        markDirtyMethod = c.getDeclaredMethod("markDirty");
        markDirtyMethod.setAccessible(true);
        for (final Class<?> inner : c.getDeclaredClasses()) {
            if (inner.getSimpleName().equals("TaskProgress")) valueField = field(inner, "value");
        }
        if (valueField == null) throw new NoSuchFieldException("CraftingCPUCluster.TaskProgress.value");
        ready = true;
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        final Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    /**
     * Start of {@code CraftingCPUCluster.updateCraftingLogic}. AE2's CPU only pushes patterns that have inputs, so
     * remote steps ("nothing -> item") are handed to their bus here: all runs of a step at once, the CPU then
     * waits for the items like for any pushed pattern.
     */
    public static void pushRemoteTasks(CraftingCPUCluster cpu, CraftingGridCache cache) {
        if (broken || cache == null) return;
        try {
            if (!ready) init();
            if (isCompleteField.getBoolean(cpu) || !cpu.isActive()) return;
            final Map<?, ?> tasks = (Map<?, ?>) tasksField.get(cpu);
            if (tasks.isEmpty()) return;
            boolean changed = false;
            for (final Map.Entry<?, ?> e : tasks.entrySet()) {
                if (!(e.getKey() instanceof RemotePattern)) continue;
                final long runs = valueField.getLong(e.getValue());
                if (runs <= 0) continue;
                final RemotePattern pattern = (RemotePattern) e.getKey();
                for (final ICraftingMedium medium : cache.getMediums(pattern)) {
                    if (!(medium instanceof PartRemoteBus) || !((PartRemoteBus) medium).accept(pattern, runs)) {
                        continue;
                    }
                    valueField.setLong(e.getValue(), 0);
                    final IAEItemStack out = pattern.output().copy().setStackSize(pattern.output().getStackSize() * runs);
                    waitingFor(cpu).add(out.copy());
                    postChangeMethod.invoke(cpu, out.copy(), machineSrcField.get(cpu));
                    postStatusMethod.invoke(cpu, out.copy());
                    changed = true;
                    break;
                }
            }
            if (changed) markDirtyMethod.invoke(cpu);
        } catch (Throwable t) {
            broken = true;
            AEGraphMod.LOG.error("Remote crafting disabled: AE2 internals differ", t);
        }
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

    @SuppressWarnings("unchecked")
    private static IItemList<IAEItemStack> waitingFor(CraftingCPUCluster cpu) throws IllegalAccessException {
        return (IItemList<IAEItemStack>) waitingForField.get(cpu);
    }
}
