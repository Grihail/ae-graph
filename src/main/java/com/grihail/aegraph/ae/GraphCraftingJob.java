package com.grihail.aegraph.ae;

import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;

import com.grihail.aegraph.AEGraphConfig;
import com.grihail.aegraph.AEGraphMod;
import com.grihail.aegraph.core.Plan;
import com.grihail.aegraph.core.Planner;
import com.grihail.aegraph.core.Problem;
import com.grihail.aegraph.core.Recipe;

import appeng.api.config.Actionable;
import appeng.api.config.CraftingMode;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingCallback;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.crafting.CraftBranchFailure;
import appeng.crafting.MECraftingInventory;
import appeng.crafting.v2.CraftingJobV2;
import appeng.hooks.TickHandler;
import appeng.me.cluster.implementations.CraftingCPUCluster;

/**
 * Crafting job planned on the whole pattern graph. Driven by AE2's TickHandler on the server thread:
 * <ol>
 * <li>BUILD - read patterns and storage into a {@link Problem}; start the optimizer in a background thread and
 * AE2's own calculator next to it.</li>
 * <li>RUNNING - wait for both.</li>
 * <li>DONE - the graph plan is shown in the confirmation screen. CPUs with a Graph Co-Processor run the graph
 * plan, other CPUs run AE2's plan. If the graph can't make the target (or lacks items AE2 can cover, e.g. via
 * ore-dictionary substitutes) AE2's plan is used everywhere.</li>
 * </ol>
 * Execution reuses the stock crafting CPU: it receives the items to extract and run counts for every pattern
 * and pushes whatever pattern has its inputs ready, which naturally follows the branching/looping plan.
 */
public class GraphCraftingJob implements ICraftingJob, Future<ICraftingJob> {

    public static volatile String lastPlanSummary;

