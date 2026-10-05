package com.grihail.aegraph.remote;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.Future;

import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Vec3;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.google.common.collect.ImmutableSet;
import com.grihail.aegraph.AEGraphMod;
import com.grihail.aegraph.ae.GraphCpus;
import com.grihail.aegraph.network.AEGraphNetwork;

import appeng.api.AEApi;
import appeng.api.config.Actionable;
import appeng.api.config.CraftingAllow;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingCPU;
import appeng.api.networking.crafting.ICraftingGrid;
import appeng.api.networking.crafting.ICraftingJob;
import appeng.api.networking.crafting.ICraftingLink;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingProviderHelper;
import appeng.api.networking.crafting.ICraftingRequester;
import appeng.api.networking.events.MENetworkCraftingPatternChange;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.security.IActionHost;
import appeng.api.networking.security.MachineSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartCollisionHelper;
import appeng.api.parts.IPartHost;
import appeng.api.parts.IPartRenderHelper;
import appeng.api.storage.data.IAEItemStack;
import appeng.api.util.AECableType;
import appeng.client.texture.CableBusTextures;
import appeng.crafting.CraftingLink;
import appeng.helpers.DualityInterface;
import appeng.helpers.IInterfaceHost;
import appeng.me.GridAccessException;
import appeng.me.cache.CraftingGridCache;
import appeng.me.cluster.implementations.CraftingCPUCluster;
import appeng.parts.PartBasicState;
import appeng.util.item.AEItemStack;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Remote Crafting Bus: sits on a cable of network A and faces an ME Interface of another, independent network B.
 * <p>
 * The crafts of that interface (every output of each of its patterns) show up in network A as craftable.
 * Network A plans them as one opaque step "nothing -> item": its calculator never looks inside network B. When a
 * CPU of network A runs such a step, the bus asks network B to craft the item - B calculates the whole chain with
 * its own patterns, storage and CPUs - and the bus moves what B's CPU delivers into network A, where A's CPU is
 * waiting for it. The two grids stay separate: no storage, patterns or crafting calculations are shared.
 * <p>
 * Exclusive mode ("priority"): network A crafts these items only through this bus, its own patterns for them are
 * hidden from its calculators.
 */
public class PartRemoteBus extends PartBasicState implements ICraftingProvider, IGridTickable {

    /** Wait after network B could not take a request (missing items, no free CPU). */
    private static final int RETRY_TICKS = 100;
    /** Network A stopped waiting for an item this long ago: drop what is still queued for it in network B. */
    private static final int CANCEL_GRACE_TICKS = 100;

    /** Live buses on the server, for the CPU hooks. */
    static final java.util.Set<PartRemoteBus> LIVE = Collections
            .newSetFromMap(new java.util.WeakHashMap<PartRemoteBus, Boolean>());

    /** A job running on a CPU of network B for us. */
    private static final class Request {

        final ICraftingLink link;
        final IAEItemStack item;
        final long amount;
        long delivered;

        Request(ICraftingLink link, IAEItemStack item, long amount) {
            this.link = link;
            this.item = item;
            this.amount = amount;
        }
    }

    /** A crafting calculation running in network B. */
    private static final class Calculation {

        final Future<ICraftingJob> future;
        final long amount;

        Calculation(Future<ICraftingJob> future, long amount) {
            this.future = future;
            this.amount = amount;
        }
    }

    private final BaseActionSource ownSource;
    private final Requester requester = new Requester();
    private final BaseActionSource remoteSource = new MachineSource(requester);

    private boolean exclusive;
    /** Item (size 1) -> amount network A asked for that network B has not taken yet. */
    private final Map<IAEItemStack, Long> queued = new LinkedHashMap<>();
    private final List<Request> running = new ArrayList<>();
    /** Item (size 1) -> amount network B delivered that network A did not accept yet. */
    private final Map<IAEItemStack, Long> toDeliver = new LinkedHashMap<>();

    private final Map<IAEItemStack, Calculation> calculating = new HashMap<>();
    private final Map<IAEItemStack, Long> retryAt = new HashMap<>();
    private final Map<IAEItemStack, Long> unneededSince = new HashMap<>();

