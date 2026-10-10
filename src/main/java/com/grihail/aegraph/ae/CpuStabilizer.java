package com.grihail.aegraph.ae;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.WeakHashMap;

import net.minecraft.server.MinecraftServer;

import com.grihail.aegraph.AEGraphConfig;
import com.grihail.aegraph.AEGraphMod;
import com.grihail.aegraph.remote.RemoteCrafting;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.FuzzyMode;
import appeng.api.networking.IGrid;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.storage.IMEInventory;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.storage.data.IAEStack;
import appeng.api.storage.data.IItemList;
import appeng.crafting.MECraftingInventory;
import appeng.me.cluster.implementations.CraftingCPUCluster;

/**
 * Keeps crafting CPUs from locking items away and from hanging forever.
 * <p>
 * AE2's CPU captures every output of a pushed pattern (except the final output) into its own buffer and gives it
 * back only when the whole job completes. A long job therefore hides all byproducts and side products until the
 * end, and if one task can never run (a machine gave fewer byproducts than its pattern says), the job never
 * completes: the items stay locked, the status keeps showing items "being crafted", and the CPU stays busy for new
 * requests until it is cancelled by hand.
 * <ul>
 * <li><b>Return what is not needed.</b> Arriving items that no remaining task needs go straight into ME storage,
 * and the buffer is trimmed to what the remaining tasks need.</li>
 * <li><b>Stall recovery.</b> When nothing happens for {@code stallSeconds}: take what the remaining tasks lack from
 * ME storage; if storage has nothing and the requested items are already delivered, finish the job.</li>
 * <li><b>Phantom waits.</b> A completed job whose expected items never came keeps the CPU busy; those waits are
 * dropped after {@code stallSeconds} of silence.</li>
 * </ul>
 */
public final class CpuStabilizer {

    private CpuStabilizer() {}

    private static final class State {

        long lastActivity;
        long lastSignature = Long.MIN_VALUE;
        long lastCheck;
        long needsComputedAt = Long.MIN_VALUE;
        /** Inputs the remaining tasks need, exact items. */
        IItemList<IAEItemStack> needs;
        /** Inputs of remaining tasks whose patterns accept substitutes: anything matching them is kept. */
        List<IAEItemStack> fuzzyNeeds = new ArrayList<>();
        boolean warned;
    }

    private static final Map<CraftingCPUCluster, State> STATES = new WeakHashMap<>();
    /** Set while we move items into storage ourselves, so the CPU does not grab them back. */
    private static boolean releasing = false;

    private static boolean ready = false;
    private static boolean broken = false;
    private static Field tasksField;
    private static Field valueField;
    private static Field inventoryField;
    private static Field waitingForField;
    private static Field finalOutputField;
    private static Field isCompleteField;
    private static Field machineSrcField;
    private static Method completeJobMethod;
    private static Method postStatusMethod;
    private static Method postChangeMethod;
    private static Method markDirtyMethod;
    private static Method getGridMethod;

    private static synchronized void init() throws ReflectiveOperationException {
        if (ready) return;
        final Class<CraftingCPUCluster> c = CraftingCPUCluster.class;
        tasksField = field(c, "tasks");
        inventoryField = field(c, "inventory");
        waitingForField = field(c, "waitingFor");
        finalOutputField = field(c, "finalOutput");
        isCompleteField = field(c, "isComplete");
        machineSrcField = field(c, "machineSrc");
        completeJobMethod = method(c, "completeJob");
        postStatusMethod = method(c, "postCraftingStatusChange", IAEItemStack.class);
        postChangeMethod = method(c, "postChange", IAEItemStack.class, BaseActionSource.class);
        markDirtyMethod = method(c, "markDirty");
        getGridMethod = method(c, "getGrid");
        for (final Class<?> inner : c.getDeclaredClasses()) {
            if (inner.getSimpleName().equals("TaskProgress")) {
                valueField = field(inner, "value");
            }
        }
        if (valueField == null) throw new NoSuchFieldException("CraftingCPUCluster.TaskProgress.value");
        ready = true;
    }

    private static Field field(Class<?> c, String name) throws NoSuchFieldException {
        final Field f = c.getDeclaredField(name);
        f.setAccessible(true);
        return f;
    }

    private static Method method(Class<?> c, String name, Class<?>... args) throws NoSuchMethodException {
        final Method m = c.getDeclaredMethod(name, args);
        m.setAccessible(true);
        return m;
    }

    private static boolean usable() {
        if (broken || !AEGraphConfig.enabled) return false;
        if (!ready) {
            try {
                init();
            } catch (Throwable t) {
                broken = true;
                AEGraphMod.LOG.error("CPU stabilizer disabled: AE2 internals differ", t);
                return false;
            }
        }
        return true;
    }