    private static final ExecutorService SOLVER = Executors.newFixedThreadPool(2, runnable -> {
        final Thread thread = new Thread(runnable, "AEGraph-Solver");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    private enum Phase {
        BUILD,
        RUNNING,
        DONE,
        CANCELLED
    }

    private final World world;
    private final IGrid grid;
    private final BaseActionSource source;
    private final IAEItemStack output;
    private final CraftingMode mode;
    private final ICraftingCallback callback;
    private final long startedAt = System.currentTimeMillis();

    private volatile Phase phase = Phase.BUILD;
    private ModelBuilder.Model model;
    private Future<Plan> solving;
    private Plan plan;
    /** AE2's own calculation: for CPUs without co-processor and as fallback. */
    private CraftingJobV2 stock;
    private boolean stockDone;
    /** True when the graph plan is the one to show and run on graph CPUs. */
    private boolean graph;
    private long graphBytes = 1;
    private NBTTagCompound snapshot;
    /** Why AE2's plan is used instead of the graph plan (lang key), or null. */
    private String fallbackKey;
    private String fallbackArg = "";

    public GraphCraftingJob(World world, IGrid grid, BaseActionSource source, IAEItemStack what, CraftingMode mode,
            ICraftingCallback callback) {
        this.world = world;
        this.grid = grid;
        this.source = source;
        this.output = what.copy();
        this.mode = mode;
        this.callback = callback;
    }

    /** Whether graph CPUs get the graph plan (false: AE2's plan is used on every CPU). */
    public boolean usesGraph() {
        return phase == Phase.DONE && graph;
    }

    // --- calculation ------------------------------------------------------------------------------------------

    @Override
    public Future<ICraftingJob> schedule() {
        TickHandler.INSTANCE.registerCraftingSimulation(world, this);
        return this;
    }

    @Override
    public boolean simulateFor(int milli) {
        if (phase == Phase.BUILD) {
            build();
        }
        if (phase != Phase.RUNNING) return false;
        if (stock != null && !stockDone) {
            stockDone = !stock.simulateFor(milli);
        }
        if ((stock == null || stockDone) && (solving == null || solving.isDone())) {
            decide();
            return false;
        }
        return true;
    }

    private void build() {
        try {
            stock = new CraftingJobV2(world, grid, source, output.copy(), mode, null);
        } catch (Throwable t) {
            AEGraphMod.LOG.error("AE2 calculator could not be started for " + output, t);
            stock = null;
        }
        try {
            model = ModelBuilder.build(grid, source, output, world);
            if (model.truncated) {
                AEGraphMod.LOG.warn("Pattern graph for {} truncated at {} patterns (maxPatterns)", output,
                        AEGraphConfig.maxPatterns);
            }
            final Problem problem = model.problem;
            if (!problem.recipes.isEmpty()) {
                final Planner.Settings settings = new Planner.Settings();
                settings.timeBudgetMillis = AEGraphConfig.timeBudgetMillis;
                settings.bigRunThreshold = AEGraphConfig.bigRunThreshold;
                settings.storedIntermediateFactor = AEGraphConfig.storedIntermediateFactor;
                settings.surplusPenalty = AEGraphConfig.surplusPenalty;
                settings.stockFirstTolerance = AEGraphConfig.stockFirstTolerance;
                solving = SOLVER.submit(() -> Planner.plan(problem, settings));
            }
        } catch (Throwable t) {
            AEGraphMod.LOG.error("Could not read the pattern graph for " + output, t);
            model = null;
        }
        phase = Phase.RUNNING;
    }

    private void decide() {
        if (solving != null) {
            try {
                plan = solving.get();
            } catch (Throwable t) {
                AEGraphMod.LOG.error("Graph planning failed for " + output, t);
                plan = null;
            }
        }
        final boolean stockComplete = stock != null && !stock.isSimulation();
        final boolean havePlan = plan != null && model != null;
        graph = havePlan && plan.missing[model.problem.target] == 0;
        fallbackKey = null;
        fallbackArg = "";
        if (!havePlan) {
            fallbackKey = "aegraph.fallback.noplan";
        } else if (!graph) {
            fallbackKey = "aegraph.fallback.target";
        } else if (plan.hasMissing() && AEGraphConfig.fallbackToAE2WhenMissing && stockComplete) {
            // AE2 found everything (ore-dictionary substitutes, emitters...) where the graph did not.
            graph = false;
            fallbackKey = "aegraph.fallback.missing";
            fallbackArg = missingList();
        }
        if (!graph && stock == null) {
            // Neither calculator worked; show the (incomplete) graph plan or nothing.
            graph = havePlan;
            fallbackKey = null;
        }
        if (havePlan) {
            snapshot = PlanSnapshot.build(grid, model, plan, stock, world.getTotalWorldTime());
            if (!graph && fallbackKey != null) {
                snapshot.setString("fallback", fallbackKey);
                snapshot.setString("fallbackArg", fallbackArg);
            }
            GraphCpus.recordCalculated(grid, snapshot);
            if (AEGraphConfig.logPlans) {
                AEGraphMod.LOG.info("Graph plan:\n{}", plan.describe(model.problem));
            }
        }
        if (graph) graphBytes = computeBytes();
        if (!graph && fallbackKey != null) {
            AEGraphMod.LOG.info("{} x{}: graph plan not used ({} {}), AE2's plan runs instead",
                    ModelBuilder.displayName(output), output.getStackSize(), fallbackKey, fallbackArg);
            GraphCpus.tell(source, new ChatComponentTranslation("aegraph.chat.fallback",
                    new ChatComponentTranslation(fallbackKey, fallbackArg)));
        }
        lastPlanSummary = summary();
        if (phase == Phase.CANCELLED) return;
        phase = Phase.DONE;
        if (callback != null) callback.calculationComplete(this);
    }

    /** Up to four missing items of the graph plan, for messages. */
    private String missingList() {
        final StringBuilder sb = new StringBuilder();
        int shown = 0;
        int more = 0;
        for (int i = 0; i < plan.missing.length; i++) {
            if (plan.missing[i] <= 0) continue;
            if (shown == 4) {
                more++;
                continue;
            }
            if (shown++ > 0) sb.append(", ");
            sb.append(plan.missing[i]).append(" x ").append(ModelBuilder.displayName(model.keys.get(i)));
        }
        if (more > 0) sb.append(" (+").append(more).append(')');
        return sb.toString();
    }

    private String summary() {
        final String target = ModelBuilder.displayName(output) + " x" + output.getStackSize();
        final long took = System.currentTimeMillis() - startedAt;
        if (!graph) return target + ": AE2 calculator used (" + took + " ms)";
        int patterns = 0;
        for (final long r : plan.runs) {
            if (r > 0) patterns++;
        }
        return target + ": " + patterns + " patterns, " + plan.totalRuns() + " runs, " + took + " ms"
                + (plan.hasMissing() ? ", MISSING items" : "")
                + (plan.executionVerified ? "" : ", order unverified");
    }

    private long computeBytes() {
        final Problem problem = model.problem;
        final long[] consumed = new long[problem.itemCount];
        for (int p = 0; p < plan.runs.length; p++) {
            final long runs = plan.runs[p];
            if (runs <= 0 || model.patterns.get(p) == null) continue;
            final Recipe recipe = problem.recipes.get(p);
            for (int q = 0; q < recipe.inItems.length; q++) {
                consumed[recipe.inItems[q]] += runs * recipe.inAmounts[q];
            }
        }
        long bytes = output.getStackSize();
        for (int i = 0; i < consumed.length; i++) {
            final long amount = consumed[i];
            if (amount <= 0) continue;
            bytes += model.fluid[i] ? (amount + 999) / 1000 : amount;
        }
        return Math.max(1, bytes);
    }

    // --- ICraftingJob -----------------------------------------------------------------------------------------

    @Override
    public boolean isSimulation() {
        if (!graph) return stock == null || stock.isSimulation();
        return plan.hasMissing() && mode != CraftingMode.IGNORE_MISSING;
    }

    @Override
    public long getByteTotal() {
        if (!graph) return stock == null ? 1 : stock.getByteTotal();
        // The same job may end up on a normal CPU running AE2's plan; reserve enough for either.
        return stock == null ? graphBytes : Math.max(graphBytes, stock.getByteTotal());
    }

    @Override
    public void populatePlan(IItemList<IAEItemStack> list) {
        if (!graph) {
            if (stock != null) stock.populatePlan(list);
            return;
        }
        final Problem problem = model.problem;
        for (int i = 0; i < problem.itemCount; i++) {
            if (i == problem.target) continue;
            final long amount = plan.extract[i] + plan.missing[i];
            if (amount > 0) list.add(model.keys.get(i).copy().setStackSize(amount));
        }
        for (int p = 0; p < plan.runs.length; p++) {
            final long runs = plan.runs[p];
            if (runs <= 0 || model.patterns.get(p) == null) continue;
            final Recipe recipe = problem.recipes.get(p);
            for (int q = 0; q < recipe.outItems.length; q++) {
                list.addRequestable(model.keys.get(recipe.outItems[q]).copy().setStackSize(0)
                        .setCountRequestable(runs * recipe.outAmounts[q]).setCountRequestableCrafts(runs));
            }
        }
    }

    @Override
    public IAEItemStack getOutput() {
        return output;
    }

    @Override
    public boolean supportsCPUCluster(ICraftingCPU cluster) {
        if (!(cluster instanceof CraftingCPUCluster)) return false;
        if (graph && GraphCpus.isGraphCpu(cluster)) return true;
        return stock != null;
    }

    @Override
    public CraftingMode getCraftingMode() {
        return mode;
    }

    @Override
    public MECraftingInventory getStorageAtBeginning() {
        if (!graph && stock != null) return stock.getStorageAtBeginning();
        return model != null && model.storage != null ? model.storage : new MECraftingInventory();
    }

    @Override
    public void startCrafting(MECraftingInventory storage, ICraftingCPU rawCluster, BaseActionSource src) {
        if (phase != Phase.DONE) {
            throw new IllegalStateException("Trying to start an unfinished graph crafting job for " + output);
        }
        if (!graph || !GraphCpus.isGraphCpu(rawCluster)) {
            stock.startCrafting(storage, rawCluster, src);
            if (!graph && snapshot != null && rawCluster instanceof CraftingCPUCluster
                    && GraphCpus.isGraphCpu(rawCluster)) {
                // The co-processor shows the graph plan it could not use, with the reason.
                GraphCpus.recordStarted((CraftingCPUCluster) rawCluster, snapshot);
            }
            return;
        }
        final CraftingCPUCluster cpu = (CraftingCPUCluster) rawCluster;
        final Problem problem = model.problem;
        for (int i = 0; i < problem.itemCount; i++) {
            if (i == problem.target) continue;
            final IAEItemStack key = model.keys.get(i);
            final long take = plan.extract[i];
            if (take > 0) {
                final IAEItemStack extracted = storage.extractItems(key.copy().setStackSize(take), Actionable.MODULATE,
                        src);
                final long got = extracted == null ? 0 : extracted.getStackSize();
                if (got < take) {
                    if (!cpu.isMissingMode()) throw new CraftBranchFailure(key, take - got);
                    cpu.addEmitable(key.copy().setStackSize(take - got));
                }
                if (got > 0) cpu.addStorage(extracted.copy());
            }
            final long missing = plan.missing[i];
            if (missing > 0) {
                if (!cpu.isMissingMode()) throw new CraftBranchFailure(key, missing);
                cpu.addEmitable(key.copy().setStackSize(missing));
            }
        }
        for (int p = 0; p < plan.runs.length; p++) {
            // Substitution steps (null) need no task: substituting patterns pick their items themselves.
            if (plan.runs[p] > 0 && model.patterns.get(p) != null) cpu.addCrafting(model.patterns.get(p), plan.runs[p]);
        }
        if (snapshot != null) GraphCpus.recordStarted(cpu, snapshot);
    }

    // --- Future -----------------------------------------------------------------------------------------------

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (phase == Phase.DONE || phase == Phase.CANCELLED) return false;
        phase = Phase.CANCELLED;
        if (stock != null) stock.cancel(mayInterruptIfRunning);
        return true;
    }

    @Override
    public boolean isCancelled() {
        return phase == Phase.CANCELLED;
    }

    @Override
    public boolean isDone() {
        return phase == Phase.DONE || phase == Phase.CANCELLED;
    }

    @Override
    public ICraftingJob get() throws InterruptedException, ExecutionException {
        while (!isDone()) {
            if (phase == Phase.RUNNING && solving != null) {
                try {
                    solving.get();
                } catch (ExecutionException ignored) {
                    // reported in decide()
                }
            }
            simulateFor(Integer.MAX_VALUE);
        }
        return this;
    }

    @Override
    public ICraftingJob get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        final long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (!isDone()) {
            final long left = deadline - System.nanoTime();
            if (left <= 0) throw new TimeoutException();
            if (phase == Phase.RUNNING && solving != null) {
                try {
                    solving.get(left, TimeUnit.NANOSECONDS);
                } catch (ExecutionException ignored) {
                    // reported in decide()
                }
            }
            simulateFor((int) Math.max(1, Math.min(Integer.MAX_VALUE, TimeUnit.NANOSECONDS.toMillis(left))));
        }
        if (phase == Phase.CANCELLED) throw new InterruptedException();
        return this;
    }
}