    private List<RemotePattern> exported = Collections.emptyList();
    private int exportedSignature = 0;
    private IInterfaceHost target;
    private IGrid remoteGrid;
    /** Network B our running links are registered with (links must be re-registered after a reload). */
    private IGrid linkedGrid;
    private boolean removed;
    private String stateKey = "aegraph.remote.state.offline";
    private String stateArg = "";
    private String problemKey;
    private String problemArg = "";

    public PartRemoteBus(ItemStack is) {
        super(is);
        ownSource = new MachineSource(this);
    }

    // --- connection to the other network -------------------------------------------------------------------

    private IInterfaceHost findTarget() {
        final TileEntity self = getHost() == null ? null : getHost().getTile();
        if (self == null || self.getWorldObj() == null) return null;
        final ForgeDirection side = getSide();
        final TileEntity te = self.getWorldObj().getTileEntity(self.xCoord + side.offsetX,
                self.yCoord + side.offsetY, self.zCoord + side.offsetZ);
        if (te instanceof IInterfaceHost) return (IInterfaceHost) te;
        if (te instanceof IPartHost) {
            final IPart part = ((IPartHost) te).getPart(side.getOpposite());
            if (part instanceof IInterfaceHost) return (IInterfaceHost) part;
        }
        return null;
    }

    private IGrid ownGrid() {
        try {
            return getProxy().getGrid();
        } catch (GridAccessException e) {
            return null;
        }
    }

    private static IGrid gridOf(IInterfaceHost host) {
        final IGridNode node = host == null ? null : host.getActionableNode();
        return node == null ? null : node.getGrid();
    }

    /** Re-reads the facing interface; tells network A when the set of offered crafts changed. */
    private void refresh() {
        target = findTarget();
        final IGrid own = ownGrid();
        IGrid remote = gridOf(target);
        final List<RemotePattern> now = new ArrayList<>();
        if (removed || own == null || !getProxy().isActive()) {
            remote = null;
            setState("aegraph.remote.state.offline", "");
        } else if (target == null) {
            remote = null;
            setState("aegraph.remote.state.noInterface", "");
        } else if (remote == own) {
            remote = null;
            setState("aegraph.remote.state.sameGrid", "");
        } else if (remote == null || !target.getActionableNode().isActive()) {
            remote = null;
            setState("aegraph.remote.state.remoteOffline", "");
        } else {
            final TileEntity t = target.getTileEntity();
            setState("aegraph.remote.state.ok",
                    t == null ? "?" : t.xCoord + " " + t.yCoord + " " + t.zCoord);
            collectExports(target.getInterfaceDuality(), now);
        }
        remoteGrid = remote;
        final int signature = now.hashCode() * 31 + (exclusive ? 1 : 0);
        if (signature != exportedSignature || now.size() != exported.size()) {
            exported = now;
            exportedSignature = signature;
            if (own != null) own.postEvent(new MENetworkCraftingPatternChange(this, getProxy().getNode()));
        }
    }

    /**
     * Every output of every pattern in the interface, once per item. An item that is the main output of some
     * pattern takes its per-run amount from that pattern, otherwise from the first pattern that makes it.
     */
    private void collectExports(DualityInterface duality, List<RemotePattern> out) {
        final List<ICraftingPatternDetails> patterns = duality == null ? null : duality.craftingList;
        if (patterns == null) return;
        final TileEntity self = getHost().getTile();
        final int dim = self.getWorldObj().provider.dimensionId;
        final Map<IAEItemStack, IAEItemStack> items = new LinkedHashMap<>();
        for (final boolean mainOnly : new boolean[] { true, false }) {
            for (final ICraftingPatternDetails details : patterns) {
                final List<IAEItemStack> outputs = outputs(details);
                for (int k = 0; k < outputs.size() && (!mainOnly || k == 0); k++) {
                    items.putIfAbsent(outputs.get(k).copy().setStackSize(1), outputs.get(k));
                }
            }
        }
        for (final IAEItemStack item : items.values()) {
            out.add(new RemotePattern(dim, self.xCoord, self.yCoord, self.zCoord, getSide().ordinal(), item,
                    exclusive));
        }
    }

