package com.grihail.aegraph.ae;

import java.lang.reflect.Field;
import java.util.Iterator;
import java.util.Map;
import java.util.WeakHashMap;

import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.ChatComponentText;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.util.EnumChatFormatting;
import net.minecraft.util.IChatComponent;

import com.grihail.aegraph.AEGraphConfig;
import com.grihail.aegraph.AEGraphMod;
import com.grihail.aegraph.block.TileGraphCoprocessor;
import com.grihail.aegraph.network.AEGraphNetwork;

import appeng.api.config.CraftingAllow;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.security.PlayerSource;
import appeng.me.cache.CraftingGridCache;
import appeng.me.cluster.IAECluster;
import appeng.me.cluster.implementations.CraftingCPUCluster;

/** Which CPUs run graph plans, CPU selection for graph jobs, and the plan snapshots shown by the viewer. */
public final class GraphCpus {

    private static final Map<IGrid, NBTTagCompound> LAST_CALCULATED = new WeakHashMap<>();

    private GraphCpus() {}

    public static boolean isGraphCpu(ICraftingCPU cpu) {
        if (!(cpu instanceof CraftingCPUCluster)) return false;
        if (!AEGraphConfig.requireCoprocessor) return true;
        return !coprocessors((CraftingCPUCluster) cpu).isEmpty();
    }

    public static boolean hasGraphCpu(IGrid grid) {
        if (!AEGraphConfig.requireCoprocessor) return true;
        final ICraftingGrid crafting = grid.getCache(ICraftingGrid.class);
        for (final ICraftingCPU cpu : crafting.getCpus()) {
            if (isGraphCpu(cpu)) return true;
        }
        return false;
    }

    private static java.util.List<TileGraphCoprocessor> coprocessors(CraftingCPUCluster cluster) {
        final java.util.List<TileGraphCoprocessor> list = new java.util.ArrayList<>(1);
        final Iterator<IGridHost> tiles = cluster.getTiles();
        while (tiles.hasNext()) {
            final IGridHost host = tiles.next();
            if (host instanceof TileGraphCoprocessor) list.add((TileGraphCoprocessor) host);
        }
        return list;
    }

    /**
     * Called at the start of {@code CraftingGridCache.submitJob}: when AE2 would pick a CPU on its own, a graph
     * job goes to a free CPU with a co-processor. If none is free, AE2 picks a normal CPU, which then runs the
     * stock AE2 plan computed alongside.
     */
    public static ICraftingCPU chooseCpu(CraftingGridCache cache, ICraftingJob job, ICraftingCPU target,
            BaseActionSource source) {
        if (target != null || !(job instanceof GraphCraftingJob) || !((GraphCraftingJob) job).usesGraph()) {
            return target;
        }
        final long bytes = job.getByteTotal();
        CraftingCPUCluster best = null;
        for (final ICraftingCPU cpu : cache.getCpus()) {
            if (!(cpu instanceof CraftingCPUCluster) || !isGraphCpu(cpu)) continue;
            final CraftingCPUCluster cluster = (CraftingCPUCluster) cpu;
            if (!cluster.isActive()) continue;
            final CraftingAllow allow = cluster.getCraftingAllowMode();
            if (source != null && source.isPlayer() && allow == CraftingAllow.ONLY_NONPLAYER) continue;
            if (source != null && !source.isPlayer() && allow == CraftingAllow.ONLY_PLAYER) continue;
            final boolean free = !cluster.isBusy() && cluster.getAvailableStorage() >= bytes;
            final boolean merge = cluster.isBusy() && cluster.getFinalOutput() != null
                    && job.getOutput().isSameType(cluster.getFinalOutput())
                    && cluster.getAvailableStorage() >= cluster.getUsedStorage() + bytes;
            if (!free && !merge) continue;
            if (best == null || cluster.getCoProcessors() > best.getCoProcessors()) best = cluster;
        }
        if (best == null) tell(source, new ChatComponentTranslation("aegraph.chat.cpuBusy"));
        return best;
    }

