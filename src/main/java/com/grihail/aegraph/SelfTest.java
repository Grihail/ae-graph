package com.grihail.aegraph;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Future;

import net.minecraft.init.Blocks;
import net.minecraft.init.Items;
import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.server.MinecraftServer;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.world.World;
import net.minecraftforge.common.DimensionManager;
import net.minecraftforge.common.util.ForgeDirection;

import com.grihail.aegraph.ae.GraphCpus;
import com.grihail.aegraph.ae.GraphCraftingJob;
import com.grihail.aegraph.block.TileGraphCoprocessor;
import com.grihail.aegraph.item.FastFeed;
import com.grihail.aegraph.remote.PartRemoteBus;
import com.grihail.aegraph.remote.RemotePattern;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.definitions.ITileDefinition;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridHost;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.CraftingItemList;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.MachineSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartHost;
import appeng.api.storage.data.IItemList;
import appeng.api.util.AEColor;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.tile.misc.TileInterface;
import appeng.tile.storage.TileDrive;
import appeng.util.item.AEItemStack;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

/**
 * In-world integration test, only active with {@code -Daegraph.selftest=true}. Builds a small ME network
 * (creative energy, drive, interface with processing patterns, 1k crafting CPU) next to a chest that acts as a
 * machine: the test itself turns what the interface puts into the chest into the pattern outputs and inserts them
 * into the network, like a real processing machine would. Requests crafts through the normal AE2 API and checks
 * the results. Prints "AEGRAPH SELFTEST: PASS/FAIL".
 */
public final class SelfTest {

    public static boolean isEnabled() {
        return Boolean.getBoolean("aegraph.selftest");
    }

    /** Processing recipe, both as AE2 pattern and as the fake machine's behaviour. */
    private static final class Process {

        final ItemStack[] in;
        final ItemStack[] out;
        /** What the machine really gives (may differ from the pattern). */
        ItemStack[] real;
        /** Slow machine: at most one run every this many ticks (0 = instant). */
        int every;
        /** The pattern substitutes: the machine accepts any damage value of the input item. */
        boolean substitute;

        Process(ItemStack[] in, ItemStack[] out) {
            this.in = in;
            this.out = out;
            this.real = out;
        }
    }

    private final List<Process> processes = new ArrayList<>();

    private int tick = 0;
    private int stage = 0;
    private int stageStart = 0;
    private World world;
    private int x0, y0, z0;
    private TileInterface iface;
    private MachineSource source;
    private Future<ICraftingJob> pending;
    private ICraftingJob job;
    private boolean failed = false;
    private long before;
    private ICraftingCPU forcedCpu;
    private final StringBuilder machineLog = new StringBuilder();

    public SelfTest() {
        // gravel -> flint + sand (byproduct); 2 sand -> flint
        processes.add(new Process(new ItemStack[] { new ItemStack(Blocks.gravel) },
                new ItemStack[] { new ItemStack(Items.flint), new ItemStack(Blocks.sand) }));
        processes.add(new Process(new ItemStack[] { new ItemStack(Blocks.sand, 2) },
                new ItemStack[] { new ItemStack(Items.flint) }));
        // bucket is a catalyst: bucket + cobblestone -> bucket + stone
        processes.add(new Process(new ItemStack[] { new ItemStack(Items.bucket), new ItemStack(Blocks.cobblestone) },
                new ItemStack[] { new ItemStack(Items.bucket), new ItemStack(Blocks.stone) }));
        // multiplying loop that needs a seed: slime -> 2 cream; cream -> slime + pearl; seed slime from 4 dirt
        processes.add(new Process(new ItemStack[] { new ItemStack(Items.slime_ball) },
                new ItemStack[] { new ItemStack(Items.magma_cream, 2) }));
        processes.add(new Process(new ItemStack[] { new ItemStack(Items.magma_cream) },
                new ItemStack[] { new ItemStack(Items.slime_ball), new ItemStack(Items.ender_pearl) }));
        processes.add(new Process(new ItemStack[] { new ItemStack(Blocks.dirt, 4) },
                new ItemStack[] { new ItemStack(Items.slime_ball) }));
        // slow machine with a byproduct nothing needs: netherrack -> nether brick + glowstone dust
        final Process slow = new Process(new ItemStack[] { new ItemStack(Blocks.netherrack) },
                new ItemStack[] { new ItemStack(Items.netherbrick), new ItemStack(Items.glowstone_dust) });
        slow.every = 10;
        processes.add(slow);
        // the pattern promises 2 sticks per log, the machine gives 1; 2 sticks -> torch
        final Process shortchanging = new Process(new ItemStack[] { new ItemStack(Blocks.log) },
                new ItemStack[] { new ItemStack(Items.coal, 1, 1), new ItemStack(Items.stick, 2) });
        shortchanging.real = new ItemStack[] { new ItemStack(Items.coal, 1, 1), new ItemStack(Items.stick, 1) };
        processes.add(shortchanging);
        processes.add(new Process(new ItemStack[] { new ItemStack(Items.stick, 2) },
                new ItemStack[] { new ItemStack(Blocks.torch) }));
        // substituting pattern written with oak planks, only birch planks are stored
        final Process button = new Process(new ItemStack[] { new ItemStack(Blocks.planks, 1, 0) },
                new ItemStack[] { new ItemStack(Blocks.wooden_button) });
        button.substitute = true;
        processes.add(button);
        // network A's own way to make bricks (the remote bus test compares it with network B's)
        processes.add(new Process(new ItemStack[] { new ItemStack(Blocks.wool) },
                new ItemStack[] { new ItemStack(Items.brick) }));

        // Network B, interface 1 (the one the Remote Crafting Bus faces): clay -> brick, 4 bricks -> brick block
        remoteProcesses.add(new Process(new ItemStack[] { new ItemStack(Items.clay_ball) },
                new ItemStack[] { new ItemStack(Items.brick) }));
        remoteProcesses.add(new Process(new ItemStack[] { new ItemStack(Items.brick, 4) },
                new ItemStack[] { new ItemStack(Blocks.brick_block) }));
        // ... and a pattern with two outputs: sand -> glass + 2 bottles
        remoteProcesses.add(new Process(new ItemStack[] { new ItemStack(Blocks.sand) },
                new ItemStack[] { new ItemStack(Blocks.glass), new ItemStack(Items.glass_bottle, 2) }));
        // Network B, interface 2 (not faced by the bus): clay block -> 4 clay
        remoteProcesses2.add(new Process(new ItemStack[] { new ItemStack(Blocks.clay) },
                new ItemStack[] { new ItemStack(Items.clay_ball, 4) }));
    }

