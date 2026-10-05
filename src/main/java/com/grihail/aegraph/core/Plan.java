package com.grihail.aegraph.core;

/**
 * Result of planning: how many times to run each recipe and what to take from storage.
 */
public final class Plan {

    /** Runs per recipe, indexed like {@link Problem#recipes}. */
    public final long[] runs;
    /** Items to pull from ME storage into the crafting CPU (includes seeds for loops/catalysts). */
    public final long[] extract;
    /** Items that are needed but neither stored nor craftable. */
    public final long[] missing;
    /** Total amount of the target the plan produces (may exceed the request because of batch sizes). */
    public final long targetProduced;
    /** False if the execution check could not prove that the CPU can run the plan to the end. */
    public final boolean executionVerified;
    public final String notes;

    public Plan(long[] runs, long[] extract, long[] missing, long targetProduced, boolean executionVerified,
            String notes) {
        this.runs = runs;
        this.extract = extract;
        this.missing = missing;
        this.targetProduced = targetProduced;
        this.executionVerified = executionVerified;
        this.notes = notes;
    }

    public boolean hasMissing() {
        for (long m : missing) {
            if (m > 0) return true;
        }
        return false;
    }

    public long totalRuns() {
        long sum = 0;
        for (long r : runs) sum += r;
        return sum;
    }

    public String describe(Problem problem) {
        final StringBuilder sb = new StringBuilder();
        sb.append("Plan for ").append(problem.targetAmount).append(" x ").append(problem.name(problem.target))
                .append(" (produces ").append(targetProduced).append(")\n");
        for (int r = 0; r < runs.length; r++) {
            if (runs[r] <= 0) continue;
            final Recipe recipe = problem.recipes.get(r);
            sb.append("  run ").append(runs[r]).append(" x [");
            for (int k = 0; k < recipe.inItems.length; k++) {
                if (k > 0) sb.append(", ");
                sb.append(recipe.inAmounts[k]).append(' ').append(problem.name(recipe.inItems[k]));
            }
            sb.append("] -> [");
            for (int k = 0; k < recipe.outItems.length; k++) {
                if (k > 0) sb.append(", ");
                sb.append(recipe.outAmounts[k]).append(' ').append(problem.name(recipe.outItems[k]));
            }
            sb.append("]\n");
        }
        for (int i = 0; i < extract.length; i++) {
            if (extract[i] > 0) sb.append("  take ").append(extract[i]).append(' ').append(problem.name(i)).append('\n');
        }
        for (int i = 0; i < missing.length; i++) {
            if (missing[i] > 0) sb.append("  MISSING ").append(missing[i]).append(' ').append(problem.name(i)).append('\n');
        }
        if (!executionVerified) sb.append("  (execution order could not be fully verified)\n");
        if (notes != null && !notes.isEmpty()) sb.append("  ").append(notes).append('\n');
        return sb.toString();
    }
}