    private static State state(CraftingCPUCluster cpu, long now) {
        State s = STATES.get(cpu);
        if (s == null) {
            s = new State();
            s.lastActivity = now;
            STATES.put(cpu, s);
        }
        return s;
    }

    private static long now(CraftingCPUCluster cpu) {
        final MinecraftServer server = MinecraftServer.getServer();
        return server == null ? 0 : server.getTickCounter();
    }

    private static String name(CraftingCPUCluster cpu) {
        final String name = cpu.getName();
        return name == null || name.isEmpty() ? "(unnamed)" : name;
    }

    // --- items arriving at the CPU ------------------------------------------------------------------------------

    /**
     * Start of {@code CraftingCPUCluster.injectItems}. True: the CPU must not take the stack (it continues into ME
     * storage); the expected amount is still counted as arrived.
     */
    public static boolean passThrough(CraftingCPUCluster cpu, IAEStack<?> input, Actionable type,
            BaseActionSource src) {
        if (releasing) return true;
        if (!AEGraphConfig.returnUnneededItems || !(input instanceof IAEItemStack) || !usable()) return false;
        try {
            if (isCompleteField.getBoolean(cpu)) return false;
            final IAEItemStack what = (IAEItemStack) input;
            final IItemList<IAEItemStack> waitingFor = list(waitingForField, cpu);
            final IAEItemStack expected = waitingFor.findPrecise(what);
            if (expected == null || expected.getStackSize() <= 0) return false;
            if (Objects.equals(finalOutputField.get(cpu), what)) return false;
            final long now = now(cpu);
            final State state = state(cpu, now);
            refreshNeeds(cpu, state, now);
            if (isNeeded(cpu, state, what)) return false;
            if (type == Actionable.MODULATE) {
                final long counted = Math.min(expected.getStackSize(), what.getStackSize());
                expected.decStackSize(counted);
                postStatusMethod.invoke(cpu, expected);
                postChangeMethod.invoke(cpu, what.copy(), src);
                markDirtyMethod.invoke(cpu);
                state.lastActivity = now;
            }
            return true;
        } catch (Throwable t) {
            broken = true;
            AEGraphMod.LOG.error("CPU stabilizer disabled", t);
            return false;
        }
    }

    private static boolean isNeeded(CraftingCPUCluster cpu, State state, IAEItemStack what) throws Exception {
        for (final IAEItemStack fuzzy : state.fuzzyNeeds) {
            if (fuzzy.isSameType(what) || fuzzy.fuzzyComparison(what, FuzzyMode.IGNORE_ALL) || fuzzy.sameOre(what)) {
                return true;
            }
        }
        final IAEItemStack need = state.needs.findPrecise(what);
        if (need == null || need.getStackSize() <= 0) return false;
        final IAEItemStack have = inventory(cpu).getItemList().findPrecise(what);
        return have == null || have.getStackSize() < need.getStackSize();
    }

    // --- every tick -------------------------------------------------------------------------------------------

    /** Start of {@code CraftingCPUCluster.updateCraftingLogic}. */
    public static void onUpdate(CraftingCPUCluster cpu) {
        if (!usable()) return;
        try {
            final long now = now(cpu);
            final State state = state(cpu, now);
            if (now - state.lastCheck < 20) return;
            state.lastCheck = now;

            final Map<?, ?> tasks = (Map<?, ?>) tasksField.get(cpu);
            final IItemList<IAEItemStack> waitingFor = list(waitingForField, cpu);
            final boolean complete = isCompleteField.getBoolean(cpu);
            final long signature = signature(cpu, tasks, waitingFor);
            // Items another network is crafting for this CPU (Remote Crafting Bus) may take long: not a stall.
            if (signature != state.lastSignature
                    || !complete && RemoteCrafting.waitsForRemote(cpu, (IGrid) getGridMethod.invoke(cpu))) {
                state.lastSignature = signature;
                state.lastActivity = now;
            }
            final long stallTicks = AEGraphConfig.stallSeconds * 20L;

            if (complete) {
                // Job done but some expected items never came: AE2 keeps the CPU "busy" for good.
                if (stallTicks > 0 && !waitingFor.isEmpty() && now - state.lastActivity >= stallTicks) {
                    dropWaits(cpu);
                    AEGraphMod.LOG.info("CPU {}: dropped expected items that never arrived after the job finished",
                            name(cpu));
                    state.lastActivity = now;
                }
                return;
            }
            if (tasks.isEmpty()) return;

            refreshNeeds(cpu, state, now);
            if (AEGraphConfig.returnUnneededItems) release(cpu, state);
            if (stallTicks > 0 && now - state.lastActivity >= stallTicks) {
                recover(cpu, state, tasks);
                state.lastActivity = now;
            }
        } catch (Throwable t) {
            broken = true;
            AEGraphMod.LOG.error("CPU stabilizer disabled", t);
        }
    }