    private final List<Process> remoteProcesses = new ArrayList<>();
    private final List<Process> remoteProcesses2 = new ArrayList<>();
    private boolean remoteBuilt;
    private boolean remoteJobSeen;

    @SubscribeEvent
    public void onTick(TickEvent.ServerTickEvent event) {
        if (event.phase != TickEvent.Phase.END || stage < 0) return;
        tick++;
        try {
            if (stage > 2) runMachine((IInventory) world.getTileEntity(x0 + 4, y0, z0), processes, false);
            if (remoteBuilt) {
                runMachine((IInventory) world.getTileEntity(x0, y0, z0 + 2), remoteProcesses, true);
                runMachine((IInventory) world.getTileEntity(x0 + 3, y0, z0 + 5), remoteProcesses2, true);
            }
            step();
        } catch (Throwable t) {
            AEGraphMod.LOG.error("AEGRAPH SELFTEST: exception", t);
            failed = true;
            finish();
        }
    }

    private void log(String text) {
        AEGraphMod.LOG.info("AEGRAPH SELFTEST: " + text);
    }

    private void next() {
        stage++;
        stageStart = tick;
    }

    private void goTo(int stage) {
        this.stage = stage;
        stageStart = tick;
    }

    private boolean waited(int ticks) {
        return tick - stageStart >= ticks;
    }

