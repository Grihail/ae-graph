package com.grihail.aegraph.ae;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.Future;

import net.minecraft.world.World;

import com.google.common.collect.ImmutableList;
import com.grihail.aegraph.AEGraphConfig;
import com.grihail.aegraph.AEGraphMod;
import com.grihail.aegraph.remote.RemoteCrafting;

import appeng.api.config.Actionable;
import appeng.api.config.CraftingMode;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingCallback;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.me.cache.CraftingGridCache;
import appeng.me.cluster.implementations.CraftingCPUCluster;

/** Called from code injected into AE2 by {@link com.grihail.aegraph.asm.AEGraphTransformer}. */
public final class Hooks {

    private Hooks() {}

    /**
     * Replacement for {@code CraftingGridCache.beginCraftingJob}. Returning null lets AE2 run its own calculator.
     */
    public static Future<ICraftingJob> beginCraftingJob(CraftingGridCache cache, World world, IGrid grid,
            BaseActionSource source, IAEItemStack what, CraftingMode mode, ICraftingCallback callback) {
        if (!AEGraphConfig.enabled || world == null || world.isRemote || grid == null || source == null
                || what == null) {
            return null;
        }
        try {
            if (!GraphCpus.hasGraphCpu(grid)) return null;
            return new GraphCraftingJob(world, grid, source, what, mode == null ? CraftingMode.STANDARD : mode,
                    callback).schedule();
        } catch (Throwable t) {
            AEGraphMod.LOG.error("Could not start graph crafting job, using AE2's calculator", t);
            return null;
        }
    }

    /** Start of {@code CraftingGridCache.submitJob}: returns the CPU to use (replaces the {@code target} argument). */
    public static ICraftingCPU chooseCpu(CraftingGridCache cache, ICraftingJob job, ICraftingCPU target,
            BaseActionSource source) {
        try {
            return GraphCpus.chooseCpu(cache, job, target, source);
        } catch (Throwable t) {
            AEGraphMod.LOG.error("Graph CPU selection failed, AE2 picks the CPU", t);
            return target;
        }
    }

    // --- CPU completion fix ---------------------------------------------------------------------------------
    // AE2 only completes a job when the last final-output item arrives and no task is left at that moment.
    // In graph plans the target can arrive before the last byproduct-processing runs are done, after which
    // nothing would ever complete the job (the requester keeps waiting, leftovers stay in the CPU).

    private static boolean reflectionReady = false;
    private static boolean reflectionBroken = false;
    private static Field isCompleteField;
    private static Field finalOutputField;
    private static Method completeJobMethod;

    public static void onCpuUpdate(CraftingCPUCluster cpu, CraftingGridCache cache) {
        RemoteCrafting.pushRemoteTasks(cpu, cache);
        CpuStabilizer.onUpdate(cpu);
        if (reflectionBroken) return;
        try {
            if (!reflectionReady) initReflection();
            if (isCompleteField.getBoolean(cpu)) return;
            final IAEItemStack finalOutput = (IAEItemStack) finalOutputField.get(cpu);
            if (finalOutput == null || finalOutput.getStackSize() > 0) return;
            // completeJob() itself returns early while tasks remain.
            completeJobMethod.invoke(cpu);
        } catch (Throwable t) {
            reflectionBroken = true;
            AEGraphMod.LOG.error("CPU completion hook disabled", t);
        }
    }

    /** End of {@code CraftingGridCache.setPatternsFromCraftingMethods}: exclusive remote buses hide other patterns. */
    public static void afterPatternsBuilt(Map<IAEItemStack, ImmutableList<ICraftingPatternDetails>> craftable) {
        try {
            RemoteCrafting.filterExclusive(craftable);
        } catch (Throwable t) {
            AEGraphMod.LOG.error("Exclusive remote crafting filter failed", t);
        }
    }

    /** Start of {@code CraftingCPUCluster.injectItems}: true makes the CPU refuse the stack (it goes to storage). */
    public static boolean cpuPassThrough(CraftingCPUCluster cpu, IAEStack<?> input, Actionable type,
            BaseActionSource source) {
        return CpuStabilizer.passThrough(cpu, input, type, source);
    }

    private static synchronized void initReflection() throws ReflectiveOperationException {
        if (reflectionReady) return;
        isCompleteField = CraftingCPUCluster.class.getDeclaredField("isComplete");
        isCompleteField.setAccessible(true);
        finalOutputField = CraftingCPUCluster.class.getDeclaredField("finalOutput");
        finalOutputField.setAccessible(true);
        completeJobMethod = CraftingCPUCluster.class.getDeclaredMethod("completeJob");
        completeJobMethod.setAccessible(true);
        reflectionReady = true;
    }
}