    /** Chat message to the player behind a request (no-op for machines). */
    static void tell(BaseActionSource source, IChatComponent message) {
        if (source instanceof PlayerSource && ((PlayerSource) source).player != null) {
            final IChatComponent prefix = new ChatComponentText("[AE Graph] ");
            prefix.getChatStyle().setColor(EnumChatFormatting.DARK_PURPLE);
            ((PlayerSource) source).player.addChatMessage(prefix.appendSibling(message));
        }
    }

    static void recordCalculated(IGrid grid, NBTTagCompound snapshot) {
        synchronized (LAST_CALCULATED) {
            LAST_CALCULATED.put(grid, snapshot);
        }
    }

    static void recordStarted(CraftingCPUCluster cluster, NBTTagCompound snapshot) {
        for (final TileGraphCoprocessor tile : coprocessors(cluster)) {
            tile.setLastPlan(snapshot);
        }
    }

    /** Right-click on a co-processor: send the plan of its CPU (or the last plan computed in the network). */
    public static void showGraph(TileGraphCoprocessor tile, EntityPlayerMP player) {
        final IAECluster cluster = tile.getCluster();
        NBTTagCompound snapshot = tile.getLastPlan();
        boolean preview = false;
        if (tile.getProxy().isReady()) {
            try {
                final IGrid grid = tile.getProxy().getGrid();
                synchronized (LAST_CALCULATED) {
                    final NBTTagCompound calculated = LAST_CALCULATED.get(grid);
                    final boolean cpuIdle = !(cluster instanceof CraftingCPUCluster)
                            || !((CraftingCPUCluster) cluster).isBusy();
                    if (calculated != null && (snapshot == null || cpuIdle && newer(calculated, snapshot))) {
                        snapshot = calculated;
                        preview = true;
                    }
                }
            } catch (Exception ignored) {
                // grid not available
            }
        }
        if (snapshot == null) {
            player.addChatMessage(new ChatComponentTranslation("aegraph.chat.noplan"));
            return;
        }
        final NBTTagCompound copy = (NBTTagCompound) snapshot.copy();
        copy.setBoolean("preview", preview);
        copy.setBoolean("formed", cluster != null);
        if (!preview && cluster instanceof CraftingCPUCluster) {
            addRemainingRuns(copy, (CraftingCPUCluster) cluster);
        }
        AEGraphNetwork.sendGraph(player, copy);
    }

    private static boolean newer(NBTTagCompound a, NBTTagCompound b) {
        return a.getLong("created") > b.getLong("created");
    }

    // --- live progress -----------------------------------------------------------------------------------------

    private static Field tasksField;
    private static Field progressField;
    private static boolean progressBroken;

    private static void addRemainingRuns(NBTTagCompound snapshot, CraftingCPUCluster cluster) {
        if (progressBroken || !cluster.isBusy()) return;
        try {
            if (tasksField == null) {
                tasksField = CraftingCPUCluster.class.getDeclaredField("tasks");
                tasksField.setAccessible(true);
            }
            final Map<?, ?> tasks = (Map<?, ?>) tasksField.get(cluster);
            final NBTTagList recipes = snapshot.getTagList("recipes", 10);
            for (final Map.Entry<?, ?> entry : tasks.entrySet()) {
                final ICraftingPatternDetails pattern = (ICraftingPatternDetails) entry.getKey();
                if (progressField == null) {
                    progressField = entry.getValue().getClass().getDeclaredField("value");
                    progressField.setAccessible(true);
                }
                final long left = progressField.getLong(entry.getValue());
                final ItemStack patternItem = pattern.getPattern();
                for (int k = 0; k < recipes.tagCount(); k++) {
                    final NBTTagCompound recipe = recipes.getCompoundTagAt(k);
                    final ItemStack stored = ItemStack.loadItemStackFromNBT(recipe.getCompoundTag("pattern"));
                    if (stored != null && patternItem != null && ItemStack.areItemStacksEqual(stored, patternItem)) {
                        recipe.setLong("remaining", left);
                    }
                }
            }
            snapshot.setBoolean("running", true);
        } catch (Throwable t) {
            progressBroken = true;
            AEGraphMod.LOG.warn("Could not read CPU progress for the graph viewer", t);
        }
    }
}
