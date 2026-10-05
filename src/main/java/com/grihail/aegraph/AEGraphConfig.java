package com.grihail.aegraph;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import net.minecraftforge.common.config.Configuration;

public final class AEGraphConfig {

    private static final String GENERAL = Configuration.CATEGORY_GENERAL;
    private static final String COSTS = "costs";
    private static final String SOLVER = "solver";

    public static boolean enabled = true;
    public static int coprocessorAcceleration = 64;
    public static boolean requireCoprocessor = true;
    public static boolean fallbackToAE2WhenMissing = true;
    public static boolean logPlans = false;
    public static boolean returnUnneededItems = true;
    public static int stallSeconds = 60;
    public static boolean substitutes = true;

    public static double defaultItemCost = 1.0;
    public static double fluidCostPerMb = 0.001;
    public static double storedIntermediateFactor = 0.9;
    public static double patternRunCost = 0.001;
    public static double surplusPenalty = 0.01;
    public static double stockFirstTolerance = 0.05;
    public static double missingPenalty = 10000;
    public static double remoteCraftCost = 100;
    /** "modid:name" or "modid:name@meta" -> cost per item (fluid drops: cost per mB). */
    public static Map<String, Double> costOverrides = new HashMap<>();

    public static int maxPatterns = 3000;
    public static int timeBudgetMillis = 4000;
    public static double bigRunThreshold = 24;

    private static Configuration config;

    private AEGraphConfig() {}

    public static void load(File file) {
        config = new Configuration(file);
        sync();
    }

    public static void reload() {
        if (config != null) {
            config.load();
            sync();
        }
    }

    private static void sync() {
        enabled = config.getBoolean("enabled", GENERAL, true,
                "Replace the AE2 crafting calculator with the graph planner.");
        requireCoprocessor = config.getBoolean("requireCoprocessor", GENERAL, true,
                "true: only crafting CPUs that contain a Graph Co-Processor block run graph plans, other CPUs keep "
                        + "AE2's own logic. false: every CPU runs graph plans, no co-processor needed.");
        coprocessorAcceleration = config.getInt("coprocessorAcceleration", GENERAL, 64, 0, 4096,
                "How many extra parallel pattern pushes a Graph Co-Processor gives its CPU, like AE2's crafting "
                        + "accelerators (a 1x accelerator = 1). AE2 lets a CPU push (accelerators + 1) pattern runs "
                        + "per 3 ticks; with 64 the CPU can fill a fast machine with dozens of runs at once instead "
                        + "of one every few ticks. Takes effect when the CPU multiblock is (re)formed.");
        fallbackToAE2WhenMissing = config.getBoolean("fallbackToAE2WhenMissing", GENERAL, true,
                "If the graph plan lacks items (e.g. it needs ore-dictionary substitutes), also try the stock AE2 "
                        + "calculator and use its plan when that one is complete.");
        logPlans = config.getBoolean("logPlans", GENERAL, false, "Write every computed plan to the log.");
        returnUnneededItems = config.getBoolean("returnUnneededItems", GENERAL, true,
                "Crafting CPUs (all of them) send items that no remaining step of the job needs - byproducts, side "
                        + "products, leftovers - straight into ME storage. Without it AE2 keeps them locked in the CPU "
                        + "until the whole job is done.");
        stallSeconds = config.getInt("stallSeconds", GENERAL, 60, 0, 3600,
                "When a crafting job makes no progress for this long (a machine gave fewer byproducts than its "
                        + "pattern says, a loop waits for itself...), the CPU takes what the remaining steps lack from ME "
                        + "storage. If storage has nothing and the requested items are already delivered, the job is "
                        + "finished and the CPU freed. Also frees CPUs that wait for items that never came after the job "
                        + "is done. 0 disables.");
        substitutes = config.getBoolean("substitutes", SOLVER, true,
                "Patterns with 'substitute' enabled may use equivalent items (ore dictionary, other damage values), "
                        + "like AE2 does. Off: the graph uses only the exact items written in the pattern.");

        defaultItemCost = config.get(COSTS, "defaultItemCost", 1.0,
                "Cost of taking one item from ME storage. The planner minimizes the total cost.").getDouble();
        fluidCostPerMb = config.get(COSTS, "fluidCostPerMb", 0.001,
                "Cost of one mB of a fluid (ae2fc fluid drops). 0.001 = one bucket costs as much as one item.")
                .getDouble();
        storedIntermediateFactor = config.get(COSTS, "storedIntermediateFactor", 0.9,
                "Multiplier for items that some pattern can craft. Below 1 makes the planner use stored "
                        + "intermediates before processing new raw materials.").getDouble();
        patternRunCost = config.get(COSTS, "patternRunCost", 0.001,
                "Cost of one pattern run. Keeps plans from doing needless processing steps.").getDouble();
        surplusPenalty = config.get(COSTS, "surplusPenalty", 0.01,
                "Penalty per unit of surplus (extra resources and byproducts the request did not need), relative to "
                        + "the item's base cost. Breaks ties towards plans without junk; keep it small.").getDouble();
        stockFirstTolerance = config.get(COSTS, "stockFirstTolerance", 0.05,
                "Stock first: among plans at most this much (0.05 = 5%) worse than the best one, take the one that "
                        + "makes the least of ingredients already lying in ME. Equally good routes always use the "
                        + "stored items first. 0 disables the rule.").getDouble();
        missingPenalty = config.get(COSTS, "missingPenalty", 10000.0,
                "Penalty per item that is neither stored nor craftable, relative to its normal cost.").getDouble();
        remoteCraftCost = config.get(COSTS, "remoteCraftCost", 100.0,
                "Graph planner: cost of having another network craft an item through a Remote Crafting Bus, relative "
                        + "to the item's base cost. High = this network's own patterns and storage come first, the "
                        + "other network is used for what this one can't make. Exclusive buses ignore it.")
                .getDouble();
        final String[] overrides = config.getStringList("itemCostOverrides", COSTS, new String[0],
                "Per-item costs, format modid:name=cost or modid:name@meta=cost. For fluid drops the cost is per mB. "
                        + "Example: hbm:item.bedrock_ore_new@0=5");
        final Map<String, Double> parsed = new HashMap<>();
        for (final String line : overrides) {
            final int eq = line.lastIndexOf('=');
            if (eq <= 0) continue;
            try {
                parsed.put(line.substring(0, eq).trim(), Double.parseDouble(line.substring(eq + 1).trim()));
            } catch (NumberFormatException e) {
                AEGraphMod.LOG.warn("Bad itemCostOverrides entry: {}", line);
            }
        }
        costOverrides = parsed;

        maxPatterns = config.getInt("maxPatterns", SOLVER, 3000, 10, 100000,
                "Maximum number of patterns pulled into one plan.");
        timeBudgetMillis = config.getInt("timeBudgetMillis", SOLVER, 4000, 100, 120000,
                "Time for optimizing one request. Runs in a background thread.");
        bigRunThreshold = config.get(SOLVER, "bigRunThreshold", 24.0,
                "Run counts above this are rounded up directly instead of being optimized one by one.").getDouble();

        // Options of earlier versions.
        boolean stale = config.getCategory(GENERAL).remove("flushStuckInterfaces") != null;
        stale |= config.getCategory(COSTS).remove("stockFirstPenalty") != null;

        if (stale || config.hasChanged()) config.save();
    }
}