    /** Outputs in encoded order, one entry per item with the amount one run makes of it. */
    static List<IAEItemStack> outputs(ICraftingPatternDetails details) {
        final List<IAEItemStack> list = new ArrayList<>();
        final IAEItemStack[] outputs = details.getOutputs();
        if (outputs == null) return list;
        outer: for (final IAEItemStack s : outputs) {
            if (s == null || s.getStackSize() <= 0) continue;
            for (final IAEItemStack have : list) {
                if (have.isSameType(s)) {
                    have.incStackSize(s.getStackSize());
                    continue outer;
                }
            }
            list.add(s.copy());
        }
        return list;
    }

    private void setState(String key, String arg) {
        stateKey = key;
        stateArg = arg;
    }

    // --- network A: crafting provider ----------------------------------------------------------------------

    @Override
    public void provideCrafting(ICraftingProviderHelper craftingTracker) {
        if (!getProxy().isActive()) return;
        for (final RemotePattern pattern : exported) craftingTracker.addCraftingOption(this, pattern);
    }

    /** AE2's CPU cannot push a pattern without inputs; {@link RemoteCrafting} hands the runs to {@link #accept}. */
    @Override
    public boolean pushPattern(ICraftingPatternDetails patternDetails, net.minecraft.inventory.InventoryCrafting table) {
        return false;
    }

    @Override
    public boolean isBusy() {
        return false;
    }

    @Override
    public ItemStack getCrafterIcon() {
        return getItemStack().copy();
    }

    /**
     * A CPU of network A runs {@code runs} times one of our patterns. False: the bus can't take it now (no
     * connection to network B), the CPU keeps the task and asks again.
     */
    boolean accept(RemotePattern pattern, long runs) {
        if (removed || remoteGrid == null || !exported.contains(pattern) || runs <= 0) return false;
        final IAEItemStack key = pattern.output().copy().setStackSize(1);
        queued.merge(key, pattern.output().getStackSize() * runs, Long::sum);
        unneededSince.remove(key);
        retryAt.remove(key);
        saveChanges();
        wake();
        return true;
    }

    /** Whether network A still waits for this item from us. */
    boolean owes(IAEItemStack item) {
        final IAEItemStack key = item.copy().setStackSize(1);
        if (queued.containsKey(key) || toDeliver.containsKey(key)) return true;
        for (final Request r : running) {
            if (r.item.isSameType(key)) return true;
        }
        return false;
    }

    IGrid getOwnGrid() {
        return ownGrid();
    }

    private void wake() {
        try {
            getProxy().getTick().alertDevice(getProxy().getNode());
        } catch (GridAccessException ignored) {
            // picked up on the next regular tick
        }
    }

    // --- ticking -------------------------------------------------------------------------------------------

    @Override
    public TickingRequest getTickingRequest(IGridNode node) {
        return new TickingRequest(1, 20, false, true);
    }

    @Override
    public TickRateModulation tickingRequest(IGridNode node, int ticksSinceLastCall) {
        LIVE.add(this);
        refresh();
        final IGrid own = ownGrid();
        if (remoteGrid != null && remoteGrid != linkedGrid) {
            registerLinks(remoteGrid);
            linkedGrid = remoteGrid;
        }
        if (own != null) deliver(own);
        final long now = worldTime();
        if (own != null) dropUnneeded(own, now);
        if (remoteGrid != null) startJobs(remoteGrid, now);
        if (!toDeliver.isEmpty() || !calculating.isEmpty()) return TickRateModulation.URGENT;
        if (!queued.isEmpty()) return TickRateModulation.FASTER;
        return TickRateModulation.SLOWER;
    }

    private long worldTime() {
        final TileEntity self = getHost() == null ? null : getHost().getTile();
        return self == null || self.getWorldObj() == null ? 0 : self.getWorldObj().getTotalWorldTime();
    }

    private void registerLinks(IGrid remote) {
        final ICraftingGrid crafting = remote.getCache(ICraftingGrid.class);
        if (!(crafting instanceof CraftingGridCache)) return;
        for (final Request r : running) {
            if (r.link instanceof CraftingLink) ((CraftingGridCache) crafting).addLink((CraftingLink) r.link);
        }
    }

