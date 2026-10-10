package com.grihail.aegraph.ae;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Map;
import java.util.concurrent.Future;

import net.minecraft.world.World;

import org.objectweb.asm.Type;

import com.grihail.aegraph.AEGraphConfig;
import com.grihail.aegraph.AEGraphMod;
import com.grihail.aegraph.remote.RemoteCrafting;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingCallback;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.me.cache.CraftingGridCache;
import appeng.me.cluster.implementations.CraftingCPUCluster;

/** Called from code injected into AE2 by {@link com.grihail.aegraph.asm.AEGraphTransformer}. */
public final class Hooks {

    private Hooks() {}

    /** Set while AE2's own calculator is being started for a graph job: that request must not be intercepted. */
    private static final ThreadLocal<Boolean> STOCK_REQUEST = new ThreadLocal<>();
    private static final Map<String, Method> BEGIN_METHODS = new java.util.concurrent.ConcurrentHashMap<>();

    /**
     * Start of {@code CraftingGridCache.beginCraftingJob}: {@code args} are that method's arguments (world, grid,
     * source, item, ..., callback). Returning null lets AE2 run its own calculator.
     */
    public static Future<ICraftingJob> beginCraftingJob(CraftingGridCache cache, String desc, Object[] args) {
        if (STOCK_REQUEST.get() != null || !AEGraphConfig.enabled) return null;
        final World world = (World) args[0];
        final IGrid grid = (IGrid) args[1];
        final BaseActionSource source = (BaseActionSource) args[2];
        final IAEItemStack what = (IAEItemStack) args[3];
        final ICraftingCallback callback = (ICraftingCallback) args[args.length - 1];
        if (world == null || world.isRemote || grid == null || source == null || what == null) return null;
        try {
            if (!GraphCpus.hasGraphCpu(grid)) return null;
            return new GraphCraftingJob(world, grid, source, what, callback, stockJob(cache, desc, args)).schedule();
        } catch (Throwable t) {
            AEGraphMod.LOG.error("Could not start graph crafting job, using AE2's calculator", t);
            return null;
        }
    }

    /**
     * AE2's own calculation of the same request (same overload, same arguments, no callback): it runs next to the
     * graph planner, for CPUs without co-processor and as fallback. Null if it could not be started.
     */
    @SuppressWarnings("unchecked")
    private static Future<ICraftingJob> stockJob(CraftingGridCache cache, String desc, Object[] args) {
        final Object[] stockArgs = args.clone();
        stockArgs[stockArgs.length - 1] = null;
        STOCK_REQUEST.set(Boolean.TRUE);
        try {
            return (Future<ICraftingJob>) beginMethod(desc).invoke(cache, stockArgs);
        } catch (Throwable t) {
            AEGraphMod.LOG.error("AE2 calculator could not be started for " + args[3], t);
            return null;
        } finally {
            STOCK_REQUEST.remove();
        }
    }

    private static Method beginMethod(String desc) throws NoSuchMethodException {
        Method method = BEGIN_METHODS.get(desc);
        if (method != null) return method;
        for (final Method m : CraftingGridCache.class.getDeclaredMethods()) {
            if ("beginCraftingJob".equals(m.getName()) && desc.equals(Type.getMethodDescriptor(m))) method = m;
        }
        if (method == null) throw new NoSuchMethodException("CraftingGridCache.beginCraftingJob" + desc);
        method.setAccessible(true);
        BEGIN_METHODS.put(desc, method);
        return method;
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

    /**
     * Start of {@code CraftingCPUCluster.submitJob}: the job this CPU really runs. A graph job runs its graph plan
     * only on a CPU with a co-processor; any other CPU (or a job whose graph plan is not used) gets AE2's own job.
     * If AE2's job can't run, the graph job stays and the CPU refuses it ({@link GraphCraftingJob#startCrafting}).
     */
    public static ICraftingJob jobForCpu(CraftingCPUCluster cpu, ICraftingJob job) {
        if (!(job instanceof GraphCraftingJob)) return job;
        final GraphCraftingJob graph = (GraphCraftingJob) job;
        if (graph.usesGraph() && GraphCpus.isGraphCpu(cpu)) return job;
        final ICraftingJob stock = graph.stockJob();
        if (stock == null || stock.isSimulation()) return job;
        graph.recordFallback(cpu);
        return stock;
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
        GraphCpus.applyAcceleration(cpu);
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

    // --- coalesced pattern list rebuilds -----------------------------------------------------------------

    private static final java.util.Set<CraftingGridCache> DEFERRED_REBUILDS = java.util.Collections
            .newSetFromMap(new java.util.WeakHashMap<CraftingGridCache, Boolean>());
    /** Set right before the deferred rebuild runs: that one call must go through. */
    private static boolean flushingRebuild;

    /** Start of {@code CraftingGridCache.updatePatterns}: true skips it, the grid rebuilds once at its next tick. */
    public static boolean deferPatternRebuild(CraftingGridCache cache) {
        if (flushingRebuild) {
            flushingRebuild = false;
            return false;
        }
        if (!AEGraphConfig.coalescePatternRebuilds) return false;
        DEFERRED_REBUILDS.add(cache);
        return true;
    }

    /** Start of {@code CraftingGridCache.onUpdateTick}: true makes it run the deferred rebuild now. */
    public static boolean takeDeferredRebuild(CraftingGridCache cache) {
        if (DEFERRED_REBUILDS.isEmpty() || !DEFERRED_REBUILDS.remove(cache)) return false;
        flushingRebuild = true;
        return true;
    }

    private static final java.util.Set<appeng.me.cache.GridStorageCache> DEFERRED_CELLS = java.util.Collections
            .newSetFromMap(new java.util.WeakHashMap<appeng.me.cache.GridStorageCache, Boolean>());
    private static boolean flushingCells;

    /** Start of {@code GridStorageCache.cellUpdate}: true skips it, the grid rebuilds its storages at its next tick. */
    public static boolean deferCellUpdate(appeng.me.cache.GridStorageCache cache) {
        if (flushingCells) {
            flushingCells = false;
            return false;
        }
        if (!AEGraphConfig.coalescePatternRebuilds) return false;
        DEFERRED_CELLS.add(cache);
        return true;
    }

    /** Start of {@code GridStorageCache.onUpdateTick}: true makes it run the deferred storage rebuild now. */
    public static boolean takeDeferredCellUpdate(appeng.me.cache.GridStorageCache cache) {
        if (DEFERRED_CELLS.isEmpty() || !DEFERRED_CELLS.remove(cache)) return false;
        flushingCells = true;
        return true;
    }

    /** End of {@code CraftingGridCache.updatePatterns}: exclusive remote buses hide other patterns. */
    public static void afterPatternsBuilt(CraftingGridCache cache) {
        try {
            RemoteCrafting.filterExclusive(GridPatterns.index(cache), GridPatterns.all(cache));
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
