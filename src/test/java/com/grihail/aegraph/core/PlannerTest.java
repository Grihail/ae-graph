package com.grihail.aegraph.core;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.Test;

public class PlannerTest {

    /** Tiny DSL for building problems by item name. */
    private static final class Builder {

        final Map<String, Integer> ids = new HashMap<>();
        final List<String> names = new ArrayList<>();
        final List<Recipe> recipes = new ArrayList<>();
        final Map<Integer, Long> stock = new HashMap<>();
        final Map<Integer, Double> weight = new HashMap<>();

        int id(String name) {
            return ids.computeIfAbsent(name, n -> {
                names.add(n);
                return names.size() - 1;
            });
        }

        Builder stock(String name, long amount) {
            stock.put(id(name), amount);
            return this;
        }

        Builder fluid(String name) {
            weight.put(id(name), 0.001);
            return this;
        }

        /** "2 a, 1 b" -> parallel arrays */
        Builder recipe(String in, String out) {
            final int[][] i = parse(in);
            final int[][] o = parse(out);
            recipes.add(new Recipe(i[0], toLong(i[1]), o[0], toLong(o[1]), 0.001, recipes.size(), in + " -> " + out));
            return this;
        }

        private int[][] parse(String spec) {
            if (spec.trim().isEmpty()) return new int[][] { new int[0], new int[0] };
            final String[] parts = spec.split(",");
            final int[] items = new int[parts.length];
            final int[] amounts = new int[parts.length];
            for (int k = 0; k < parts.length; k++) {
                final String[] p = parts[k].trim().split(" ");
                amounts[k] = Integer.parseInt(p[0]);
                items[k] = id(p[1]);
            }
            return new int[][] { items, amounts };
        }

        private static long[] toLong(int[] a) {
            final long[] r = new long[a.length];
            for (int k = 0; k < a.length; k++) r[k] = a[k];
            return r;
        }

        Problem build(String target, long amount) {
            final int t = id(target);
            final int n = names.size();
            final long[] available = new long[n];
            final double[] weights = new double[n];
            final double[] missingCost = new double[n];
            final boolean[] craftable = new boolean[n];
            for (final Recipe r : recipes) for (final int o : r.outItems) craftable[o] = true;
            for (int i = 0; i < n; i++) {
                available[i] = i == t ? 0 : stock.getOrDefault(i, 0L);
                weights[i] = weight.getOrDefault(i, 1.0);
                missingCost[i] = Math.max(weights[i], 1e-3) * 1e4;
            }
            missingCost[t] = 1e7;
            return new Problem(n, t, amount, available, weights, craftable, missingCost, recipes,
                    names.toArray(new String[0]));
        }
    }

    private static Planner.Settings settings() {
        final Planner.Settings s = new Planner.Settings();
        s.timeBudgetMillis = 10000;
        return s;
    }

    private static void assertBalanced(Problem problem, Plan plan) {
        final long[] net = new long[problem.itemCount];
        for (int p = 0; p < plan.runs.length; p++) {
            final Recipe r = problem.recipes.get(p);
            for (int q = 0; q < r.outItems.length; q++) net[r.outItems[q]] += plan.runs[p] * r.outAmounts[q];
            for (int q = 0; q < r.inItems.length; q++) net[r.inItems[q]] -= plan.runs[p] * r.inAmounts[q];
        }
        assertTrue("target produced", net[problem.target] >= problem.targetAmount);
        for (int i = 0; i < problem.itemCount; i++) {
            if (i == problem.target) continue;
            assertTrue("balance of " + problem.name(i), net[i] + plan.extract[i] + plan.missing[i] >= 0);
            assertTrue("storage limit of " + problem.name(i), plan.extract[i] <= problem.available[i]);
        }
    }

    @Test
    public void simpleChain() {
        final Builder b = new Builder().stock("log", 10).recipe("1 log", "4 plank").recipe("2 plank", "4 stick");
        final Problem problem = b.build("stick", 8);
        final Plan plan = Planner.plan(problem, settings());
        assertBalanced(problem, plan);
        assertFalse(plan.hasMissing());
        assertEquals(1, plan.extract[b.id("log")]);
        assertTrue(plan.executionVerified);
    }

    @Test
    public void picksCheaperAlternativeRoute() {
        final Builder b = new Builder().stock("ore", 1000).stock("coal", 1000)
                .recipe("1 ore", "1 dust")
                .recipe("1 dust", "1 ingot")
                .recipe("1 ore, 1 coal", "3 ingot");
        final Problem problem = b.build("ingot", 30);
        final Plan plan = Planner.plan(problem, settings());
        assertBalanced(problem, plan);
        assertEquals(10, plan.extract[b.id("ore")]);
        assertEquals(10, plan.extract[b.id("coal")]);
    }