    /** Items from network B into network A's storage, where A's waiting CPU takes them first. */
    private void deliver(IGrid own) {
        if (toDeliver.isEmpty()) return;
        final IStorageGrid storage = own.getCache(IStorageGrid.class);
        if (storage == null) return;
        boolean changed = false;
        final Iterator<Map.Entry<IAEItemStack, Long>> it = toDeliver.entrySet().iterator();
        while (it.hasNext()) {
            final Map.Entry<IAEItemStack, Long> e = it.next();
            final IAEItemStack left = storage.getItemInventory()
                    .injectItems(e.getKey().copy().setStackSize(e.getValue()), Actionable.MODULATE, ownSource);
            final long rest = left == null ? 0 : left.getStackSize();
            if (rest == e.getValue()) continue;
            changed = true;
            if (rest <= 0) it.remove();
            else e.setValue(rest);
        }
        if (changed) saveChanges();
    }

    /** Network A no longer waits for an item (its job was cancelled): stop crafting it in network B. */
    private void dropUnneeded(IGrid own, long now) {
        final ICraftingGrid crafting = own.getCache(ICraftingGrid.class);
        final java.util.Set<IAEItemStack> items = new java.util.HashSet<>(queued.keySet());
        for (final Request r : running) items.add(r.item);
        for (final IAEItemStack item : items) {
            if (crafting.isRequesting(item)) {
                unneededSince.remove(item);
                continue;
            }
            final Long since = unneededSince.get(item);
            if (since == null) {
                unneededSince.put(item, now);
                continue;
            }
            if (now - since < CANCEL_GRACE_TICKS) continue;
            unneededSince.remove(item);
            final Long dropped = queued.remove(item);
            final Calculation calc = calculating.remove(item);
            if (calc != null) calc.future.cancel(true);
            long cancelled = 0;
            for (final Iterator<Request> it = running.iterator(); it.hasNext();) {
                final Request r = it.next();
                if (!r.item.isSameType(item)) continue;
                it.remove();
                cancelled += r.amount - r.delivered;
                r.link.cancel();
            }
            AEGraphMod.LOG.info("Remote crafting bus: network no longer needs {}, dropped {} queued, cancelled {} in "
                    + "the other network", name(item), dropped == null ? 0 : dropped, cancelled);
            saveChanges();
        }
    }

    /** Hands queued amounts to network B: calculate there, then start on a free CPU of network B. */
    private void startJobs(IGrid remote, long now) {
        if (queued.isEmpty()) return;
        final ICraftingGrid crafting = remote.getCache(ICraftingGrid.class);
        final World world = getHost().getTile().getWorldObj();
        for (final Map.Entry<IAEItemStack, Long> e : new ArrayList<>(queued.entrySet())) {
            final IAEItemStack item = e.getKey();
            final long amount = e.getValue();
            if (amount <= 0) {
                queued.remove(item);
                continue;
            }
            final Calculation calc = calculating.get(item);
            if (calc == null) {
                final Long retry = retryAt.get(item);
                if (retry != null && now < retry) continue;
                try {
                    final Future<ICraftingJob> future = crafting.beginCraftingJob(world, remote, remoteSource,
                            item.copy().setStackSize(amount), null);
                    if (future != null) calculating.put(item, new Calculation(future, amount));
                } catch (Throwable t) {
                    AEGraphMod.LOG.warn("Remote crafting bus: could not start a crafting calculation for " + name(item),
                            t);
                    problem("aegraph.remote.problem.failed", name(item));
                    retryAt.put(item, now + RETRY_TICKS);
                }
                continue;
            }
            if (!calc.future.isDone()) continue;
            calculating.remove(item);
            ICraftingJob job = null;
            try {
                if (!calc.future.isCancelled()) job = calc.future.get();
            } catch (Throwable t) {
                AEGraphMod.LOG.warn("Remote crafting bus: crafting calculation failed for " + name(item), t);
            }
            if (job == null) {
                problem("aegraph.remote.problem.failed", name(item));
                retryAt.put(item, now + RETRY_TICKS);
                continue;
            }
            if (job.isSimulation()) {
                problem("aegraph.remote.problem.missing", calc.amount + " x " + name(item));
                retryAt.put(item, now + RETRY_TICKS);
                continue;
            }
            final ICraftingCPU cpu = freeCpu(crafting, job);
            final ICraftingLink link = cpu == null ? null
                    : crafting.submitJob(job, requester, cpu, false, remoteSource);
            if (link == null) {
                problem("aegraph.remote.problem.noCpu", calc.amount + " x " + name(item));
                retryAt.put(item, now + RETRY_TICKS);
                continue;
            }
            running.add(new Request(link, item, calc.amount));
            final long rest = queued.getOrDefault(item, 0L) - calc.amount;
            if (rest > 0) queued.put(item, rest);
            else queued.remove(item);
            retryAt.remove(item);
            problemKey = null;
            saveChanges();
        }
    }