    private static long signature(CraftingCPUCluster cpu, Map<?, ?> tasks, IItemList<IAEItemStack> waitingFor)
            throws Exception {
        long sig = 17;
        for (final Object progress : tasks.values()) sig = sig * 31 + valueField.getLong(progress);
        for (final IAEItemStack s : waitingFor) sig = sig * 31 + s.getStackSize();
        for (final IAEItemStack s : inventory(cpu).getItemList()) sig = sig * 31 + s.getStackSize();
        return sig;
    }

    private static void refreshNeeds(CraftingCPUCluster cpu, State state, long now) throws Exception {
        if (state.needsComputedAt == now && state.needs != null) return;
        state.needsComputedAt = now;
        final IItemList<IAEItemStack> needs = AEApi.instance().storage().createItemList();
        final List<IAEItemStack> fuzzy = new ArrayList<>();
        for (final Map.Entry<?, ?> e : ((Map<?, ?>) tasksField.get(cpu)).entrySet()) {
            final long runs = valueField.getLong(e.getValue());
            if (runs <= 0) continue;
            final ICraftingPatternDetails details = (ICraftingPatternDetails) e.getKey();
            for (final IAEItemStack in : details.getCondensedInputs()) {
                if (in == null) continue;
                needs.add(in.copy().setStackSize(in.getStackSize() * runs));
                if (details.canSubstitute()) fuzzy.add(in);
            }
        }
        state.needs = needs;
        state.fuzzyNeeds = fuzzy;
    }

    /** Moves everything above what the remaining tasks need from the CPU buffer into ME storage. */
    private static void release(CraftingCPUCluster cpu, State state) throws Exception {
        final IMEInventory<IAEItemStack> storage = storage(cpu);
        if (storage == null) return;
        final MECraftingInventory inventory = inventory(cpu);
        final Object finalOutput = finalOutputField.get(cpu);
        final BaseActionSource src = (BaseActionSource) machineSrcField.get(cpu);
        final List<IAEItemStack> extra = new ArrayList<>();
        for (final IAEItemStack have : inventory.getItemList()) {
            if (have.getStackSize() <= 0 || Objects.equals(finalOutput, have)) continue;
            if (!state.fuzzyNeeds.isEmpty() && isNeeded(cpu, state, have.copy().setStackSize(1))) continue;
            final IAEItemStack need = state.needs.findPrecise(have);
            final long surplus = have.getStackSize() - (need == null ? 0 : need.getStackSize());
            if (surplus > 0) extra.add(have.copy().setStackSize(surplus));
        }
        if (extra.isEmpty()) return;
        boolean moved = false;
        releasing = true;
        try {
            for (final IAEItemStack stack : extra) {
                final IAEItemStack taken = inventory.extractItems(stack, Actionable.MODULATE, src);
                if (taken == null || taken.getStackSize() <= 0) continue;
                final IAEItemStack left = storage.injectItems(taken.copy(), Actionable.MODULATE, src);
                final long stored = taken.getStackSize() - (left == null ? 0 : left.getStackSize());
                if (left != null && left.getStackSize() > 0) inventory.injectItems(left, Actionable.MODULATE, src);
                if (stored > 0) {
                    postChangeMethod.invoke(cpu, taken.copy().setStackSize(stored), src);
                    moved = true;
                }
            }
        } finally {
            releasing = false;
        }
        if (moved) markDirtyMethod.invoke(cpu);
    }