    @Test
    public void reusesByproductsOfOtherBranches() {
        // a -> target + junk, junk -> target : twice the yield of a plain chain.
        final Builder b = new Builder().stock("a", 100)
                .recipe("1 a", "1 t, 1 junk")
                .recipe("2 junk", "1 t");
        final Problem problem = b.build("t", 30);
        final Plan plan = Planner.plan(problem, settings());
        assertBalanced(problem, plan);
        assertEquals(20, plan.extract[b.id("a")]);
    }

    @Test
    public void usesStoredIntermediatesAndStorageLimits() {
        final Builder b = new Builder().stock("ore", 5).stock("dust", 3)
                .recipe("1 ore", "1 dust")
                .recipe("1 dust", "1 ingot");
        final Problem problem = b.build("ingot", 6);
        final Plan plan = Planner.plan(problem, settings());
        assertBalanced(problem, plan);
        assertEquals(3, plan.extract[b.id("dust")]);
        assertEquals(3, plan.extract[b.id("ore")]);
        assertFalse(plan.hasMissing());
    }

    @Test
    public void reportsMissingRawMaterial() {
        final Builder b = new Builder().stock("ore", 2).recipe("1 ore", "1 ingot");
        final Problem problem = b.build("ingot", 5);
        final Plan plan = Planner.plan(problem, settings());
        assertBalanced(problem, plan);
        assertEquals(2, plan.extract[b.id("ore")]);
        assertEquals(3, plan.missing[b.id("ore")]);
    }

    @Test
    public void catalystIsLentFromStorage() {
        final Builder b = new Builder().stock("press", 1).stock("ingot", 100)
                .recipe("1 press, 1 ingot", "1 press, 1 plate");
        final Problem problem = b.build("plate", 20);
        final Plan plan = Planner.plan(problem, settings());
        assertBalanced(problem, plan);
        assertEquals(1, plan.extract[b.id("press")]);
        assertEquals(20, plan.extract[b.id("ingot")]);
        assertTrue(plan.executionVerified);
    }

    @Test
    public void loopSeedIsCraftedWhenNotStored() {
        // seed -> 2 half; half -> seed + t. The loop multiplies, but needs one seed to start; seed is
        // craftable from stone outside the loop.
        final Builder b = new Builder().stock("stone", 100)
                .recipe("1 seed", "2 half")
                .recipe("1 half", "1 seed, 1 t")
                .recipe("10 stone", "1 seed");
        final Problem problem = b.build("t", 10);
        final Plan plan = Planner.plan(problem, settings());
        assertBalanced(problem, plan);
        assertTrue(plan.executionVerified);
        assertFalse(plan.hasMissing());
        assertEquals(10, plan.extract[b.id("stone")]);
    }

    @Test
    public void loopSeedReportedMissingWhenImpossible() {
        final Builder b = new Builder()
                .recipe("1 seed", "2 half")
                .recipe("1 half", "1 seed, 1 t");
        final Problem problem = b.build("t", 4);
        final Plan plan = Planner.plan(problem, settings());
        assertTrue(plan.missing[b.id("seed")] > 0);
    }

    /**
     * Reported in game: A makes 8 resources plus one B, B makes 2 resources. Burning A for small requests piles up
     * B forever (8880 B in storage). B is worth 2 resources, A is worth 10, so small requests must use B.
     */
    private static Builder byproductFeedsCheaperRoute() {
        return new Builder().stock("A", 3766).stock("B", 8880)
                .recipe("1 A", "8 res, 1 B")
                .recipe("1 B", "2 res");
    }

    @Test
    public void smallRequestUsesTheStockpiledByproduct() {
        final Builder b = byproductFeedsCheaperRoute();
        final Problem problem = b.build("res", 2);
        final Plan plan = Planner.plan(problem, settings());
        assertBalanced(problem, plan);
        assertEquals(0, plan.extract[b.id("A")]);
        assertEquals(1, plan.extract[b.id("B")]);
    }

    @Test
    public void largeRequestEatsTheStockpileFirst() {
        final Builder b = byproductFeedsCheaperRoute();
        // 8880 B give 17760 resources; the rest comes from A, whose B is processed right away.
        final Problem problem = b.build("res", 20000);
        final Plan plan = Planner.plan(problem, settings());
        assertBalanced(problem, plan);
        assertEquals(8880, plan.extract[b.id("B")]);
        assertEquals(224, plan.extract[b.id("A")]);
        assertFalse(plan.hasMissing());
    }