    /**
     * An idle CPU of network B (a co-processor CPU first when the job has a graph plan). Never a busy one: AE2 would
     * merge the job into the running one and its results would not come to this bus.
     */
    private static ICraftingCPU freeCpu(ICraftingGrid crafting, ICraftingJob job) {
        ICraftingCPU best = null;
        boolean bestGraph = false;
        for (final ICraftingCPU cpu : crafting.getCpus()) {
            if (cpu.isBusy() || cpu.getAvailableStorage() < job.getByteTotal()) continue;
            if (cpu instanceof CraftingCPUCluster) {
                final CraftingCPUCluster cluster = (CraftingCPUCluster) cpu;
                if (!cluster.isActive() || cluster.getCraftingAllowMode() == CraftingAllow.ONLY_PLAYER) continue;
            }
            if (!job.supportsCPUCluster(cpu)) continue;
            final boolean graph = GraphCpus.isGraphCpu(cpu);
            if (best == null || graph && !bestGraph
                    || graph == bestGraph && cpu.getCoProcessors() > best.getCoProcessors()) {
                best = cpu;
                bestGraph = graph;
            }
        }
        return best;
    }

    private void problem(String key, String arg) {
        problemKey = key;
        problemArg = arg;
    }

    private static String name(IAEItemStack item) {
        try {
            return item.getItemStack().getDisplayName();
        } catch (Throwable t) {
            return String.valueOf(item);
        }
    }

    // --- network B: crafting requester ---------------------------------------------------------------------

    /**
     * Stands for the bus in network B. Its node is the interface's: network B keeps a job's link alive only while
     * the requester is a machine of network B.
     */
    private final class Requester implements ICraftingRequester {

        @Override
        public IGridNode getActionableNode() {
            if (removed) return null;
            final IInterfaceHost host = target != null ? target : findTarget();
            return host == null ? null : host.getActionableNode();
        }

        @Override
        public IGridNode getGridNode(ForgeDirection dir) {
            return getActionableNode();
        }

        @Override
        public AECableType getCableConnectionType(ForgeDirection dir) {
            return AECableType.NONE;
        }

        @Override
        public void securityBreak() {}

        @Override
        public ImmutableSet<ICraftingLink> getRequestedJobs() {
            final ImmutableSet.Builder<ICraftingLink> links = ImmutableSet.builder();
            for (final Request r : running) links.add(r.link);
            return links.build();
        }

        @Override
        public IAEItemStack injectCraftedItems(ICraftingLink link, IAEItemStack items, Actionable mode) {
            if (items == null || removed) return items;
            if (mode == Actionable.MODULATE) {
                toDeliver.merge(items.copy().setStackSize(1), items.getStackSize(), Long::sum);
                for (final Request r : running) {
                    if (r.link == link) r.delivered += items.getStackSize();
                }
                saveChanges();
                wake();
            }
            return null;
        }

        @Override
        public void jobStateChange(ICraftingLink link) {
            for (final Iterator<Request> it = running.iterator(); it.hasNext();) {
                final Request r = it.next();
                if (r.link != link) continue;
                it.remove();
                final long rest = r.amount - r.delivered;
                if (link.isCanceled() && rest > 0) {
                    // Cancelled inside network B (or it lost the CPU): ask again for what is still owed.
                    queued.merge(r.item, rest, Long::sum);
                    AEGraphMod.LOG.info("Remote crafting bus: job for {} x {} was cancelled in the other network, "
                            + "requesting it again", rest, name(r.item));
                }
                saveChanges();
            }
        }
    }

    // --- GUI -----------------------------------------------------------------------------------------------

    public boolean isExclusive() {
        return exclusive;
    }