    /** Nothing happened for a while: fill what the remaining tasks lack from storage, or finish the job. */
    private static void recover(CraftingCPUCluster cpu, State state, Map<?, ?> tasks) throws Exception {
        final IMEInventory<IAEItemStack> storage = storage(cpu);
        if (storage == null) return;
        final MECraftingInventory inventory = inventory(cpu);
        final BaseActionSource src = (BaseActionSource) machineSrcField.get(cpu);

        // Balance of the rest of the job, assuming the overdue expected items never come.
        final IItemList<IAEItemStack> supply = AEApi.instance().storage().createItemList();
        for (final IAEItemStack have : inventory.getItemList()) supply.add(have.copy());
        for (final Map.Entry<?, ?> e : tasks.entrySet()) {
            final long runs = valueField.getLong(e.getValue());
            if (runs <= 0) continue;
            for (final IAEItemStack out : ((ICraftingPatternDetails) e.getKey()).getCondensedOutputs()) {
                if (out != null) supply.add(out.copy().setStackSize(out.getStackSize() * runs));
            }
        }
        final List<IAEItemStack> lacking = new ArrayList<>();
        for (final IAEItemStack need : state.needs) {
            final IAEItemStack has = supply.findPrecise(need);
            final long lack = need.getStackSize() - (has == null ? 0 : has.getStackSize());
            if (lack > 0) lacking.add(need.copy().setStackSize(lack));
        }
        long pulled = pull(storage, inventory, lacking, src);

        if (pulled == 0) {
            // Balanced but deadlocked (a loop waiting for itself): one run's worth for every blocked task.
            final List<IAEItemStack> seeds = new ArrayList<>();
            for (final Map.Entry<?, ?> e : tasks.entrySet()) {
                if (valueField.getLong(e.getValue()) <= 0) continue;
                final ICraftingPatternDetails details = (ICraftingPatternDetails) e.getKey();
                if (details.canSubstitute()) continue;
                for (final IAEItemStack in : details.getCondensedInputs()) {
                    if (in == null) continue;
                    final IAEItemStack has = inventory.getItemList().findPrecise(in);
                    final long lack = in.getStackSize() - (has == null ? 0 : has.getStackSize());
                    if (lack > 0) seeds.add(in.copy().setStackSize(lack));
                }
            }
            pulled = pull(storage, inventory, seeds, src);
            if (pulled == 0) lacking.addAll(seeds);
        }
        if (pulled > 0) {
            AEGraphMod.LOG.info("CPU {}: stalled, took {} missing items from ME storage", name(cpu), pulled);
            state.warned = false;
            markDirtyMethod.invoke(cpu);
            return;
        }

        final IAEItemStack finalOutput = (IAEItemStack) finalOutputField.get(cpu);
        if (finalOutput == null || finalOutput.getStackSize() <= 0) {
            // Everything requested is delivered; the rest can't run. Close the job, AE2 returns the buffer.
            tasks.clear();
            completeJobMethod.invoke(cpu);
            dropWaits(cpu);
            markDirtyMethod.invoke(cpu);
            AEGraphMod.LOG.info("CPU {}: requested items delivered, dropped remaining steps that lack {}",
                    name(cpu), names(lacking));
            return;
        }
        if (!state.warned) {
            state.warned = true;
            AEGraphMod.LOG.warn("CPU {}: crafting of {} is stuck, ME storage lacks {}", name(cpu),
                    ModelBuilder.displayName(finalOutput), names(lacking));
        }
    }

    private static long pull(IMEInventory<IAEItemStack> storage, MECraftingInventory inventory,
            List<IAEItemStack> wanted, BaseActionSource src) {
        long pulled = 0;
        for (final IAEItemStack want : wanted) {
            if (want.getStackSize() <= 0) continue;
            final IAEItemStack got = storage.extractItems(want.copy(), Actionable.MODULATE, src);
            if (got == null || got.getStackSize() <= 0) continue;
            inventory.injectItems(got, Actionable.MODULATE, src);
            pulled += got.getStackSize();
        }
        return pulled;
    }

    private static void dropWaits(CraftingCPUCluster cpu) throws Exception {
        final IItemList<IAEItemStack> waitingFor = list(waitingForField, cpu);
        final List<IAEItemStack> dropped = new ArrayList<>();
        for (final IAEItemStack s : waitingFor) dropped.add(s.copy());
        waitingFor.resetStatus();
        for (final IAEItemStack s : dropped) postStatusMethod.invoke(cpu, s.setStackSize(0));
        markDirtyMethod.invoke(cpu);
    }

    private static String names(List<IAEItemStack> stacks) {
        if (stacks.isEmpty()) return "nothing (deadlock)";
        final StringBuilder sb = new StringBuilder();
        for (int k = 0; k < stacks.size() && k < 8; k++) {
            if (k > 0) sb.append(", ");
            sb.append(stacks.get(k).getStackSize()).append(" x ").append(ModelBuilder.displayName(stacks.get(k)));
        }
        if (stacks.size() > 8) sb.append(", ...");
        return sb.toString();
    }

    @SuppressWarnings("unchecked")
    private static IItemList<IAEItemStack> list(Field field, CraftingCPUCluster cpu) throws IllegalAccessException {
        return (IItemList<IAEItemStack>) field.get(cpu);
    }

    private static MECraftingInventory inventory(CraftingCPUCluster cpu) throws IllegalAccessException {
        return (MECraftingInventory) inventoryField.get(cpu);
    }

    private static IMEInventory<IAEItemStack> storage(CraftingCPUCluster cpu) throws Exception {
        final IGrid grid = (IGrid) getGridMethod.invoke(cpu);
        if (grid == null) return null;
        final IStorageGrid sg = grid.getCache(IStorageGrid.class);
        return sg == null ? null : sg.getItemInventory();
    }
}