    @Test
    public void stockpileIsUsedBeforeMakingMoreOfIt() {
        // Stock first: while the 8880 B can cover the request (up to 17760 resources), A must not run at all -
        // also when A itself is craftable (reported in game: 4 used B, 6 and 4440 switched to A).
        for (final boolean aCraftable : new boolean[] { false, true }) {
            final Builder b = byproductFeedsCheaperRoute();
            if (aCraftable) b.recipe("9 ore", "1 A");
            final List<Long> amounts = new ArrayList<>();
            for (long n = 1; n <= 60; n++) amounts.add(n);
            amounts.add(4440L);
            amounts.add(17760L);
            for (final long amount : amounts) {
                final Problem problem = b.build("res", amount);
                final Plan plan = Planner.plan(problem, settings());
                assertBalanced(problem, plan);
                assertEquals("A craftable=" + aCraftable + ", " + amount + " res: A runs", 0, plan.runs[0]);
                assertEquals("A craftable=" + aCraftable + ", " + amount + " res: B used", (amount + 1) / 2,
                        plan.extract[b.id("B")]);
            }
        }
    }

    /**
     * The in-game screenshot: both routes also give copper (in different amounts). Order 10 used A + its dust
     * while 8880 dust lay in storage. Whatever the copper amounts, the stored dust must be used.
     */
    @Test
    public void stockFirstHoldsWithByproductsOnBothRoutes() {
        for (final int copperA : new int[] { 0, 1, 3, 5, 8 }) {
            for (final int copperB : new int[] { 0, 1, 2, 3 }) {
                for (final boolean aCraftable : new boolean[] { false, true }) {
                    final Builder b = new Builder().stock("ingotA", 3766).stock("dust", 8880)
                            .recipe("1 ingotA", "8 res, 1 dust" + (copperA > 0 ? ", " + copperA + " copper" : ""))
                            .recipe("1 dust", "2 res" + (copperB > 0 ? ", " + copperB + " copper" : ""));
                    if (aCraftable) b.recipe("9 ore", "1 ingotA");
                    final List<Long> amounts = new ArrayList<>();
                    for (long n = 1; n <= 100; n++) amounts.add(n);
                    amounts.add(4440L);
                    for (final long amount : amounts) {
                        final Problem problem = b.build("res", amount);
                        final Plan plan = Planner.plan(problem, settings());
                        assertBalanced(problem, plan);
                        assertEquals("copper " + copperA + "/" + copperB + ", A craftable=" + aCraftable + ", " + amount
                                + " res: ingotA used", 0, plan.extract[b.id("ingotA")]);
                    }
                }
            }
        }
    }

    @Test
    public void stockFirstNeverCostsMoreThanTheTolerance() {
        // A little stored "primary" must not push a big request onto a much worse route just to avoid making
        // more primary: ore -> primary -> 4 res is far better than ore -> 1 res.
        final Builder b = new Builder().stock("ore", 1000).stock("primary", 5)
                .recipe("1 ore", "1 primary")
                .recipe("1 primary", "4 res")
                .recipe("1 ore", "1 res");
        final Problem problem = b.build("res", 400);
        final Plan plan = Planner.plan(problem, settings());
        assertBalanced(problem, plan);
        assertEquals(5, plan.extract[b.id("primary")]);
        assertEquals(95, plan.extract[b.id("ore")]);
    }

    @Test
    public void smallRequestAvoidsJunkProducingRoute() {
        // Same yields as above but the byproduct is junk nobody uses: still no reason to make 6 extra + junk.
        final Builder b = new Builder().stock("A", 100).stock("B", 100)
                .recipe("1 A", "8 res, 3 junk")
                .recipe("1 B", "2 res");
        final Problem problem = b.build("res", 2);
        final Plan plan = Planner.plan(problem, settings());
        assertEquals(0, plan.extract[b.id("A")]);
        assertEquals(1, plan.extract[b.id("B")]);
    }

    @Test
    public void onlyTheBigRouteAvailableStillWorks() {
        final Builder b = new Builder().stock("A", 10).recipe("1 A", "8 res, 1 B").recipe("1 B", "2 res");
        final Problem problem = b.build("res", 2);
        final Plan plan = Planner.plan(problem, settings());
        assertBalanced(problem, plan);
        assertEquals(1, plan.extract[b.id("A")]);
        assertFalse(plan.hasMissing());
    }