    private void step() {
        switch (stage) {
            case 0:
                if (tick < 40) return;
                world = DimensionManager.getWorld(0);
                x0 = world.getSpawnPoint().posX;
                z0 = world.getSpawnPoint().posZ;
                y0 = 200;
                if (Boolean.getBoolean("aegraph.selftest.resume")) {
                    // Phase 2: the networks of phase 1 are in the saved world.
                    iface = (TileInterface) world.getTileEntity(x0 + 3, y0, z0);
                    source = new MachineSource((IActionHost) iface);
                    sourceB = new MachineSource((IActionHost) world.getTileEntity(x0 + 1, y0, z0 + 2));
                    remoteProcesses.get(0).every = 20;
                    remoteBuilt = true;
                    log("resuming in the saved world");
                    stage = 100;
                    stageStart = tick;
                    return;
                }
                // graph CPU: co-processor + 1k storage; a plain 1k CPU sits on top of the drive
                world.setBlock(x0 - 1, y0, z0, AEGraphMod.coprocessor, 0, 3);
                place(0, AEApi.instance().definitions().blocks().craftingStorage1k());
                world.setBlock(x0 + 2, y0 + 1, z0,
                        AEApi.instance().definitions().blocks().craftingStorage1k().maybeBlock().get(), 0, 3);
                place(1, AEApi.instance().definitions().blocks().energyCellCreative());
                place(2, AEApi.instance().definitions().blocks().drive());
                place(3, AEApi.instance().definitions().blocks().iface());
                world.setBlock(x0 + 4, y0, z0, Blocks.chest, 0, 3);
                log("network placed at " + x0 + "," + y0 + "," + z0);
                next();
                return;
            case 1:
                if (!waited(20)) return;
                final TileDrive drive = (TileDrive) world.getTileEntity(x0 + 2, y0, z0);
                drive.getInternalInventory().setInventorySlotContents(0,
                        AEApi.instance().definitions().items().cell64k().maybeStack(1).get());
                iface = (TileInterface) world.getTileEntity(x0 + 3, y0, z0);
                source = new MachineSource((IActionHost) iface);
                // more than 9 patterns: one pattern capacity card
                iface.getInventoryByName("upgrades").setInventorySlotContents(0,
                        AEApi.instance().definitions().materials().cardPatternCapacity().maybeStack(1).get());
                final IInventory patterns = iface.getInventoryByName("patterns");
                for (int k = 0; k < processes.size(); k++) {
                    patterns.setInventorySlotContents(k, processingPattern(processes.get(k)));
                }
                next();
                return;
            case 2: {
                if (waited(400)) throw new IllegalStateException("network/CPU/patterns not ready");
                final IGrid grid = grid();
                if (grid == null) return;
                final ICraftingGrid crafting = grid.getCache(ICraftingGrid.class);
                if (crafting.getCpus().size() < 2 || crafting.getCraftingPatterns().size() < 3) return;
                int graphCpus = 0;
                for (final ICraftingCPU cpu : crafting.getCpus()) {
                    if (GraphCpus.isGraphCpu(cpu)) graphCpus++;
                }
                check(graphCpus == 1, "exactly one of " + crafting.getCpus().size() + " CPUs has a co-processor");
                inject(new ItemStack(Blocks.gravel, 40));
                inject(new ItemStack(Items.bucket, 1));
                inject(new ItemStack(Blocks.cobblestone, 10));
                inject(new ItemStack(Blocks.dirt, 20));
                inject(new ItemStack(Blocks.netherrack, 8));
                inject(new ItemStack(Blocks.log, 2));
                inject(new ItemStack(Blocks.planks, 3, 2));
                log("network ready: " + crafting.getCpus().size() + " cpu, " + crafting.getCraftingPatterns().size()
                        + " craftable items");
                next();
                return;
            }
            // 1. Rig check with AE2's own calculator: 9 flint.
            case 3:
                if (!waited(10)) return;
                AEGraphConfig.enabled = false;
                before = stored(new ItemStack(Blocks.gravel));
                requestAny(new ItemStack(Items.flint), 9);
                return;
            case 4:
                awaitPlan("9 flint, stock AE2", false);
                return;
            case 5:
                if (awaitCrafted(new ItemStack(Items.flint), 9, "9 flint, stock AE2", false)) {
                    AEGraphConfig.enabled = true;
                    log("stock AE2 used " + (before - stored(new ItemStack(Blocks.gravel))) + " gravel for 9 flint");
                    final ICraftingGrid crafting = grid().getCache(ICraftingGrid.class);
                    for (final ICraftingCPU cpu : crafting.getCpus()) {
                        if (cpu.isBusy()) {
                            log("stock AE2 job did not finish, cancelling it");
                            ((CraftingCPUCluster) cpu).cancel();
                        }
                    }
                    next();
                }
                return;
            case 6:
                // let the CPU return its leftovers, then clear the products of the comparison run
                if (!waited(40)) return;
                extract(new ItemStack(Items.flint), stored(new ItemStack(Items.flint)));
                extract(new ItemStack(Blocks.sand), stored(new ItemStack(Blocks.sand)));
                next();
                return;
            // 2. Graph planner: 9 flint must reuse the sand byproduct -> 6 gravel.
            case 7:
                if (stageStart == tick - 1) {
                    final IInventory upgrades = iface.getInventoryByName("upgrades");
                    final ItemStack card = new ItemStack(AEGraphMod.fastFeedCard);
                    int slot = -1;
                    for (int s = 0; s < upgrades.getSizeInventory() && slot < 0; s++) {
                        if (upgrades.getStackInSlot(s) == null && upgrades.isItemValidForSlot(s, card)) slot = s;
                    }
                    check(slot >= 0, "ME Interface accepts the Fast Feed Card in its upgrade slots");
                    if (slot >= 0) upgrades.setInventorySlotContents(slot, card);
                    check(FastFeed.isInstalled(iface.getInterfaceDuality()), "card installed");
                }
                before = stored(new ItemStack(Blocks.gravel));
                request(new ItemStack(Items.flint), 9);
                return;
            case 8:
                awaitPlan("9 flint, graph", false);
                return;
            case 9:
                if (awaitCrafted(new ItemStack(Items.flint), 9, "9 flint, graph", true)) next();
                return;
            case 10: {
                if (!waited(40)) return;
                final long used = before - stored(new ItemStack(Blocks.gravel));
                check(used == 6, "graph used " + used + " gravel for 9 flint (expected 6)");
                check(stored(new ItemStack(Items.flint)) == 9, "exactly 9 flint delivered");
                check(stored(new ItemStack(Blocks.sand)) == 0, "no sand left over");
                final TileGraphCoprocessor coprocessor = (TileGraphCoprocessor) world.getTileEntity(x0 - 1, y0, z0);
                final NBTTagCompound graph = coprocessor.getLastPlan();
                check(graph != null && graph.getTagList("recipes", 10).tagCount() == 2,
                        "graph CPU ran the job and its co-processor stored the plan graph");
                if (graph != null) {
                    log("stored graph: " + graph.getTagList("items", 10).tagCount() + " items, "
                            + graph.getTagList("recipes", 10).tagCount() + " patterns, stock AE2 would take "
                            + graph.getLong("stockItems") + " items, graph takes " + graph.getLong("takeItems"));
                }
                next();
                return;
            }
            // 3. Catalyst: 4 stone with a single bucket that must be lent and returned.
            case 11:
                request(new ItemStack(Blocks.stone), 4);
                return;
            case 12:
                awaitPlan("4 stone (bucket catalyst)", false);
                return;
            case 13:
                if (awaitCrafted(new ItemStack(Blocks.stone), 4, "4 stone", true)) next();
                return;
            case 14:
                if (!waited(40)) return;
                check(stored(new ItemStack(Items.bucket)) == 1, "bucket returned to storage");
                check(stored(new ItemStack(Blocks.cobblestone)) == 6, "4 cobblestone used");
                next();
                return;
            // 4. Loop that needs a seed crafted outside of it.
            case 15:
                before = stored(new ItemStack(Blocks.dirt));
                request(new ItemStack(Items.ender_pearl), 6);
                return;
            case 16:
                awaitPlan("6 pearls (seeded loop)", false);
                return;
            case 17:
                if (awaitCrafted(new ItemStack(Items.ender_pearl), 6, "6 pearls", true)) next();
                return;
            case 18:
                if (!waited(40)) return;
                check(before - stored(new ItemStack(Blocks.dirt)) == 4, "one seed made from 4 dirt");
                check(stored(new ItemStack(Items.ender_pearl)) == 6, "6 pearls delivered");
                log("loop surplus returned: " + stored(new ItemStack(Items.slime_ball)) + " slime balls");
                next();
                return;
            // 5. Graph job sent to the CPU without co-processor: it must run AE2's own plan.
            case 19:
                for (final ICraftingCPU cpu : ((ICraftingGrid) grid().getCache(ICraftingGrid.class)).getCpus()) {
                    if (!GraphCpus.isGraphCpu(cpu)) forcedCpu = cpu;
                }
                before = stored(new ItemStack(Items.flint));
                request(new ItemStack(Items.flint), 2);
                return;
            case 20:
                awaitPlan("2 flint on the plain CPU", false);
                return;
            case 21:
                forcedCpu = null;
                if (awaitCrafted(new ItemStack(Items.flint), before + 2, "2 flint on the plain CPU", true)) next();
                return;
            // 6. Too much: missing gravel, cannot start.
            case 22:
                request(new ItemStack(Items.flint), 600);
                return;
            case 23:
                awaitPlan("600 flint (missing gravel)", true);
                return;
            // 7. Slow machine: the unneeded byproduct must reach ME while the job is still running.
            case 24:
                byproductSeenEarly = false;
                request(new ItemStack(Items.netherbrick), 8);
                return;
            case 25:
                awaitPlan("8 nether bricks (slow machine)", false);
                return;
            case 26:
                if (stored(new ItemStack(Items.glowstone_dust)) > 0 && anyCpuBusy()) byproductSeenEarly = true;
                if (awaitCrafted(new ItemStack(Items.netherbrick), 8, "8 nether bricks", true)) next();
                return;
            case 27:
                if (!waited(40)) return;
                check(byproductSeenEarly, "byproduct reached ME storage while the job was still running");
                check(stored(new ItemStack(Items.glowstone_dust)) == 8, "all 8 glowstone dust in storage");
                next();
                return;
            // 8. Machine gives fewer byproducts than the pattern says: the job must recover and free the CPU.
            case 28:
                AEGraphConfig.stallSeconds = 3;
                request(new ItemStack(Blocks.torch), 2);
                return;
            case 29:
                // Sticks show up after planning, before the CPU waits for any: only stall recovery can use them.
                beforeSubmit = () -> inject(new ItemStack(Items.stick, 5));
                awaitPlan("2 torches (short-changing machine)", false);
                return;
            case 30:
                if (awaitCrafted(new ItemStack(Blocks.torch), 2, "2 torches", true)) next();
                return;
            case 31:
                if (!waited(40)) return;
                AEGraphConfig.stallSeconds = 60;
                check(stored(new ItemStack(Blocks.torch)) == 2, "2 torches delivered after the stall");
                check(stored(new ItemStack(Items.stick)) == 3, "the 2 missing sticks were taken from storage");
                check(stored(new ItemStack(Items.coal, 1, 1)) == 2, "charcoal byproduct in storage");
                check(!anyCpuBusy(), "no CPU left busy waiting for sticks that never come");
                next();
                return;
            // 9. Substituting pattern: the graph uses birch planks for a pattern written with oak planks.
            case 32:
                request(new ItemStack(Blocks.wooden_button), 2);
                return;
            case 33:
                awaitPlan("2 buttons (substitution)", false);
                return;
            case 34:
                if (stageStart == tick - 1) {
                    check(job instanceof GraphCraftingJob && ((GraphCraftingJob) job).usesGraph(),
                            "graph plan used, no fallback to AE2");
                }
                if (awaitCrafted(new ItemStack(Blocks.wooden_button), 2, "2 buttons", true)) next();
                return;
            case 35:
                if (!waited(40)) return;
                check(stored(new ItemStack(Blocks.planks, 1, 2)) == 1, "2 birch planks used");
                next();
                return;
            // 10. Remote Crafting Bus: a second, independent network B with its own storage, CPU and patterns.
            case 36:
                buildRemote();
                next();
                return;
            case 37:
                if (!waited(20)) return;
                setupRemote();
                next();
                return;
            case 38: {
                if (waited(400)) throw new IllegalStateException("network B / remote bus not ready: " + describeBus());
                final IGrid a = grid();
                final IGrid b = gridB();
                if (a == null || b == null) return;
                final ICraftingGrid ca = a.getCache(ICraftingGrid.class);
                final ICraftingGrid cb = b.getCache(ICraftingGrid.class);
                if (cb.getCpus().isEmpty() || cb.getCraftingPatterns().size() < 3) return;
                if (!ca.getCraftingPatterns().containsKey(ae(new ItemStack(Blocks.brick_block)))) return;
                log("remote bus: " + describeBus());
                check(a != b, "network A and network B are separate grids");
                final List<ICraftingPatternDetails> bricks = ca.getCraftingPatterns().get(ae(new ItemStack(Items.brick)));
                check(bricks != null && bricks.size() == 2 && remoteCount(bricks) == 1,
                        "network A makes bricks with its own pattern or through the bus");
                final List<ICraftingPatternDetails> blocks = ca.getCraftingPatterns()
                        .get(ae(new ItemStack(Blocks.brick_block)));
                check(blocks.size() == 1 && blocks.get(0) instanceof RemotePattern
                        && blocks.get(0).getCondensedInputs().length == 0,
                        "network A sees network B's brick block as one step without inputs (it never plans B's chain)");
                check(!ca.getCraftingPatterns().containsKey(ae(new ItemStack(Items.clay_ball))),
                        "crafts of network B's other interface are not offered to network A");
                check(!cb.getCraftingPatterns().containsKey(ae(new ItemStack(Items.flint))),
                        "network B does not see network A's patterns");
                final List<ICraftingPatternDetails> glass = ca.getCraftingPatterns().get(ae(new ItemStack(Blocks.glass)));
                final List<ICraftingPatternDetails> bottles = ca.getCraftingPatterns()
                        .get(ae(new ItemStack(Items.glass_bottle)));
                check(glass != null && remoteCount(glass) == 1 && bottles != null && remoteCount(bottles) == 1
                        && ((RemotePattern) bottles.get(0)).output().getStackSize() == 2,
                        "both outputs of a two-output pattern are offered to network A (2 bottles per run)");
                injectB(new ItemStack(Blocks.clay, 2));
                injectB(new ItemStack(Blocks.sand, 1));
                inject(new ItemStack(Blocks.wool, 4));
                next();
                return;
            }
            case 39:
                if (!waited(5)) return;
                check(stored(new ItemStack(Blocks.clay)) == 0, "network A does not see network B's storage");
                request(new ItemStack(Items.brick), 2);
                return;
            case 40:
                awaitPlan("2 bricks (bus not exclusive)", false);
                return;
            case 41:
                if (awaitCrafted(new ItemStack(Items.brick), 2, "2 bricks (bus not exclusive)", true)) next();
                return;
            case 42:
                if (!waited(20)) return;
                check(stored(new ItemStack(Blocks.wool)) == 2, "not exclusive: network A used its own pattern first");
                check(storedB(new ItemStack(Blocks.clay)) == 2 && !anyCpuBusyB(), "network B was not asked");
                extract(new ItemStack(Items.brick), stored(new ItemStack(Items.brick)));
                next();
                return;
            // 11. An item only network B can make: A runs one opaque step, B calculates and crafts the chain.
            case 43:
                remoteJobSeen = false;
                request(new ItemStack(Blocks.brick_block), 1);
                return;
            case 44:
                awaitPlan("1 brick block through the bus", false);
                return;
            case 45:
                if (stageStart == tick - 1) checkOnlyRemoteStep(new ItemStack(Blocks.brick_block));
                if (anyCpuBusyB()) remoteJobSeen = true;
                if (awaitCrafted(new ItemStack(Blocks.brick_block), 1, "1 brick block (network B)", true)) next();
                return;
            case 46:
                if (!waited(40)) return;
                check(remoteJobSeen, "network B ran the job on its own CPU");
                check(!anyCpuBusyB(), "network B's CPU is free again");
                check(storedB(new ItemStack(Blocks.clay)) == 1 && storedB(new ItemStack(Items.clay_ball)) == 0
                        && storedB(new ItemStack(Items.brick)) == 0,
                        "network B calculated the chain itself: 1 clay block -> 4 clay -> 4 bricks -> brick block");
                check(storedB(new ItemStack(Blocks.brick_block)) == 0, "the result went to network A");
                goTo(70);
                return;
            // 11b. The second output of a pattern: network A orders bottles, network B runs sand -> glass + bottles.
            case 70:
                request(new ItemStack(Items.glass_bottle), 2);
                return;
            case 71:
                awaitPlan("2 bottles (second output of network B's pattern)", false);
                return;
            case 72:
                if (awaitCrafted(new ItemStack(Items.glass_bottle), 2, "2 bottles (network B)", true)) next();
                return;
            case 73:
                if (!waited(40)) return;
                check(stored(new ItemStack(Items.glass_bottle)) == 2 && storedB(new ItemStack(Blocks.sand)) == 0,
                        "network B made the bottles from its sand and sent them to network A");
                check(storedB(new ItemStack(Blocks.glass)) == 1 && stored(new ItemStack(Blocks.glass)) == 0,
                        "the other output of that pattern (glass) stayed in network B");
                goTo(47);
                return;
            // 12. Exclusive ("priority"): network A's own brick pattern is no longer used.
            case 47:
                bus().setExclusive(true);
                next();
                return;
            case 48: {
                if (!waited(5)) return;
                final List<ICraftingPatternDetails> bricks = ((ICraftingGrid) grid().getCache(ICraftingGrid.class))
                        .getCraftingPatterns().get(ae(new ItemStack(Items.brick)));
                check(bricks.size() == 1 && bricks.get(0) instanceof RemotePattern,
                        "exclusive: network A's own brick pattern is hidden from its calculators");
                // AE2's own calculator and a plain CPU must handle the remote step as well.
                AEGraphConfig.enabled = false;
                remoteJobSeen = false;
                requestAny(new ItemStack(Items.brick), 3);
                return;
            }
            case 49:
                awaitPlan("3 bricks (exclusive, AE2 calculator)", false);
                return;
            case 50:
                if (anyCpuBusyB()) remoteJobSeen = true;
                if (awaitCrafted(new ItemStack(Items.brick), 3, "3 bricks (exclusive, AE2 calculator)", true)) next();
                return;
            case 51:
                if (!waited(40)) return;
                AEGraphConfig.enabled = true;
                check(remoteJobSeen && stored(new ItemStack(Blocks.wool)) == 2,
                        "exclusive with AE2's calculator: made by network B, wool untouched");
                check(storedB(new ItemStack(Blocks.clay)) == 0 && storedB(new ItemStack(Items.clay_ball)) == 1,
                        "network B used its own clay for 3 bricks");
                extract(new ItemStack(Items.brick), stored(new ItemStack(Items.brick)));
                next();
                return;
            case 52:
                request(new ItemStack(Items.brick), 1);
                return;
            case 53:
                awaitPlan("1 brick (exclusive, graph)", false);
                return;
            case 54:
                if (awaitCrafted(new ItemStack(Items.brick), 1, "1 brick (exclusive, graph)", true)) next();
                return;
            case 55:
                if (!waited(40)) return;
                check(stored(new ItemStack(Blocks.wool)) == 2 && storedB(new ItemStack(Items.clay_ball)) == 0,
                        "exclusive with the graph planner: made by network B, wool untouched");
                extract(new ItemStack(Items.brick), stored(new ItemStack(Items.brick)));
                next();
                return;
            // 13. Network B lacks items: the bus waits and retries; cancelling in network A drops the request.
            case 56:
                request(new ItemStack(Items.brick), 2);
                return;
            case 57:
                awaitPlan("2 bricks (network B has no clay)", false);
                return;
            case 58: {
                if (!waited(60)) return;
                final NBTTagCompound state = bus().describe();
                log("remote bus: " + state);
                check("aegraph.remote.problem.missing".equals(state.getString("problem")),
                        "the bus reports that network B lacks items");
                check(state.getTagList("queued", 10).tagCount() == 1, "the request waits in the bus");
                check(anyCpuBusy(), "network A's CPU waits for the bricks");
                for (final ICraftingCPU cpu : ((ICraftingGrid) grid().getCache(ICraftingGrid.class)).getCpus()) {
                    if (cpu.isBusy()) ((CraftingCPUCluster) cpu).cancel();
                }
                next();
                return;
            }
            case 59: {
                if (!waited(160)) return;
                final NBTTagCompound state = bus().describe();
                check(state.getTagList("queued", 10).tagCount() == 0 && state.getTagList("running", 10).tagCount() == 0,
                        "cancelled in network A: the bus dropped the request");
                check(!anyCpuBusy() && !anyCpuBusyB(), "no CPU left busy in either network");
                next();
                return;
            }
            // 14. Reload in the middle of a remote job (slow machine in network B): phase 1 stops the server here,
            // phase 2 (-Daegraph.selftest.resume=true, same world) checks that the job finishes after the restart.
            case 60:
                injectB(new ItemStack(Blocks.clay, 2));
                remoteProcesses.get(0).every = 20;
                request(new ItemStack(Items.brick), 6);
                return;
            case 61:
                awaitPlan("6 bricks (slow machine in network B)", false);
                return;
            case 62:
                if (waited(1200)) throw new IllegalStateException("remote job did not start: " + describeBus());
                if (stored(new ItemStack(Items.brick)) < 2 || !anyCpuBusyB()) return;
                log("stopping the server in the middle of the remote job (" + stored(new ItemStack(Items.brick))
                        + " of 6 bricks delivered): " + describeBus());
                finish();
                return;
            case 100: {
                if (waited(600)) throw new IllegalStateException("after reload: networks not ready: " + describeBus());
                if (grid() == null || gridB() == null || bus() == null) return;
                if (!"aegraph.remote.state.ok".equals(bus().describe().getString("state"))) return;
                log("after reload: " + describeBus());
                check(anyCpuBusy(), "after reload: network A's CPU still waits for the bricks");
                check(anyCpuBusyB(), "after reload: network B's CPU still crafts them");
                next();
                return;
            }
            case 101:
                if (awaitCrafted(new ItemStack(Items.brick), 6, "6 bricks after the reload", true)) next();
                return;
            case 102: {
                if (!waited(60)) return;
                final NBTTagCompound state = bus().describe();
                check(stored(new ItemStack(Items.brick)) == 6, "after reload: all 6 bricks reached network A");
                check(!anyCpuBusy() && !anyCpuBusyB(), "after reload: both CPUs free");
                check(storedB(new ItemStack(Items.clay_ball)) == 2 && storedB(new ItemStack(Items.brick)) == 0,
                        "after reload: network B made exactly 6 bricks");
                check(state.getTagList("queued", 10).tagCount() == 0 && state.getTagList("running", 10).tagCount() == 0
                        && state.getTagList("deliver", 10).tagCount() == 0, "after reload: the bus has nothing left");
                finish();
                return;
            }
            default:
        }
    }

