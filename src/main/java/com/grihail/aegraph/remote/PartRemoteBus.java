package com.grihail.aegraph.remote;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.minecraft.client.renderer.RenderBlocks;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.tileentity.TileEntity;
import net.minecraft.util.Vec3;
import net.minecraftforge.common.util.ForgeDirection;

import com.grihail.aegraph.AEGraphMod;
import com.grihail.aegraph.network.AEGraphNetwork;

import appeng.api.config.Actionable;
import appeng.api.networking.IGrid;
import appeng.api.networking.IGridNode;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.networking.crafting.ICraftingProvider;
import appeng.api.networking.crafting.ICraftingMedium;
import appeng.api.networking.crafting.ICraftingProviderHelper;
import appeng.api.networking.events.MENetworkCraftingPatternChange;
import appeng.api.networking.security.BaseActionSource;
import appeng.api.networking.security.MachineSource;
import appeng.api.networking.storage.IStorageGrid;
import appeng.api.networking.ticking.IGridTickable;
import appeng.api.networking.ticking.TickRateModulation;
import appeng.api.networking.ticking.TickingRequest;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartCollisionHelper;
import appeng.api.parts.IPartHost;
import appeng.api.parts.IPartRenderHelper;
import appeng.api.storage.IMEMonitor;
import appeng.api.storage.data.IAEItemStack;
import appeng.client.texture.CableBusTextures;
import appeng.helpers.DualityInterface;
import appeng.helpers.IInterfaceHost;
import appeng.me.GridAccessException;
import appeng.parts.PartBasicState;
import appeng.util.item.AEItemStack;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Remote Crafting Bus: sits on a cable of network A and faces an ME Interface of another, independent network B.
 * <p>
 * For network A it works like an ME Interface whose machines stand in network B. The patterns of the faced interface
 * show up in network A with their inputs and outputs ({@link RemotePattern}). Network A's CPU gathers or crafts the
 * inputs in its own plan and pushes a run into the bus; the bus hands it to the interface in network B, which
 * pushes it into its machine. What the machine makes comes back into network B's storage, and the bus brings what
 * network A's CPU waits for over to network A (a machine may also return its products straight into network A).
 * Network B shares nothing else: its storage, other patterns and CPUs stay its own.
 * <p>
 * Exclusive mode ("priority"): network A crafts these items only through this bus, its own patterns for them are
 * hidden from its calculators.
 */
public class PartRemoteBus extends PartBasicState implements ICraftingProvider, IGridTickable {

    /** Live buses on the server, for the CPU hooks. */
    static final java.util.Set<PartRemoteBus> LIVE = Collections
            .newSetFromMap(new java.util.WeakHashMap<PartRemoteBus, Boolean>());

    private final BaseActionSource ownSource;

    private boolean exclusive;
    /** Item (size 1) -> amount pushed runs still have to bring back from network B. */
    private final Map<IAEItemStack, Long> owed = new LinkedHashMap<>();
    /** Item (size 1) -> amount taken from network B that network A did not accept yet. */
    private final Map<IAEItemStack, Long> toDeliver = new LinkedHashMap<>();

    private List<RemotePattern> exported = Collections.emptyList();
    private int exportedSignature = 0;
    private IInterfaceHost target;
    private IGrid remoteGrid;
    private boolean removed;
    private String stateKey = "aegraph.remote.state.offline";
    private String stateArg = "";

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

