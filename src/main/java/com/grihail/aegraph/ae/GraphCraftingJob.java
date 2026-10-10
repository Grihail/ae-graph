package com.grihail.aegraph.ae;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.ChatComponentTranslation;
import net.minecraft.world.World;

import com.grihail.aegraph.AEGraphConfig;
import com.grihail.aegraph.AEGraphMod;
import com.grihail.aegraph.core.Plan;
import com.grihail.aegraph.core.Planner;
import com.grihail.aegraph.core.Problem;
import com.grihail.aegraph.core.Recipe;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingCallback;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IItemList;
import appeng.crafting.CraftBranchFailure;
import appeng.crafting.CraftingJob;
import appeng.crafting.CraftingTreeNode;
import appeng.crafting.MECraftingInventory;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * Crafting job planned on the whole pattern graph. Driven by {@link #DRIVER} on the server thread:
 * <ol>
 * <li>BUILD - read patterns and storage into a {@link Problem}; start the optimizer in a background thread. AE2's
 * own calculation of the same request was started next to it by {@link Hooks#beginCraftingJob}.</li>
 * <li>RUNNING - wait for both.</li>
 * <li>DONE - the graph plan is shown in the confirmation screen. CPUs with a Graph Co-Processor run the graph
 * plan, other CPUs run AE2's job ({@link Hooks#jobForCpu}). If the graph can't make the target (or lacks items AE2
 * can cover, e.g. via ore-dictionary substitutes) AE2's job is used everywhere.</li>
 * </ol>
 * Execution reuses the stock crafting CPU: it receives the items to extract and run counts for every pattern
 * and pushes whatever pattern has its inputs ready, which naturally follows the branching/looping plan.
 * <p>
 * The class extends AE2's {@link CraftingJob} because that is the job type every AE2 CPU accepts; the inherited
 * calculator is never run. CPUs start a job either through {@code getTree().setJob(...)} or through
 * {@code startCrafting(...)}; both lead to {@link #startCrafting}.
 */
public class GraphCraftingJob extends CraftingJob implements Future<ICraftingJob> {

    public static volatile String lastPlanSummary;

    private static final ExecutorService SOLVER = Executors.newFixedThreadPool(2, runnable -> {
        final Thread thread = new Thread(runnable, "AEGraph-Solver");
        thread.setDaemon(true);
        thread.setPriority(Thread.NORM_PRIORITY - 1);
        return thread;
    });

    public static final Driver DRIVER = new Driver();

    /** Steps the jobs being calculated, once per server tick. */
    public static final class Driver {

        private Driver() {}

        @SubscribeEvent
        public void onServerTick(TickEvent.ServerTickEvent event) {
            if (event.phase != TickEvent.Phase.END) return;
            final List<GraphCraftingJob> jobs;
            synchronized (PENDING) {
                if (PENDING.isEmpty()) return;
                jobs = new ArrayList<>(PENDING);
            }
            for (final GraphCraftingJob job : jobs) {
                boolean more;
                try {
                    more = job.step();
                } catch (Throwable t) {
                    AEGraphMod.LOG.error("Graph crafting job failed", t);
                    job.cancel(false);
                    more = false;
                }
                if (!more) {
                    synchronized (PENDING) {
                        PENDING.remove(job);
                    }
                }
            }
        }
    }

    private static final List<GraphCraftingJob> PENDING = new ArrayList<>();

    /**
     * What AE2's base class is built for: an item no pattern makes, so its own crafting tree stays empty and
     * costs nothing. The real target is {@link #output}.
     */
    private static IAEItemStack placeholder() {
        final ItemStack stack = new ItemStack(AEGraphMod.coprocessor);
        final NBTTagCompound tag = new NBTTagCompound();
        tag.setBoolean("aegraphPlaceholder", true);
        stack.setTagCompound(tag);
        return AEApi.instance().storage().createItemStack(stack);
    }

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
    private final ICraftingCallback callback;
    private final long startedAt = System.currentTimeMillis();
    private final CraftingTreeNode tree;

    private volatile Phase phase = Phase.BUILD;
    private ModelBuilder.Model model;
    private Future<Plan> solving;
    private Plan plan;
    /** AE2's own calculation: for CPUs without co-processor and as fallback. */
    private Future<ICraftingJob> stockFuture;
    private ICraftingJob stock;
    /** True when the graph plan is the one to show and run on graph CPUs. */
    private boolean graph;
    private long graphBytes = 1;
    private NBTTagCompound snapshot;
    /** Why AE2's plan is used instead of the graph plan (lang key), or null. */
    private String fallbackKey;
    private String fallbackArg = "";

    public GraphCraftingJob(World world, IGrid grid, BaseActionSource source, IAEItemStack what,
            ICraftingCallback callback, Future<ICraftingJob> stockFuture) {
        super(world, grid, source, placeholder(), null);
        this.world = world;
        this.grid = grid;
        this.source = source;
        this.output = what.copy();
        this.callback = callback;
        this.stockFuture = stockFuture;
        this.tree = new StartNode(grid.getCache(ICraftingGrid.class), this);
    }

    /** Whether graph CPUs get the graph plan (false: AE2's job is used on every CPU). */
    public boolean usesGraph() {
        return phase == Phase.DONE && graph;
    }

    /** AE2's own job for the same request, once calculated; null if it is not available. */
    public ICraftingJob stockJob() {
        return stock;
    }

    /** A graph CPU is about to run AE2's job: its co-processor shows the graph plan it could not use. */
    void recordFallback(CraftingCPUCluster cpu) {
        if (snapshot != null && !cpu.isBusy() && GraphCpus.isGraphCpu(cpu)) GraphCpus.recordStarted(cpu, snapshot);
    }

    // --- calculation ------------------------------------------------------------------------------------------

    public Future<ICraftingJob> schedule() {
        synchronized (PENDING) {
            PENDING.add(this);
        }
        return this;
    }

    /** One server tick of calculation; false when the job needs no more ticks. */
    private boolean step() {
        if (phase == Phase.BUILD) build();
        if (phase != Phase.RUNNING) return false;
        if ((stockFuture == null || stockFuture.isDone()) && (solving == null || solving.isDone())) {
            decide();
            return false;
        }
        return true;
    }

    private void build() {
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

    private void takeStock() {
        if (stockFuture == null) return;
        try {
            stock = stockFuture.isCancelled() ? null : stockFuture.get();
        } catch (Throwable t) {
            AEGraphMod.LOG.error("AE2 calculator failed for " + output, t);
            stock = null;
        }
        stockFuture = null;
    }

    private void decide() {
        takeStock();
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
        return plan.hasMissing();
    }

    @Override
    public long getByteTotal() {
        if (!graph) return stock == null ? 1 : stock.getByteTotal();
        // The same job may end up on a normal CPU running AE2's job; reserve enough for either.
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
                        .setCountRequestable(runs * recipe.outAmounts[q]));
            }
        }
    }

    @Override
    public IAEItemStack getOutput() {
        return output;
    }

    @Override
    public CraftingTreeNode getTree() {
        return tree;
    }

    @Override
    public boolean simulateFor(int milli) {
        return !isDone();
    }

    @Override
    public void run() {
        // AE2's calculator of the base class is never used.
    }

    /**
     * Puts the graph plan into a CPU with a co-processor ({@link Hooks#jobForCpu} sends other CPUs AE2's job). Any
     * other CPU refuses the job: AE2's own plan for it lacks items. AE2's CPUs catch the {@link CraftBranchFailure}
     * and stay free.
     */
    public void startCrafting(MECraftingInventory storage, ICraftingCPU rawCluster, BaseActionSource src)
            throws CraftBranchFailure {
        if (phase != Phase.DONE || !graph || !GraphCpus.isGraphCpu(rawCluster)) {
            GraphCpus.tell(src, new ChatComponentTranslation("aegraph.chat.needsCoprocessor"));
            throw new CraftBranchFailure(output, output.getStackSize());
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
                if (got < take) throw new CraftBranchFailure(key, take - got);
                cpu.addStorage(extracted.copy());
            }
            if (plan.missing[i] > 0) throw new CraftBranchFailure(key, plan.missing[i]);
        }
        for (int p = 0; p < plan.runs.length; p++) {
            // Substitution steps (null) need no task: substituting patterns pick their items themselves.
            if (plan.runs[p] > 0 && model.patterns.get(p) != null) cpu.addCrafting(model.patterns.get(p), plan.runs[p]);
        }
        if (snapshot != null) GraphCpus.recordStarted(cpu, snapshot);
    }

    /** The tree a CPU hands its storage to; it has no children and puts the graph plan in instead. */
    private static final class StartNode extends CraftingTreeNode {

        private final GraphCraftingJob job;

        StartNode(ICraftingGrid crafting, GraphCraftingJob job) {
            super(crafting, job, placeholder(), null, -1, 0);
            this.job = job;
        }

        @Override
        public void setJob(MECraftingInventory storage, CraftingCPUCluster cpu, BaseActionSource src)
                throws CraftBranchFailure {
            job.startCrafting(storage, cpu, src);
        }
    }

    // --- Future -----------------------------------------------------------------------------------------------

    @Override
    public boolean cancel(boolean mayInterruptIfRunning) {
        if (phase == Phase.DONE || phase == Phase.CANCELLED) return false;
        phase = Phase.CANCELLED;
        if (stockFuture != null) stockFuture.cancel(mayInterruptIfRunning);
        if (solving != null) solving.cancel(mayInterruptIfRunning);
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
            waitForParts(Long.MAX_VALUE);
            step();
        }
        if (phase == Phase.CANCELLED) throw new InterruptedException();
        return this;
    }

    @Override
    public ICraftingJob get(long timeout, TimeUnit unit)
            throws InterruptedException, ExecutionException, TimeoutException {
        final long deadline = System.nanoTime() + unit.toNanos(timeout);
        while (!isDone()) {
            final long left = deadline - System.nanoTime();
            if (left <= 0) throw new TimeoutException();
            waitForParts(left);
            step();
        }
        if (phase == Phase.CANCELLED) throw new InterruptedException();
        return this;
    }

    /** Blocks until the optimizer and AE2's calculation are done (or the time is up). */
    private void waitForParts(long nanos) throws InterruptedException {
        if (phase != Phase.RUNNING) return;
        final long deadline = System.nanoTime() + nanos;
        for (final Future<?> part : new Future<?>[] { solving, stockFuture }) {
            if (part == null) continue;
            try {
                part.get(Math.max(1, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
            } catch (ExecutionException | TimeoutException | java.util.concurrent.CancellationException ignored) {
                // reported in decide(), or the caller's timeout
            }
        }
    }
}