    // --- network B and the Remote Crafting Bus -------------------------------------------------------------

    /**
     * Network B south of network A, connected to nothing but the bus: the bus sits on a cable next to A's energy
     * cell and faces B's first interface.
     */
    private void buildRemote() {
        final ITileDefinition cell = AEApi.instance().definitions().blocks().energyCellCreative();
        world.setBlock(x0 + 1, y0, z0 + 2, AEApi.instance().definitions().blocks().iface().maybeBlock().get(), 0, 3);
        world.setBlock(x0 + 1, y0, z0 + 3, cell.maybeBlock().get(), 0, 3);
        world.setBlock(x0 + 2, y0, z0 + 3, AEApi.instance().definitions().blocks().drive().maybeBlock().get(), 0, 3);
        world.setBlock(x0 + 3, y0, z0 + 3,
                AEApi.instance().definitions().blocks().craftingStorage1k().maybeBlock().get(), 0, 3);
        world.setBlock(x0 + 3, y0, z0 + 4, AEApi.instance().definitions().blocks().iface().maybeBlock().get(), 0, 3);
        world.setBlock(x0, y0, z0 + 2, Blocks.chest, 0, 3);
        world.setBlock(x0 + 3, y0, z0 + 5, Blocks.chest, 0, 3);
        world.setBlock(x0 + 1, y0, z0 + 1, AEApi.instance().definitions().blocks().multiPart().maybeBlock().get(), 0,
                3);
        final IPartHost host = (IPartHost) world.getTileEntity(x0 + 1, y0, z0 + 1);
        host.addPart(AEApi.instance().definitions().parts().cableGlass().stack(AEColor.Transparent, 1),
                ForgeDirection.UNKNOWN, null);
        check(host.addPart(new ItemStack(AEGraphMod.remoteBus), ForgeDirection.SOUTH, null) == ForgeDirection.SOUTH,
                "Remote Crafting Bus placed on a cable");
        log("network B placed at " + (x0 + 1) + "," + y0 + "," + (z0 + 2));
    }