    public void setExclusive(boolean exclusive) {
        if (this.exclusive == exclusive) return;
        this.exclusive = exclusive;
        saveChanges();
        refresh();
    }

    /** State for the GUI. */
    public NBTTagCompound describe() {
        refresh();
        final NBTTagCompound tag = new NBTTagCompound();
        final TileEntity self = getHost().getTile();
        tag.setInteger("x", self.xCoord);
        tag.setInteger("y", self.yCoord);
        tag.setInteger("z", self.zCoord);
        tag.setByte("side", (byte) getSide().ordinal());
        tag.setBoolean("exclusive", exclusive);
        tag.setString("state", stateKey);
        tag.setString("stateArg", stateArg);
        if (problemKey != null) {
            tag.setString("problem", problemKey);
            tag.setString("problemArg", problemArg);
        }
        final NBTTagList exports = new NBTTagList();
        for (final RemotePattern p : exported) exports.appendTag(stackTag(p.output(), p.output().getStackSize()));
        tag.setTag("exports", exports);
        tag.setTag("queued", amounts(queued));
        final NBTTagList jobs = new NBTTagList();
        for (final Request r : running) {
            final NBTTagCompound t = stackTag(r.item, r.amount);
            t.setLong("delivered", r.delivered);
            jobs.appendTag(t);
        }
        tag.setTag("running", jobs);
        tag.setTag("deliver", amounts(toDeliver));
        return tag;
    }

    @Override
    public boolean onPartActivate(EntityPlayer player, Vec3 pos) {
        if (player.isSneaking()) return false;
        if (getHost().getTile().getWorldObj().isRemote) return true;
        if (player instanceof EntityPlayerMP) AEGraphNetwork.sendRemoteBus((EntityPlayerMP) player, describe());
        return true;
    }

    // --- persistence ---------------------------------------------------------------------------------------

    @Override
    public void readFromNBT(NBTTagCompound data) {
        super.readFromNBT(data);
        exclusive = data.getBoolean("exclusive");
        readAmounts(data.getTagList("queued", 10), queued);
        readAmounts(data.getTagList("deliver", 10), toDeliver);
        running.clear();
        final NBTTagList jobs = data.getTagList("running", 10);
        for (int k = 0; k < jobs.tagCount(); k++) {
            final NBTTagCompound t = jobs.getCompoundTagAt(k);
            final IAEItemStack item = readStack(t);
            if (item == null) continue;
            try {
                final ICraftingLink link = AEApi.instance().storage().loadCraftingLink(t.getCompoundTag("link"),
                        requester);
                final Request r = new Request(link, item, t.getLong("amount"));
                r.delivered = t.getLong("delivered");
                running.add(r);
            } catch (Throwable e) {
                // Lost link: ask network B again for what it still owed.
                final long rest = t.getLong("amount") - t.getLong("delivered");
                if (rest > 0) queued.merge(item, rest, Long::sum);
            }
        }
        linkedGrid = null;
    }

    @Override
    public void writeToNBT(NBTTagCompound data) {
        super.writeToNBT(data);
        data.setBoolean("exclusive", exclusive);
        data.setTag("queued", amounts(queued));
        data.setTag("deliver", amounts(toDeliver));
        final NBTTagList jobs = new NBTTagList();
        for (final Request r : running) {
            final NBTTagCompound t = stackTag(r.item, r.amount);
            t.setLong("delivered", r.delivered);
            final NBTTagCompound link = new NBTTagCompound();
            r.link.writeToNBT(link);
            t.setTag("link", link);
            jobs.appendTag(t);
        }
        data.setTag("running", jobs);
    }

    private static NBTTagCompound stackTag(IAEItemStack item, long amount) {
        final NBTTagCompound t = new NBTTagCompound();
        final ItemStack stack = item.getItemStack();
        stack.stackSize = 1;
        t.setTag("item", stack.writeToNBT(new NBTTagCompound()));
        t.setLong("amount", amount);
        return t;
    }

    private static IAEItemStack readStack(NBTTagCompound t) {
        final ItemStack stack = ItemStack.loadItemStackFromNBT(t.getCompoundTag("item"));
        final IAEItemStack ae = stack == null ? null : AEItemStack.create(stack);
        return ae == null ? null : ae.setStackSize(1);
    }