    /** Re-reads the facing interface; tells network A when the set of offered patterns changed. */
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
            setState("aegraph.remote.state.ok", t == null ? "?" : t.xCoord + " " + t.yCoord + " " + t.zCoord);
            collectExports(target.getInterfaceDuality(), now);
        }
        remoteGrid = remote;
        final int signature = now.hashCode() * 31 + (exclusive ? 1 : 0);
        final boolean changed = signature != exportedSignature || now.size() != exported.size();
        // Always the fresh list: it holds the interface's current pattern objects.
        exported = now;
        exportedSignature = signature;
        if (changed && own != null) own.postEvent(new MENetworkCraftingPatternChange(this, getProxy().getNode()));
    }

    /** Every pattern of the faced interface, as it is. */
    private void collectExports(DualityInterface duality, List<RemotePattern> out) {
        if (duality == null) return;
        final TileEntity self = getHost().getTile();
        final int dim = self.getWorldObj().provider.dimensionId;
        duality.provideCrafting(new ICraftingProviderHelper() {

            @Override
            public void addCraftingOption(ICraftingMedium medium, ICraftingPatternDetails details) {
                final RemotePattern pattern = new RemotePattern(dim, self.xCoord, self.yCoord, self.zCoord,
                        getSide().ordinal(), details, exclusive);
                if (pattern.getCondensedOutputs().length > 0 && !out.contains(pattern)) out.add(pattern);
            }

            @Override
            public void setEmitable(IAEItemStack item) {}
        });
    }

    private void setState(String key, String arg) {
        stateKey = key;
        stateArg = arg;
    }

    // --- network A: crafting provider and medium -----------------------------------------------------------

    @Override
    public void provideCrafting(ICraftingProviderHelper craftingTracker) {
        if (!getProxy().isActive()) return;
        for (final RemotePattern pattern : exported) craftingTracker.addCraftingOption(this, pattern);
    }

    /** The faced interface's pattern a run of ours stands for (patterns restored from a saved job have none). */
    private ICraftingPatternDetails sourceOf(ICraftingPatternDetails details) {
        if (!(details instanceof RemotePattern)) return null;
        for (final RemotePattern p : exported) {
            if (p.equals(details)) return p.source;
        }
        return null;
    }

    /**
     * A CPU of network A pushes a run: the inputs go to the interface in network B, which puts them into its
     * machine. The outputs are owed until they come back.
     */
    @Override
    public boolean pushPattern(ICraftingPatternDetails patternDetails, InventoryCrafting table) {
        if (removed || remoteGrid == null || target == null) return false;
        final ICraftingPatternDetails source = sourceOf(patternDetails);
        final DualityInterface duality = target.getInterfaceDuality();
        if (source == null || duality == null || !duality.pushPattern(source, table)) return false;
        for (final IAEItemStack out : patternDetails.getCondensedOutputs()) {
            owed.merge(out.copy().setStackSize(1), out.getStackSize(), Long::sum);
        }
        saveChanges();
        wake();
        return true;
    }

    /** Busy like the faced interface (blocking mode, a machine that has not taken everything yet). */
    @Override
    public boolean isBusy() {
        if (target == null || remoteGrid == null) return true;
        final DualityInterface duality = target.getInterfaceDuality();
        return duality == null || duality.isBusy();
    }

    /** Whether network A still waits for this item from us. */
    boolean owes(IAEItemStack item) {
        final IAEItemStack key = item.copy().setStackSize(1);
        return owed.containsKey(key) || toDeliver.containsKey(key);
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
        if (own != null) {
            matchNeeds(own);
            if (remoteGrid != null) fetch(remoteGrid);
            deliver(own);
        }
        if (!toDeliver.isEmpty()) return TickRateModulation.URGENT;
        return owed.isEmpty() ? TickRateModulation.SLOWER : TickRateModulation.FASTER;
    }

    /**
     * Owes no more than network A's CPUs still wait for: a cancelled job, or products that reached network A
     * another way (a machine of network B returning them straight into network A), are not fetched.
     */
    private void matchNeeds(IGrid own) {
        boolean changed = false;
        for (final Iterator<Map.Entry<IAEItemStack, Long>> it = owed.entrySet().iterator(); it.hasNext();) {
            final Map.Entry<IAEItemStack, Long> e = it.next();
            final long waiting = RemoteCrafting.waitingAmount(own, e.getKey());
            if (waiting < 0) continue;
            final long need = Math.max(0, waiting - toDeliver.getOrDefault(e.getKey(), 0L));
            if (need >= e.getValue()) continue;
            changed = true;
            if (need <= 0) it.remove();
            else e.setValue(need);
        }
        if (changed) saveChanges();
    }

    /** What network B's machines made for us, out of network B's storage. */
    private void fetch(IGrid remote) {
        if (owed.isEmpty()) return;
        final IStorageGrid storage = remote.getCache(IStorageGrid.class);
        if (storage == null) return;
        final IMEMonitor<IAEItemStack> items = storage.getItemInventory();
        final BaseActionSource source = new MachineSource(target);
        boolean changed = false;
        for (final Iterator<Map.Entry<IAEItemStack, Long>> it = owed.entrySet().iterator(); it.hasNext();) {
            final Map.Entry<IAEItemStack, Long> e = it.next();
            final IAEItemStack got = items.extractItems(e.getKey().copy().setStackSize(e.getValue()),
                    Actionable.MODULATE, source);
            if (got == null || got.getStackSize() <= 0) continue;
            changed = true;
            toDeliver.merge(e.getKey(), got.getStackSize(), Long::sum);
            if (got.getStackSize() >= e.getValue()) it.remove();
            else e.setValue(e.getValue() - got.getStackSize());
        }
        if (changed) saveChanges();
    }

    /** Into network A's storage, where its waiting CPU takes them first. */
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
        final NBTTagList exports = new NBTTagList();
        for (final RemotePattern p : exported) {
            final NBTTagCompound t = stackTag(p.output(), p.output().getStackSize());
            final NBTTagList in = new NBTTagList();
            for (final IAEItemStack s : p.getCondensedInputs()) in.appendTag(stackTag(s, s.getStackSize()));
            t.setTag("in", in);
            exports.appendTag(t);
        }
        tag.setTag("exports", exports);
        tag.setTag("owed", amounts(owed));
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
        readAmounts(data.getTagList("owed", 10), owed);
        readAmounts(data.getTagList("deliver", 10), toDeliver);
    }

    @Override
    public void writeToNBT(NBTTagCompound data) {
        super.writeToNBT(data);
        data.setBoolean("exclusive", exclusive);
        data.setTag("owed", amounts(owed));
        data.setTag("deliver", amounts(toDeliver));
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
        super.removeFromWorld();
    }

    /** Broken: what was taken from network B and network A did not accept yet drops as items. */
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