    private void setupRemote() {
        final TileDrive drive = (TileDrive) world.getTileEntity(x0 + 2, y0, z0 + 3);
        drive.getInternalInventory().setInventorySlotContents(0,
                AEApi.instance().definitions().items().cell64k().maybeStack(1).get());
        final TileInterface iface1 = (TileInterface) world.getTileEntity(x0 + 1, y0, z0 + 2);
        final TileInterface iface2 = (TileInterface) world.getTileEntity(x0 + 3, y0, z0 + 4);
        sourceB = new MachineSource((IActionHost) iface1);
        for (int k = 0; k < remoteProcesses.size(); k++) {
            iface1.getInventoryByName("patterns").setInventorySlotContents(k, processingPattern(remoteProcesses.get(k)));
        }
        for (int k = 0; k < remoteProcesses2.size(); k++) {
            iface2.getInventoryByName("patterns").setInventorySlotContents(k,
                    processingPattern(remoteProcesses2.get(k)));
        }
        remoteBuilt = true;
    }

    private MachineSource sourceB;

    private PartRemoteBus bus() {
        final TileEntity te = world.getTileEntity(x0 + 1, y0, z0 + 1);
        if (!(te instanceof IPartHost)) return null;
        final IPart part = ((IPartHost) te).getPart(ForgeDirection.SOUTH);
        return part instanceof PartRemoteBus ? (PartRemoteBus) part : null;
    }

