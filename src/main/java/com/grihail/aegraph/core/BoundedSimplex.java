package com.grihail.aegraph.core;

/**
 * Dense primal simplex for bounded variables:
 *
 * <pre>
 *   minimize  c.x   subject to   A x = b,   lo <= x <= hi
 * </pre>
 *
 * The caller supplies a starting basis made of unit columns (one per row) that is feasible when every other
 * variable sits at its lower bound. The planner always has such a basis (each row owns a "missing" and a
 * "surplus" column), so no phase 1 is needed and every intermediate basis is a feasible plan.
 */
final class BoundedSimplex {

    static final double INF = Double.POSITIVE_INFINITY;

    private static final double PIVOT_TOL = 1e-9;
    private static final double COST_TOL = 1e-7;
    private static final byte AT_LOWER = 0;
    private static final byte AT_UPPER = 1;
    private static final byte BASIC = 2;

    static final class Result {

        final double[] x;
        final double objective;
        /** False if the iteration limit or deadline was hit; x is still feasible, just not optimal. */
        final boolean optimal;
        /** c - yA for every column; for a column that is minus a unit vector of row r it equals the dual y_r. */
        final double[] reducedCosts;

        Result(double[] x, double objective, boolean optimal, double[] reducedCosts) {
            this.x = x;
            this.objective = objective;
            this.optimal = optimal;
            this.reducedCosts = reducedCosts;
        }
    }

    private BoundedSimplex() {}

