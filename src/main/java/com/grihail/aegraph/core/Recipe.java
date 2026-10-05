package com.grihail.aegraph.core;

/**
 * One encoded pattern as seen by the planner: integer item ids with per-run amounts.
 * Inputs and outputs are kept separately (not netted), because an item that is both consumed and
 * returned (a catalyst, e.g. a press stamp) still has to be physically present to start a run.
 */
public final class Recipe {

    public final int[] inItems;
    public final long[] inAmounts;
    public final int[] outItems;
    public final long[] outAmounts;
    /** Small cost per run, keeps the optimizer from doing pointless runs. */
    public final double runCost;
    /** Order in which the AE2 crafting CPU visits patterns, used by the execution check. */
    public final long cpuOrder;
    /** The original pattern object (opaque to the core). */
    public final Object handle;

    public Recipe(int[] inItems, long[] inAmounts, int[] outItems, long[] outAmounts, double runCost,
            long cpuOrder, Object handle) {
        if (inItems.length != inAmounts.length || outItems.length != outAmounts.length) {
            throw new IllegalArgumentException("Mismatched recipe arrays");
        }
        this.inItems = inItems;
        this.inAmounts = inAmounts;
        this.outItems = outItems;
        this.outAmounts = outAmounts;
        this.runCost = runCost;
        this.cpuOrder = cpuOrder;
        this.handle = handle;
    }

    public long inputOf(int item) {
        long sum = 0;
        for (int k = 0; k < inItems.length; k++) {
            if (inItems[k] == item) sum += inAmounts[k];
        }
        return sum;
    }

    public long outputOf(int item) {
        long sum = 0;
        for (int k = 0; k < outItems.length; k++) {
            if (outItems[k] == item) sum += outAmounts[k];
        }
        return sum;
    }
}