    private String describeBus() {
        final PartRemoteBus bus = bus();
        return bus == null ? "no bus" : bus.describe().toString();
    }

    private IGrid gridB() {
        final TileEntity te = world.getTileEntity(x0 + 1, y0, z0 + 2);
        if (!(te instanceof IGridHost)) return null;
        final IGridNode node = ((IGridHost) te).getGridNode(ForgeDirection.UNKNOWN);
        return node == null ? null : node.getGrid();
    }

    private void injectB(ItemStack stack) {
        final IStorageGrid storage = gridB().getCache(IStorageGrid.class);
        final IAEItemStack left = storage.getItemInventory().injectItems(AEItemStack.create(stack), Actionable.MODULATE,
                sourceB);
        if (left != null) throw new IllegalStateException("network B refused " + left);
    }

    private long storedB(ItemStack what) {
        final IStorageGrid storage = gridB().getCache(IStorageGrid.class);
        final IAEItemStack found = storage.getItemInventory().getStorageList().findPrecise(AEItemStack.create(what));
        return found == null ? 0 : found.getStackSize();
    }

    private boolean anyCpuBusyB() {
        for (final ICraftingCPU cpu : ((ICraftingGrid) gridB().getCache(ICraftingGrid.class)).getCpus()) {
            if (cpu.isBusy()) return true;
        }
        return false;
    }