    private static NBTTagList amounts(Map<IAEItemStack, Long> map) {
        final NBTTagList list = new NBTTagList();
        for (final Map.Entry<IAEItemStack, Long> e : map.entrySet()) list.appendTag(stackTag(e.getKey(), e.getValue()));
        return list;
    }

    private static void readAmounts(NBTTagList list, Map<IAEItemStack, Long> into) {
        into.clear();
        for (int k = 0; k < list.tagCount(); k++) {
            final NBTTagCompound t = list.getCompoundTagAt(k);
            final IAEItemStack item = readStack(t);
            if (item != null && t.getLong("amount") > 0) into.merge(item, t.getLong("amount"), Long::sum);
        }
    }

    @Override
    public void addToWorld() {
        super.addToWorld();
        removed = false;
        if (getHost() != null && getHost().getTile() != null && getHost().getTile().getWorldObj() != null
                && !getHost().getTile().getWorldObj().isRemote) {
            LIVE.add(this);
        }
    }

    @Override
    public void removeFromWorld() {
        removed = true;
        LIVE.remove(this);
        for (final Calculation calc : calculating.values()) calc.future.cancel(true);
        calculating.clear();
        super.removeFromWorld();
    }

    /** Broken: what network B already delivered and network A did not take drops as items. */
    @Override
    public void getDrops(List<ItemStack> drops, boolean wrenched) {
        int stacks = 0;
        for (final Map.Entry<IAEItemStack, Long> e : toDeliver.entrySet()) {
            long left = e.getValue();
            final ItemStack proto = e.getKey().getItemStack();
            final int max = Math.max(1, proto.getMaxStackSize());
            while (left > 0 && stacks < 1024) {
                final ItemStack drop = proto.copy();
                drop.stackSize = (int) Math.min(max, left);
                left -= drop.stackSize;
                drops.add(drop);
                stacks++;
            }
        }
        toDeliver.clear();
    }

    @Override
    public void onNeighborChanged() {
        wake();
    }

    // --- shape and rendering (like AE2's storage bus) ------------------------------------------------------

    @Override
    public void getBoxes(IPartCollisionHelper bch) {
        bch.addBox(3, 3, 15, 13, 13, 16);
        bch.addBox(2, 2, 14, 14, 14, 15);
        bch.addBox(5, 5, 12, 11, 11, 14);
    }

    @Override
    public int cableConnectionRenderTo() {
        return 4;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void renderInventory(IPartRenderHelper rh, RenderBlocks renderer) {
        textures(rh, false);
        rh.setBounds(3, 3, 15, 13, 13, 16);
        rh.renderInventoryBox(renderer);
        rh.setBounds(2, 2, 14, 14, 14, 15);
        rh.renderInventoryBox(renderer);
        rh.setBounds(5, 5, 12, 11, 11, 14);
        rh.renderInventoryBox(renderer);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void renderStatic(int x, int y, int z, IPartRenderHelper rh, RenderBlocks renderer) {
        setRenderCache(rh.useSimplifiedRendering(x, y, z, this, getRenderCache()));
        textures(rh, false);
        rh.setBounds(3, 3, 15, 13, 13, 16);
        rh.renderBlock(x, y, z, renderer);
        rh.setBounds(2, 2, 14, 14, 14, 15);
        rh.renderBlock(x, y, z, renderer);
        rh.setBounds(5, 5, 12, 11, 11, 13);
        rh.renderBlock(x, y, z, renderer);
        textures(rh, true);
        rh.setBounds(5, 5, 13, 11, 11, 14);
        rh.renderBlock(x, y, z, renderer);
        renderLights(x, y, z, rh, renderer);
    }

    @SideOnly(Side.CLIENT)
    private void textures(IPartRenderHelper rh, boolean status) {
        final net.minecraft.util.IIcon sides = status ? CableBusTextures.PartMonitorSidesStatus.getIcon()
                : CableBusTextures.PartStorageSides.getIcon();
        final net.minecraft.util.IIcon back = status ? CableBusTextures.PartMonitorBack.getIcon()
                : CableBusTextures.PartStorageBack.getIcon();
        rh.setTexture(sides, sides, back, getItemStack().getIconIndex(), sides, sides);
    }
}
