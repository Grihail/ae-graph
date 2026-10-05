package com.grihail.aegraph.core;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Replays a plan the way the AE2 crafting CPU runs it: every round each pattern with remaining runs is pushed
 * as many times as the CPU inventory allows (in CPU order), and the outputs only come back at the end of the
 * round. A plan whose totals balance can still deadlock - e.g. a loop that needs one item to get started, or a
 * catalyst that is never present. Such cases are reported as a deficit so the planner can add a "seed".
 */
final class ExecutionSimulator {

    enum Status {
        OK,
        STUCK,
        /** Ran out of the step budget while still making progress. */
        UNVERIFIED
    }

    static final class Outcome {

        final Status status;
        /** For {@link Status#STUCK}: extra items that unblock one waiting pattern. */
        final long[] deficit;
        /** For {@link Status#STUCK}: recipes that still had runs left. */
        final boolean[] waiting;

        Outcome(Status status, long[] deficit, boolean[] waiting) {
            this.status = status;
            this.deficit = deficit;
            this.waiting = waiting;
        }
    }

    private ExecutionSimulator() {}

    static Outcome run(Problem problem, long[] runs, long[] initialStock, long[] storageSpare, double[] itemCost,
            long maxSteps) {
        final List<Integer> order = new ArrayList<>();
        for (int r = 0; r < runs.length; r++) {
            if (runs[r] > 0) order.add(r);
        }
        Collections.sort(order, (x, y) -> Long.compare(problem.recipes.get(x).cpuOrder, problem.recipes.get(y).cpuOrder));

        final long[] stock = initialStock.clone();
        final long[] pending = new long[problem.itemCount];
        final long[] remaining = runs.clone();
        int active = order.size();
        long steps = 0;

        while (active > 0) {
            boolean fired = false;
            for (final int r : order) {
                if (remaining[r] <= 0) continue;
                steps++;
                final Recipe recipe = problem.recipes.get(r);
                long k = remaining[r];
                for (int q = 0; q < recipe.inItems.length && k > 0; q++) {
                    k = Math.min(k, stock[recipe.inItems[q]] / recipe.inAmounts[q]);
                }
                if (k <= 0) continue;
                for (int q = 0; q < recipe.inItems.length; q++) {
                    stock[recipe.inItems[q]] -= k * recipe.inAmounts[q];
                }
                for (int q = 0; q < recipe.outItems.length; q++) {
                    pending[recipe.outItems[q]] += k * recipe.outAmounts[q];
                }
                remaining[r] -= k;
                if (remaining[r] == 0) active--;
                fired = true;
            }
            for (int i = 0; i < pending.length; i++) {
                if (pending[i] != 0) {
                    stock[i] += pending[i];
                    pending[i] = 0;
                }
            }
            if (!fired) {
                final boolean[] waiting = new boolean[runs.length];
                for (int r = 0; r < runs.length; r++) waiting[r] = remaining[r] > 0;
                return new Outcome(Status.STUCK, cheapestUnblock(problem, order, remaining, stock, storageSpare, itemCost),
                        waiting);
            }
            if (steps > maxSteps) {
                return new Outcome(Status.UNVERIFIED, null, null);
            }
        }
        return new Outcome(Status.OK, null, null);
    }

    /**
     * Picks the waiting pattern that is cheapest to unblock and returns what it lacks. Lending from storage is
     * cheapest, crafting the seed with patterns outside the deadlock comes next, items that can't be obtained
     * at all are the last resort.
     */
    private static long[] cheapestUnblock(Problem problem, List<Integer> order, long[] remaining, long[] stock,
            long[] storageSpare, double[] itemCost) {
        final boolean[] craftableOutside = new boolean[problem.itemCount];
        for (int r = 0; r < problem.recipes.size(); r++) {
            if (remaining[r] > 0) continue;
            for (final int out : problem.recipes.get(r).outItems) craftableOutside[out] = true;
        }
        long[] best = null;
        double bestCost = Double.POSITIVE_INFINITY;
        for (final int r : order) {
            if (remaining[r] <= 0) continue;
            final Recipe recipe = problem.recipes.get(r);
            final long[] lack = new long[problem.itemCount];
            double cost = 0;
            for (int q = 0; q < recipe.inItems.length; q++) {
                final int item = recipe.inItems[q];
                final long short_ = recipe.inAmounts[q] - stock[item];
                if (short_ > 0 && lack[item] < short_) {
                    final long extra = short_ - lack[item];
                    final double unit;
                    if (storageSpare[item] >= short_) {
                        unit = Math.max(itemCost[item], 1e-9);
                    } else if (craftableOutside[item]) {
                        unit = 2 * Math.max(itemCost[item], 1e-9) + 1;
                    } else {
                        unit = Math.max(problem.missingCost[item], 1);
                    }
                    cost += extra * unit;
                    lack[item] = short_;
                }
            }
            if (cost < bestCost) {
                bestCost = cost;
                best = lack;
            }
        }
        return best;
    }
}