    private static IAEItemStack ae(ItemStack stack) {
        return AEItemStack.create(stack);
    }

    private static int remoteCount(List<ICraftingPatternDetails> patterns) {
        int n = 0;
        for (final ICraftingPatternDetails p : patterns) {
            if (p instanceof RemotePattern) n++;
        }
        return n;
    }

    /** Network A's plan takes nothing from storage and has one step: the remote craft of the target. */
    private void checkOnlyRemoteStep(ItemStack target) {
        final IItemList<IAEItemStack> plan = AEApi.instance().storage().createItemList();
        job.populatePlan(plan);
        boolean onlyTarget = true;
        for (final IAEItemStack s : plan) {
            if (s.getStackSize() > 0 || !s.isSameType(AEItemStack.create(target))) onlyTarget = false;
        }
        check(onlyTarget, "network A's plan has no ingredients of network B's chain: " + plan);
    }

    private boolean byproductSeenEarly;
    private Runnable beforeSubmit;

    private boolean anyCpuBusy() {
        for (final ICraftingCPU cpu : ((ICraftingGrid) grid().getCache(ICraftingGrid.class)).getCpus()) {
            if (cpu.isBusy()) return true;
        }
        return false;
    }

    /** The chest is the "machine": consume complete input sets and insert the outputs into the network. */
    private void runMachine(IInventory chest, List<Process> processes, boolean networkB) {
        if (chest == null) return;
        final boolean[] ranSlow = new boolean[processes.size()];
        boolean worked = true;
        while (worked) {
            worked = false;
            for (int p = processes.size() - 1; p >= 0; p--) {
                final Process process = processes.get(p);
                if (process.every > 0 && (ranSlow[p] || tick % process.every != 0)) continue;
                if (!has(chest, process.in, process.substitute)) continue;
                for (final ItemStack in : process.in) take(chest, in, process.substitute);
                for (final ItemStack out : process.real) {
                    if (networkB) injectB(out.copy());
                    else inject(out.copy());
                }
                machineLog.append(" t").append(tick - stageStart).append(networkB ? ":B" : ":p").append(p);
                ranSlow[p] = process.every > 0;
                worked = true;
            }
        }
    }

    private static boolean matches(ItemStack is, ItemStack w, boolean anyDamage) {
        return is != null && (anyDamage ? is.getItem() == w.getItem() : is.isItemEqual(w));
    }

    private static boolean has(IInventory inv, ItemStack[] wanted, boolean anyDamage) {
        for (final ItemStack w : wanted) {
            int count = 0;
            for (int s = 0; s < inv.getSizeInventory(); s++) {
                final ItemStack is = inv.getStackInSlot(s);
                if (matches(is, w, anyDamage)) count += is.stackSize;
            }
            if (count < w.stackSize) return false;
        }
        return true;
    }

    private static void take(IInventory inv, ItemStack w, boolean anyDamage) {
        int left = w.stackSize;
        for (int s = 0; s < inv.getSizeInventory() && left > 0; s++) {
            final ItemStack is = inv.getStackInSlot(s);
            if (!matches(is, w, anyDamage)) continue;
            final int n = Math.min(left, is.stackSize);
            inv.decrStackSize(s, n);
            left -= n;
        }
    }

    private void request(ItemStack what, long amount) {
        requestAny(what, amount);
        check(pending instanceof GraphCraftingJob, "request goes through the graph planner");
    }

    private void requestAny(ItemStack what, long amount) {
        final IGrid grid = grid();
        final ICraftingGrid crafting = grid.getCache(ICraftingGrid.class);
        final IAEItemStack stack = AEItemStack.create(what).setStackSize(amount);
        pending = crafting.beginCraftingJob(world, grid, source, stack, null);
        next();
    }