    /** Model of the HBM bedrock ore processing for one ore type (see CentrifugeRecipes/CrystallizerRecipes). */
    private static Builder bedrock() {
        return new Builder().stock("base", 10000).stock("acid", 10_000_000).stock("solvent", 10_000_000)
                .stock("water", 10_000_000).stock("slop", 10_000_000)
                .fluid("acid").fluid("solvent").fluid("water").fluid("slop").fluid("vitriol")
                // centrifuge
                .recipe("1 base", "1 primary, 1 gravel")
                .recipe("1 base_washed", "2 primary, 1 gravel")
                .recipe("1 primary_sulf", "2 primary_nosulf, 2 sulf_byp")
                .recipe("1 primary_solv", "2 primary_nosolv, 2 sulf_byp, 2 solv_byp")
                .recipe("1 primary", "1 iron, 1 copper")
                .recipe("1 primary_nosulf", "1 iron, 1 copper, 1 crumbs")
                .recipe("1 primary_nosolv", "1 iron, 1 copper, 1 crumbs")
                .recipe("1 primary_first", "2 iron, 1 copper, 1 crumbs")
                .recipe("1 sulf_washed", "1 sulfur, 1 bp2, 1 bp3, 1 crumbs")
                .recipe("1 solv_washed", "1 bs1, 1 bs2, 1 bs3, 1 crumbs")
                // crystallizer
                .recipe("1 base, 250 water", "1 base_washed")
                .recipe("1 primary, 250 acid", "1 primary_sulf")
                .recipe("1 primary, 250 solvent", "1 primary_solv")
                .recipe("1 primary_nosulf, 250 solvent", "1 primary_solv")
                .recipe("1 primary_nosulf, 1000 acid", "1 primary_first")
                .recipe("1 sulf_byp, 250 water", "1 sulf_washed")
                .recipe("1 solv_byp, 250 water", "1 solv_washed")
                .recipe("64 crumbs, 1000 slop", "1 base")
                // pyro oven
                .recipe("1 base", "50 vitriol, 1 base_roasted")
                .recipe("1 base_roasted, 250 water", "1 base_washed");
    }

    @Test
    public void bedrockOreUsesTheWholeProcessingGraph() {
        final Builder b = bedrock();
        final Problem problem = b.build("iron", 640);
        final Plan plan = Planner.plan(problem, settings());
        assertBalanced(problem, plan);
        assertFalse(plan.hasMissing());
        assertTrue(plan.executionVerified);
        final long base = plan.extract[b.id("base")];
        System.out.println(plan.describe(problem));
        // Plain AE2 chain (base -> primary -> iron) needs 640 base ore. The graph plan washes the ore (2x primary)
        // and runs the acid route (2x again), and recycles crumbs.
        assertTrue("base ore used: " + base, base <= 640 / 4);
    }

    @Test
    public void bedrockSmallRequestStaysReasonable() {
        final Builder b = bedrock();
        final Problem problem = b.build("iron", 1);
        final Plan plan = Planner.plan(problem, settings());
        assertBalanced(problem, plan);
        assertFalse(plan.hasMissing());
        // Must not drag 64 crumbs worth of extra processing into a one-item request.
        assertTrue(plan.extract[b.id("base")] <= 1);
        assertEquals(0, plan.extract[b.id("slop")]);
    }

    @Test
    public void largeRandomGraphFinishesInTime() {
        final Builder b = new Builder();
        final java.util.Random rnd = new java.util.Random(42);
        for (int i = 0; i < 60; i++) b.stock("raw" + i, 100000);
        for (int k = 0; k < 400; k++) {
            final int out = 60 + rnd.nextInt(150);
            final int in1 = rnd.nextInt(out);
            final int in2 = rnd.nextInt(out);
            final String in1n = in1 < 60 ? "raw" + in1 : "it" + in1;
            final String in2n = in2 < 60 ? "raw" + in2 : "it" + in2;
            b.recipe((1 + rnd.nextInt(3)) + " " + in1n + ", " + (1 + rnd.nextInt(3)) + " " + in2n,
                    (1 + rnd.nextInt(4)) + " it" + out + ", 1 it" + (60 + rnd.nextInt(150)));
        }
        final Problem problem = b.build("it" + 205, 1000);
        final long start = System.currentTimeMillis();
        final Plan plan = Planner.plan(problem, settings());
        final long took = System.currentTimeMillis() - start;
        System.out.println("random graph: " + took + " ms, " + plan.notes);
        assertBalanced(problem, plan);
        assertTrue("took " + took, took < 20000);
    }
}