    static Result solve(double[][] a, double[] b, double[] c, double[] lo, double[] hi, int[] startBasis,
            long deadlineNanos) {
        final int m = a.length;
        final int n = c.length;
        final double[][] t = new double[m][];
        final double[] beta = new double[m];
        final int[] basis = startBasis.clone();
        final byte[] state = new byte[n];
        for (int r = 0; r < m; r++) {
            state[basis[r]] = BASIC;
        }
        for (int r = 0; r < m; r++) {
            final double[] row = a[r].clone();
            final double unit = row[basis[r]];
            if (Math.abs(Math.abs(unit) - 1.0) > 1e-12) {
                throw new IllegalArgumentException("Start basis column is not a unit column at row " + r);
            }
            double rhs = b[r];
            if (unit < 0) {
                for (int j = 0; j < n; j++) row[j] = -row[j];
                rhs = -rhs;
            }
            for (int j = 0; j < n; j++) {
                if (state[j] != BASIC && row[j] != 0.0 && lo[j] != 0.0) rhs -= row[j] * lo[j];
            }
            t[r] = row;
            beta[r] = rhs;
            final int bv = basis[r];
            if (beta[r] < lo[bv] - 1e-6 || beta[r] > hi[bv] + 1e-6) {
                throw new IllegalArgumentException("Start basis is infeasible at row " + r + ": " + beta[r]);
            }
        }
        final double[] d = c.clone();
        for (int r = 0; r < m; r++) {
            final double cb = c[basis[r]];
            if (cb == 0.0) continue;
            final double[] row = t[r];
            for (int j = 0; j < n; j++) {
                if (row[j] != 0.0) d[j] -= cb * row[j];
            }
        }

        final int maxIterations = 20000 + 60 * (m + n);
        final int[] nz = new int[n];
        int degenerateStreak = 0;
        boolean optimal = false;

        for (int iteration = 0; iteration < maxIterations; iteration++) {
            if ((iteration & 31) == 0 && System.nanoTime() > deadlineNanos) {
                break;
            }
            final boolean bland = degenerateStreak > 40;

            // Pricing.
            int enter = -1;
            double best = 0;
            for (int j = 0; j < n; j++) {
                final byte s = state[j];
                if (s == BASIC) continue;
                final double score;
                if (s == AT_LOWER) {
                    if (d[j] >= -COST_TOL || hi[j] <= lo[j]) continue;
                    score = -d[j];
                } else {
                    if (d[j] <= COST_TOL) continue;
                    score = d[j];
                }
                if (bland) {
                    enter = j;
                    break;
                }
                if (score > best) {
                    best = score;
                    enter = j;
                }
            }
            if (enter < 0) {
                optimal = true;
                break;
            }

            // Ratio test.
            final double dir = state[enter] == AT_LOWER ? 1.0 : -1.0;
            double theta = hi[enter] - lo[enter];
            int leave = -1;
            boolean leaveToUpper = false;
            for (int r = 0; r < m; r++) {
                final double alpha = t[r][enter] * dir;
                if (alpha > PIVOT_TOL) {
                    final int bv = basis[r];
                    double limit = (beta[r] - lo[bv]) / alpha;
                    if (limit < 0) limit = 0;
                    if (betterLeave(limit, theta, r, leave, alpha, t, enter, basis, bland)) {
                        theta = limit;
                        leave = r;
                        leaveToUpper = false;
                    }
                } else if (alpha < -PIVOT_TOL) {
                    final int bv = basis[r];
                    if (hi[bv] == INF) continue;
                    double limit = (hi[bv] - beta[r]) / -alpha;
                    if (limit < 0) limit = 0;
                    if (betterLeave(limit, theta, r, leave, alpha, t, enter, basis, bland)) {
                        theta = limit;
                        leave = r;
                        leaveToUpper = true;
                    }
                }
            }
            if (theta == INF) {
                // Cannot happen with non-negative costs, but never loop forever on bad input.
                throw new IllegalStateException("LP is unbounded");
            }

            if (theta > 1e-12) {
                degenerateStreak = 0;
                for (int r = 0; r < m; r++) {
                    final double tr = t[r][enter];
                    if (tr != 0.0) beta[r] -= tr * dir * theta;
                }
            } else {
                degenerateStreak++;
            }

            if (leave < 0) {
                state[enter] = state[enter] == AT_LOWER ? AT_UPPER : AT_LOWER;
                continue;
            }

            final double enterValue = dir > 0 ? lo[enter] + theta : hi[enter] - theta;
            final int leaving = basis[leave];
            state[leaving] = leaveToUpper ? AT_UPPER : AT_LOWER;
            basis[leave] = enter;
            state[enter] = BASIC;

            // Pivot on (leave, enter).
            final double[] pivotRow = t[leave];
            final double pv = pivotRow[enter];
            int nzCount = 0;
            for (int j = 0; j < n; j++) {
                if (pivotRow[j] != 0.0) {
                    pivotRow[j] /= pv;
                    nz[nzCount++] = j;
                }
            }
            pivotRow[enter] = 1.0;
            for (int r = 0; r < m; r++) {
                if (r == leave) continue;
                final double[] row = t[r];
                final double f = row[enter];
                if (f == 0.0) continue;
                for (int k = 0; k < nzCount; k++) {
                    final int j = nz[k];
                    row[j] -= f * pivotRow[j];
                }
                row[enter] = 0.0;
            }
            final double fd = d[enter];
            if (fd != 0.0) {
                for (int k = 0; k < nzCount; k++) {
                    final int j = nz[k];
                    d[j] -= fd * pivotRow[j];
                }
                d[enter] = 0.0;
            }
            beta[leave] = enterValue;
        }

        final double[] x = new double[n];
        for (int j = 0; j < n; j++) {
            x[j] = state[j] == AT_UPPER ? hi[j] : lo[j];
        }
        for (int r = 0; r < m; r++) {
            final int bv = basis[r];
            // Clamp tiny numerical drift back into the bounds.
            x[bv] = Math.max(lo[bv], Math.min(hi[bv], beta[r]));
        }
        double objective = 0;
        for (int j = 0; j < n; j++) {
            if (x[j] != 0.0) objective += c[j] * x[j];
        }
        return new Result(x, objective, optimal, d);
    }

    private static boolean betterLeave(double limit, double theta, int r, int leave, double alpha, double[][] t,
            int enter, int[] basis, boolean bland) {
        if (limit < theta - 1e-12) return true;
        if (limit > theta + 1e-12 || leave < 0) return false;
        // Tie: Bland picks the smallest variable index, otherwise prefer the numerically larger pivot.
        if (bland) return basis[r] < basis[leave];
        return Math.abs(alpha) > Math.abs(t[leave][enter]);
    }
}