    private void awaitPlan(String name, boolean expectMissing) {
        if (waited(600)) throw new IllegalStateException("timeout: plan for " + name);
        if (!pending.isDone()) return;
        try {
            job = pending.get();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        final IItemList<IAEItemStack> plan = AEApi.instance().storage().createItemList();
        job.populatePlan(plan);
        final StringBuilder sb = new StringBuilder();
        for (final IAEItemStack s : plan) {
            sb.append("\n    ").append(s.getItemStack().getDisplayName()).append(": take ").append(s.getStackSize())
                    .append(", craft ").append(s.getCountRequestable());
        }
        log("plan for " + name + " (simulation=" + job.isSimulation() + ", bytes=" + job.getByteTotal() + ")"
                + (job instanceof GraphCraftingJob ? ": " + GraphCraftingJob.lastPlanSummary : "") + sb);
        check(job.isSimulation() == expectMissing, name + ": simulation flag " + job.isSimulation());
        if (!expectMissing) {
            if (beforeSubmit != null) {
                beforeSubmit.run();
                beforeSubmit = null;
            }
            final ICraftingGrid crafting = grid().getCache(ICraftingGrid.class);
            check(crafting.submitJob(job, null, forcedCpu, false, source) != null, name + ": job submitted to a CPU");
            if (forcedCpu != null) check(forcedCpu.isBusy(), name + ": the chosen CPU took the job");
        }
        next();
    }

    private boolean awaitCrafted(ItemStack what, long amount, String name, boolean mustFinish) {
        final long have = stored(what);
        final ICraftingGrid crafting = grid().getCache(ICraftingGrid.class);
        boolean busy = false;
        for (final ICraftingCPU cpu : crafting.getCpus()) {
            busy |= cpu.isBusy();
        }
        if (have >= amount && !busy) {
            log(name + " crafted: " + have + " in storage after " + (tick - stageStart) + " ticks");
            log(name + " machine timeline (ticks since submit):" + machineLog);
            machineLog.setLength(0);
            return true;
        }
        if ((tick - stageStart) % 200 == 199) dumpCpu(crafting, name);
        if (waited(1200)) {
            if (mustFinish) check(false, name + ": only " + have + " crafted, cpu busy=" + busy);
            else log(name + ": not finished, " + have + " crafted, cpu busy=" + busy);
            return true;
        }
        return false;
    }

    private void dumpCpu(ICraftingGrid crafting, String name) {
        for (final ICraftingCPU cpu : crafting.getCpus()) {
            final StringBuilder sb = new StringBuilder("cpu state (" + name + "):");
            for (final CraftingItemList kind : CraftingItemList.values()) {
                if (kind == CraftingItemList.ALL) continue;
                final IItemList<IAEItemStack> list = AEApi.instance().storage().createItemList();
                ((CraftingCPUCluster) cpu).getListOfItem(list, kind);
                sb.append("\n    ").append(kind).append(':');
                for (final IAEItemStack s : list) {
                    sb.append(' ').append(s.getStackSize()).append('x').append(s.getItemStack().getDisplayName());
                }
            }
            log(sb.toString());
        }
    }

    private void check(boolean ok, String what) {
        log((ok ? "ok   " : "FAIL ") + what);
        if (!ok) failed = true;
    }

    private void finish() {
        stage = -1;
        log(failed ? "FAIL" : "PASS");
        MinecraftServer.getServer().initiateShutdown();
    }

    private void place(int dx, ITileDefinition definition) {
        world.setBlock(x0 + dx, y0, z0, definition.maybeBlock().get(), 0, 3);
    }

    private IGrid grid() {
        final TileEntity te = world.getTileEntity(x0 + 3, y0, z0);
        if (!(te instanceof IGridHost)) return null;
        final IGridNode node = ((IGridHost) te).getGridNode(ForgeDirection.UNKNOWN);
        return node == null ? null : node.getGrid();
    }

    private void inject(ItemStack stack) {
        final IStorageGrid storage = grid().getCache(IStorageGrid.class);
        final IAEItemStack left = storage.getItemInventory().injectItems(AEItemStack.create(stack), Actionable.MODULATE,
                source);
        if (left != null) throw new IllegalStateException("network refused " + left);
    }

    private void extract(ItemStack stack, long amount) {
        if (amount <= 0) return;
        final IStorageGrid storage = grid().getCache(IStorageGrid.class);
        storage.getItemInventory().extractItems(AEItemStack.create(stack).setStackSize(amount), Actionable.MODULATE,
                source);
    }

    private long stored(ItemStack what) {
        final IStorageGrid storage = grid().getCache(IStorageGrid.class);
        final IAEItemStack found = storage.getItemInventory().getStorageList().findPrecise(AEItemStack.create(what));
        return found == null ? 0 : found.getStackSize();
    }

    private static ItemStack processingPattern(Process process) {
        final NBTTagList in = new NBTTagList();
        for (final ItemStack stack : process.in) {
            final NBTTagCompound tag = new NBTTagCompound();
            stack.writeToNBT(tag);
            in.appendTag(tag);
        }
        final NBTTagList out = new NBTTagList();
        for (final ItemStack stack : process.out) {
            final NBTTagCompound tag = new NBTTagCompound();
            stack.writeToNBT(tag);
            out.appendTag(tag);
        }
        final NBTTagCompound data = new NBTTagCompound();
        data.setTag("in", in);
        data.setTag("out", out);
        data.setBoolean("crafting", false);
        data.setBoolean("substitute", process.substitute);
        final ItemStack pattern = AEApi.instance().definitions().items().encodedPattern().maybeStack(1).get();
        pattern.setTagCompound(data);
        return pattern;
    }
}
