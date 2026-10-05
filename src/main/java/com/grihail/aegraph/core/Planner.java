package com.grihail.aegraph.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Graph crafting planner.
 * <p>
 * Every pattern is a column of a production matrix, every item is a row. A linear program picks how often each
 * pattern runs so that the target is produced while the weighted amount of items taken from storage is minimal.
 * Because whole graph is solved at once, byproducts of one branch are fed into other branches, alternative
 * routes are compared and processing loops (recycling) are used whenever they pay off - instead of AE2's single
 * greedy chain per request.
 * <p>
 * <b>What a stored item costs.</b> Counting items is wrong: one item that yields 8 resources plus a byproduct
 * would cost the same as one that yields 2, and small requests would burn the valuable one and pile up the
 * byproduct forever. So stored items are priced by what they are worth: a first LP (pricing) solves the request as
 * if every stored item could be bought without limit at its base weight, and its duals (shadow prices) give the
 * marginal value of every item. Items the pricing plan does not touch get their potential value - the value of what
 * their best pattern turns them into. The real LP then charges these values for items taken from storage (stored
 * intermediates slightly less, so leftovers get used up first) and a small penalty for every surplus unit, so
 * between equally good plans the one without junk wins.
 * <p>
 * <b>Stock first.</b> Value pricing makes routes that convert into each other equally good (A = 8 resources + B
 * is worth exactly as much as the resources it yields), so a rule decides between them: producing an ingredient
 * that already lies in storage costs extra. The stored one is used up before any pattern adds more of it.
 * <p>
 * Patterns can only run a whole number of times, so the LP solution is made integral by branch and bound (see
 * {@link #integerRuns}). Finally the plan is replayed against the CPU behaviour; loops and catalysts that would
 * deadlock get seed items.
 */
public final class Planner {

    public static final class Settings {

        /** Runs above this are simply rounded up, the relative waste is negligible. */
        public double bigRunThreshold = 24;
        /** Wall-clock budget for the whole planning, after it the remaining fractions are rounded up. */
        public long timeBudgetMillis = 4000;
        public int maxSeedRounds = 12;
        public long maxSimulationSteps = 4_000_000L;
        /** Value multiplier for stored items some pattern can make: below 1 uses up leftovers first. */
        public double storedIntermediateFactor = 0.9;
        /** Penalty per surplus unit (resources or byproducts the request did not need), relative to its weight. */
        public double surplusPenalty = 0.01;
        /**
         * "Stock first": how much worse (relative cost) a plan may get to avoid making ingredients that already lie
         * in storage. Routes that are equally efficient always eat the stockpile first.
         */
        public double stockFirstTolerance = 0.05;
        /** Branch and bound node limit; past it the best plan found so far is used. */
        public int maxBranchNodes = 3000;
    }

    private final Problem problem;
    private final Settings settings;
    private final long deadline;
    private final int depth;

    // LP layout
    private final int recipeCount;
    private final int[] rowItem;
    private final int[] rowOfItem;
    private final int columnCount;
    private final double[][] matrix;
    /** Costs of the real LP (set after pricing). */
    private final double[] cost;
    private final int[] extractColumn;
    private final int[] missingColumn;
    private final int[] surplusColumn;
    private final double[] baseLo;
    private final double[] baseHi;
    /** Cost of taking one unit of each item from storage, after pricing. */
    private final double[] itemCost;
    /** Real LP costs without the stock-first rule. */
    private final double[] baseCost;
    /** Only what the plan consumes (no junk penalties): the yardstick for the stock-first tolerance. */
    private final double[] efficiencyCost;
    /** Per recipe: value of ingredients it makes that already lie in storage. */
    private final double[] restockValue;
    /** Penalty per unit of each item left over at the end of the plan. */
    private final double[] surplusCost;
    private double stockFirstWeight = 0;

    private int lpSolves = 0;
    private int lpNotOptimal = 0;
    private long lpNanos = 0;
    private long lpMaxNanos = 0;
    private boolean timedOut = false;

    private Planner(Problem problem, Settings settings, long deadline, int depth) {
        this.problem = problem;
        this.settings = settings;
        this.deadline = deadline;
        this.depth = depth;
        this.recipeCount = problem.recipes.size();

        // Rows: every item some pattern consumes, plus the target.
        final boolean[] needsRow = new boolean[problem.itemCount];
        needsRow[problem.target] = true;
        for (final Recipe recipe : problem.recipes) {
            for (final int item : recipe.inItems) needsRow[item] = true;
        }
        int rows = 0;
        this.rowOfItem = new int[problem.itemCount];
        Arrays.fill(rowOfItem, -1);
        for (int i = 0; i < problem.itemCount; i++) {
            if (needsRow[i]) rowOfItem[i] = rows++;
        }
        this.rowItem = new int[rows];
        for (int i = 0; i < problem.itemCount; i++) {
            if (rowOfItem[i] >= 0) rowItem[rowOfItem[i]] = i;
        }

        // Columns: recipe runs | storage extraction | missing | surplus.
        int col = recipeCount;
        this.extractColumn = new int[rows];
        for (int r = 0; r < rows; r++) {
            final int item = rowItem[r];
            extractColumn[r] = item != problem.target && problem.available[item] > 0 ? col++ : -1;
        }
        this.missingColumn = new int[rows];
        for (int r = 0; r < rows; r++) missingColumn[r] = col++;
        this.surplusColumn = new int[rows];
        for (int r = 0; r < rows; r++) surplusColumn[r] = col++;
        this.columnCount = col;

        this.matrix = new double[rows][columnCount];
        this.cost = new double[columnCount];
        this.baseLo = new double[columnCount];
        this.baseHi = new double[columnCount];
        Arrays.fill(baseHi, BoundedSimplex.INF);

        for (int p = 0; p < recipeCount; p++) {
            final Recipe recipe = problem.recipes.get(p);
            for (int q = 0; q < recipe.outItems.length; q++) {
                final int r = rowOfItem[recipe.outItems[q]];
                if (r >= 0) matrix[r][p] += recipe.outAmounts[q];
            }
            for (int q = 0; q < recipe.inItems.length; q++) {
                matrix[rowOfItem[recipe.inItems[q]]][p] -= recipe.inAmounts[q];
            }
        }
        for (int r = 0; r < rows; r++) {
            if (extractColumn[r] >= 0) {
                matrix[r][extractColumn[r]] = 1;
                baseHi[extractColumn[r]] = problem.available[rowItem[r]];
            }
            matrix[r][missingColumn[r]] = 1;
            matrix[r][surplusColumn[r]] = -1;
        }
        this.itemCost = new double[problem.itemCount];
        this.baseCost = new double[columnCount];
        this.efficiencyCost = new double[columnCount];
        this.restockValue = new double[recipeCount];
        this.surplusCost = new double[problem.itemCount];
    }

    // --- pricing ----------------------------------------------------------------------------------------------

    /**
     * Values every item and fills {@link #cost} / {@link #itemCost} for the real LP. See the class comment.
     */
    private void price(long[] demand) {
        final int rows = rowItem.length;
        final double[] value = new double[problem.itemCount];

        // Pricing LP: stored items can be bought without limit at their weight.
        final double[] pricingCost = new double[columnCount];
        final double[] pricingHi = baseHi.clone();
        for (int p = 0; p < recipeCount; p++) pricingCost[p] = problem.recipes.get(p).runCost;
        for (int r = 0; r < rows; r++) {
            final int item = rowItem[r];
            if (extractColumn[r] >= 0) {
                pricingCost[extractColumn[r]] = problem.weight[item];
                pricingHi[extractColumn[r]] = BoundedSimplex.INF;
            }
            pricingCost[missingColumn[r]] = problem.missingCost[item];
        }
        final BoundedSimplex.Result pricing = solveWith(demand, pricingCost, baseLo, pricingHi);
        // Items the efficient plan makes itself (intermediates, byproducts) - the stock-first rule is about these,
        // not about raw materials that come from storage anyway.
        final boolean[] madeByPlan = new boolean[problem.itemCount];
        for (int p = 0; p < recipeCount; p++) {
            if (pricing.x[p] <= 1e-9) continue;
            for (final int out : problem.recipes.get(p).outItems) madeByPlan[out] = true;
        }
        for (int r = 0; r < rows; r++) {
            final int item = rowItem[r];
            final double dual = pricing.reducedCosts[surplusColumn[r]];
            value[item] = Math.max(0, Math.min(dual, cap(item)));
        }

        // Potential: what an item's best pattern turns it into. Covers items the pricing plan does not use
        // (their dual is 0 or ambiguous), e.g. a stockpiled byproduct.
        for (int pass = 0; pass < 32; pass++) {
            boolean changed = false;
            for (final Recipe recipe : problem.recipes) {
                double out = -recipe.runCost;
                for (int q = 0; q < recipe.outItems.length; q++) out += recipe.outAmounts[q] * value[recipe.outItems[q]];
                double in = 0;
                for (int q = 0; q < recipe.inItems.length; q++) in += recipe.inAmounts[q] * value[recipe.inItems[q]];
                for (int q = 0; q < recipe.inItems.length; q++) {
                    final int item = recipe.inItems[q];
                    final double others = in - recipe.inAmounts[q] * value[item];
                    final double potential = Math.min(cap(item), (out - others) / recipe.inAmounts[q]);
                    if (potential > value[item] + 1e-12) {
                        value[item] = potential;
                        changed = true;
                    }
                }
            }
            if (!changed) break;
        }

        for (int i = 0; i < problem.itemCount; i++) {
            final double w = problem.weight[i];
            final double v = Math.max(value[i], 1e-3 * w);
            itemCost[i] = problem.craftable[i] ? v * settings.storedIntermediateFactor : v;
        }

        // Real LP costs (without the stock-first rule, see chooseStockFirstWeight).
        for (int p = 0; p < recipeCount; p++) {
            final Recipe recipe = problem.recipes.get(p);
            double c = recipe.runCost;
            double restock = 0;
            for (int q = 0; q < recipe.outItems.length; q++) {
                final int item = recipe.outItems[q];
                if (rowOfItem[item] < 0) {
                    // Outputs nothing consumes are pure surplus.
                    c += settings.surplusPenalty * problem.weight[item] * recipe.outAmounts[q];
                } else if (item != problem.target && problem.available[item] > 0 && madeByPlan[item]) {
                    // Makes more of an intermediate that already lies in storage.
                    restock += Math.max(value[item], 1e-3 * problem.weight[item]) * recipe.outAmounts[q];
                }
            }
            efficiencyCost[p] = recipe.runCost;
            baseCost[p] = c;
            restockValue[p] = restock;
        }
        for (int r = 0; r < rows; r++) {
            final int item = rowItem[r];
            if (extractColumn[r] >= 0) baseCost[extractColumn[r]] = efficiencyCost[extractColumn[r]] = itemCost[item];
            baseCost[missingColumn[r]] = efficiencyCost[missingColumn[r]] = problem.missingCost[item];
            // By value, not by count: 4 junk items cost as much as the 1 item a pattern compresses them into, so
            // processing leftovers further never looks like "less surplus".
            surplusCost[item] = settings.surplusPenalty * Math.max(value[item], 1e-3 * problem.weight[item]);
            baseCost[surplusColumn[r]] = surplusCost[item];
        }
        System.arraycopy(baseCost, 0, cost, 0, columnCount);
    }

    /**
     * Stock first, as a strict secondary goal: among plans whose cost is within {@code stockFirstTolerance} of the
     * best possible, prefer the one that makes the least of ingredients already lying in storage. Implemented as a
     * penalty per unit of value restocked, with the strongest weight whose LP optimum still stays within the
     * tolerance (lexicographic optimization by weight search). Equivalent routes - like "A makes 8 resources + B" vs
     * "B makes 2 resources" - then always eat the stored B, while the rule can never make a plan worse than the
     * tolerance allows.
     */
    private void chooseStockFirstWeight(long[] demand) {
        boolean any = false;
        for (final double r : restockValue) any |= r > 0;
        if (!any || settings.stockFirstTolerance <= 0) return;
        // Efficiency only (what is consumed); the junk penalty is a tie-breaker and must not veto the rule.
        final BoundedSimplex.Result plain = solveWith(demand, efficiencyCost, baseLo, baseHi);
        final double best = plain.objective;
        final double allowed = best + settings.stockFirstTolerance * Math.abs(best) + 1e-9;
        final double[] weighted = baseCost.clone();
        for (double weight = 8; weight >= 1.0 / 64; weight /= 4) {
            for (int p = 0; p < recipeCount; p++) weighted[p] = baseCost[p] + weight * restockValue[p];
            final BoundedSimplex.Result result = solveWith(demand, weighted, baseLo, baseHi);
            double realCost = 0;
            for (int j = 0; j < columnCount; j++) realCost += efficiencyCost[j] * result.x[j];
            if (realCost <= allowed) {
                System.arraycopy(weighted, 0, cost, 0, columnCount);
                stockFirstWeight = weight;
                return;
            }
        }
    }

    /** Most an item can be worth: its purchase price if stored, otherwise the missing penalty. */
    private double cap(int item) {
        return problem.available[item] > 0 ? problem.weight[item] : problem.missingCost[item];
    }

    public static Plan plan(Problem problem, Settings settings) {
        return new Planner(problem, settings, System.nanoTime() + settings.timeBudgetMillis * 1_000_000L, 0).run();
    }

    private Plan run() {
        final long[] demand = new long[problem.itemCount];
        demand[problem.target] = problem.targetAmount;
        final StringBuilder notes = new StringBuilder();

        price(demand);
        chooseStockFirstWeight(demand);
        final long[] runs = integerRuns(demand);
        final long[] net = netFlow(runs);
        final long[] extract = new long[problem.itemCount];
        final long[] missing = new long[problem.itemCount];
        for (int i = 0; i < problem.itemCount; i++) {
            if (i == problem.target) continue;
            final long need = -net[i];
            if (need > 0) {
                extract[i] = Math.min(need, problem.available[i]);
                missing[i] = need - extract[i];
            }
        }
        if (net[problem.target] < problem.targetAmount) {
            missing[problem.target] = problem.targetAmount - net[problem.target];
        }

        // Replay on the CPU model. When it deadlocks, provide a seed: lend it from storage if possible
        // (it comes back as surplus at the end), otherwise craft it with a sub-plan that avoids the blocked patterns.
        boolean verified = false;
        for (int attempt = 0; attempt < settings.maxSeedRounds; attempt++) {
            final long[] stock = new long[problem.itemCount];
            final long[] spare = new long[problem.itemCount];
            for (int i = 0; i < stock.length; i++) {
                stock[i] = extract[i] + missing[i];
                spare[i] = Math.max(0, problem.available[i] - extract[i]);
            }
            final ExecutionSimulator.Outcome outcome = ExecutionSimulator
                    .run(problem, runs, stock, spare, itemCost, settings.maxSimulationSteps);
            if (outcome.status == ExecutionSimulator.Status.OK) {
                verified = true;
                break;
            }
            if (outcome.status == ExecutionSimulator.Status.UNVERIFIED) {
                notes.append("execution check hit its step limit; ");
                break;
            }
            if (outcome.deficit == null) break;
            for (int i = 0; i < outcome.deficit.length; i++) {
                long lack = outcome.deficit[i];
                if (lack <= 0) continue;
                final long lend = Math.min(lack, problem.available[i] - extract[i]);
                if (lend > 0) {
                    extract[i] += lend;
                    lack -= lend;
                }
                if (lack <= 0) continue;
                if (depth < MAX_SEED_DEPTH) {
                    seedSubPlan(i, lack, outcome.waiting, runs, extract, missing);
                } else {
                    missing[i] += lack;
                }
            }
        }

        if (timedOut) notes.append("time budget reached, rounded up remaining fractions; ");
        if (stockFirstWeight > 0) notes.append("stock-first weight ").append(stockFirstWeight).append("; ");
        notes.append("lp solves: ").append(lpSolves).append(" (").append(lpNanos / 1_000_000).append(" ms, max ")
                .append(lpMaxNanos / 1_000_000).append(" ms");
        if (lpNotOptimal > 0) notes.append(", ").append(lpNotOptimal).append(" stopped early");
        notes.append(')');
        return new Plan(runs, extract, missing, netFlow(runs)[problem.target], verified, notes.toString());
    }

    private static final int MAX_SEED_DEPTH = 2;

    /** Plans {@code amount} of {@code item} without the blocked recipes and merges it into the current plan. */
    private void seedSubPlan(int item, long amount, boolean[] blocked, long[] runs, long[] extract, long[] missing) {
        final List<Recipe> subRecipes = new ArrayList<>();
        final List<Integer> mapping = new ArrayList<>();
        for (int p = 0; p < recipeCount; p++) {
            if (blocked[p]) continue;
            subRecipes.add(problem.recipes.get(p));
            mapping.add(p);
        }
        final long[] available = new long[problem.itemCount];
        for (int i = 0; i < available.length; i++) {
            available[i] = Math.max(0, problem.available[i] - extract[i]);
        }
        final Problem sub = new Problem(problem.itemCount, item, amount, available, problem.weight,
                problem.craftable, problem.missingCost, subRecipes, problem.itemNames);
        final Plan subPlan = new Planner(sub, settings, deadline, depth + 1).run();
        for (int k = 0; k < subRecipes.size(); k++) {
            runs[mapping.get(k)] += subPlan.runs[k];
        }
        for (int i = 0; i < problem.itemCount; i++) {
            extract[i] += subPlan.extract[i];
            missing[i] += subPlan.missing[i];
        }
    }

    private long[] netFlow(long[] runs) {
        final long[] net = new long[problem.itemCount];
        for (int p = 0; p < recipeCount; p++) {
            final long x = runs[p];
            if (x == 0) continue;
            final Recipe recipe = problem.recipes.get(p);
            for (int q = 0; q < recipe.outItems.length; q++) {
                net[recipe.outItems[q]] = Math.addExact(net[recipe.outItems[q]], Math.multiplyExact(x, recipe.outAmounts[q]));
            }
            for (int q = 0; q < recipe.inItems.length; q++) {
                net[recipe.inItems[q]] = Math.subtractExact(net[recipe.inItems[q]], Math.multiplyExact(x, recipe.inAmounts[q]));
            }
        }
        return net;
    }

    /**
     * Solves the LP for the given demand and turns the run counts into whole numbers with depth-first branch and
     * bound: branch on a fractional run count (x <= floor | x >= ceil), cheaper LP child first, and skip every
     * branch whose LP value cannot beat the best whole-number plan found so far. Every LP solution rounded up is
     * a valid plan, so a good answer exists from the first LP on, and the node/time limit only affects huge graphs.
     */
    private long[] integerRuns(long[] demand) {
        final double[] lo = baseLo.clone();
        final double[] hi = baseHi.clone();
        final BoundedSimplex.Result root = solve(demand, lo, hi);
        best = roundUp(root);
        bestCost = integerCost(best, demand);
        branchNodes = 0;
        // A plan that lacks raw materials can't run as is (AE2's plan or "missing" mode takes over); a deep
        // search for the last fraction of efficiency would only delay the answer.
        nodeLimit = settings.maxBranchNodes;
        for (int r = 0; r < rowItem.length; r++) {
            if (rowItem[r] != problem.target && root.x[missingColumn[r]] > 1e-6) {
                nodeLimit = Math.min(nodeLimit, 200);
                break;
            }
        }
        branch(demand, lo, hi, root);
        repair(demand);
        return best;
    }

    /**
     * Rounding a run count up (big counts are rounded directly) makes that pattern consume more than the LP
     * planned, and the patterns feeding it were sized for the fractional value - the extra input would show up as
     * "missing" although storage and other patterns can supply it. So the rounded runs are fixed as lower bounds
     * and the LP is solved again: it covers the extra demand the cheapest way (stock, other routes, upstream
     * patterns). The new upstream runs get rounded too and may leave a deficit one step further up, so the bounds
     * accumulate and this repeats until the whole chain is consistent; the result replaces the plan if cheaper.
     */
    private void repair(long[] demand) {
        // Two ways to settle a deficit: make more of it (raise the feeding patterns), or - when the item is scarce
        // - run its consumer one time less and let the LP cover the gap through other routes.
        for (final boolean capConsumers : new boolean[] { false, true }) {
            if (!hasMissingInputs(best, demand)) return;
            final double[] lo = baseLo.clone();
            final double[] hi = baseHi.clone();
            long[] current = best;
            for (int round = 0; round < 40; round++) {
                final boolean[] short_ = deficits(current, demand);
                if (short_ == null) break;
                for (int p = 0; p < recipeCount; p++) {
                    if (capConsumers && consumesAny(p, short_) && current[p] > 0) {
                        hi[p] = Math.min(hi[p], current[p] - 1);
                        lo[p] = Math.min(lo[p], hi[p]);
                    } else {
                        lo[p] = Math.min(Math.max(lo[p], current[p]), hi[p]);
                    }
                }
                current = roundUp(solve(demand, lo, hi));
            }
            final double cost = integerCost(current, demand);
            if (cost < bestCost - 1e-9) {
                best = current;
                bestCost = cost;
            }
        }
    }

    private boolean consumesAny(int p, boolean[] items) {
        for (final int in : problem.recipes.get(p).inItems) {
            if (items[in]) return true;
        }
        return false;
    }

    /** Items a whole-number plan lacks beyond what storage has, or null if none. */
    private boolean[] deficits(long[] runs, long[] demand) {
        final long[] net = netFlow(runs);
        boolean[] lacking = null;
        for (final int item : rowItem) {
            if (item == problem.target) continue;
            if (demand[item] - net[item] > problem.available[item]) {
                if (lacking == null) lacking = new boolean[problem.itemCount];
                lacking[item] = true;
            }
        }
        return lacking;
    }

    private boolean hasMissingInputs(long[] runs, long[] demand) {
        return deficits(runs, demand) != null;
    }

    private long[] best;
    private double bestCost;
    private int branchNodes;
    private int nodeLimit;

    private void branch(long[] demand, double[] lo, double[] hi, BoundedSimplex.Result node) {
        final long[] rounded = roundUp(node);
        final double roundedCost = integerCost(rounded, demand);
        if (roundedCost < bestCost) {
            best = rounded;
            bestCost = roundedCost;
        }
        // Nothing below this node can be meaningfully better (0.01% gap) than the plan we already have.
        if (node.objective >= bestCost - Math.max(1e-7, 1e-4 * Math.abs(bestCost))) return;

        // Branch on the most fractional small run count; big counts are rounded up, the waste is negligible.
        int pick = -1;
        double pickFraction = 0;
        for (int p = 0; p < recipeCount; p++) {
            final double x = node.x[p];
            if (isIntegral(x) || x >= settings.bigRunThreshold) continue;
            final double fraction = Math.min(x - Math.floor(x), Math.ceil(x) - x);
            if (fraction > pickFraction) {
                pickFraction = fraction;
                pick = p;
            }
        }
        if (pick < 0) return;
        if (++branchNodes > nodeLimit || System.nanoTime() > deadline) {
            timedOut = true;
            return;
        }

        final double x = node.x[pick];
        final double[] downLo = lo.clone();
        final double[] downHi = hi.clone();
        downHi[pick] = Math.floor(x);
        final double[] upLo = lo.clone();
        final double[] upHi = hi.clone();
        upLo[pick] = Math.floor(x) + 1;
        final BoundedSimplex.Result down = solve(demand, downLo, downHi);
        final BoundedSimplex.Result up = upLo[pick] <= upHi[pick] ? solve(demand, upLo, upHi) : null;
        if (up == null || down.objective <= up.objective) {
            branch(demand, downLo, downHi, down);
            if (up != null) branch(demand, upLo, upHi, up);
        } else {
            branch(demand, upLo, upHi, up);
            branch(demand, downLo, downHi, down);
        }
    }


    private long[] roundUp(BoundedSimplex.Result result) {
        final long[] runs = new long[recipeCount];
        for (int p = 0; p < recipeCount; p++) {
            final double x = result.x[p];
            runs[p] = isIntegral(x) ? Math.round(x) : (long) Math.ceil(x - 1e-7);
            if (runs[p] < 0) runs[p] = 0;
        }
        return runs;
    }

    /** Objective of a whole-number plan, with storage, shortages and surplus settled exactly like the LP does. */
    private double integerCost(long[] runs, long[] demand) {
        final long[] net = netFlow(runs);
        double total = 0;
        for (int p = 0; p < recipeCount; p++) total += runs[p] * cost[p];
        for (final int item : rowItem) {
            final long need = demand[item] - net[item];
            if (need > 0) {
                final long take = item == problem.target ? 0 : Math.min(need, problem.available[item]);
                total += take * itemCost[item] + (need - take) * problem.missingCost[item];
            } else {
                total += -need * surplusCost[item];
            }
        }
        return total;
    }

    private BoundedSimplex.Result solve(long[] demand, double[] lo, double[] hi) {
        return solveWith(demand, cost, lo, hi);
    }

    private BoundedSimplex.Result solveWith(long[] demand, double[] costs, double[] lo, double[] hi) {
        lpSolves++;
        final int rows = rowItem.length;
        final double[] b = new double[rows];
        final int[] basis = new int[rows];
        for (int r = 0; r < rows; r++) {
            final int item = rowItem[r];
            // A tiny distinct perturbation keeps the simplex out of degenerate pivot cycles (most rows are 0);
            // run counts are rounded afterwards, so it never shows up in the plan. Rows without demand get a
            // negative one, so they start on their surplus column rather than on the "missing" column.
            final double perturbation = 1e-9 * (1 + (r * 7919L) % 101);
            b[r] = demand[item] > 0 ? demand[item] + perturbation : demand[item] - perturbation;
            double rhs = b[r];
            for (int p = 0; p < recipeCount; p++) {
                if (lo[p] != 0.0 && matrix[r][p] != 0.0) rhs -= matrix[r][p] * lo[p];
            }
            basis[r] = rhs >= 0 ? missingColumn[r] : surplusColumn[r];
        }
        // Even past the deadline, a feasible (if not optimal) answer is returned.
        final long started = System.nanoTime();
        final long lpDeadline = Math.max(deadline, started + 200_000_000L);
        final BoundedSimplex.Result result = BoundedSimplex.solve(matrix, b, costs, lo, hi, basis, lpDeadline);
        final long took = System.nanoTime() - started;
        lpNanos += took;
        lpMaxNanos = Math.max(lpMaxNanos, took);
        if (!result.optimal) lpNotOptimal++;
        return result;
    }

    /** Run counts are whole machine runs; anything this close to an integer is numerical noise. */
    private static boolean isIntegral(double x) {
        return Math.abs(x - Math.rint(x)) < 1e-4;
    }

    /** Items whose rows exist in the LP (consumed by some recipe, or the target). Exposed for tests. */
    List<Integer> rowItems() {
        final List<Integer> list = new ArrayList<>();
        for (final int item : rowItem) list.add(item);
        return list;
    }
}
