package com.grihail.aegraph.remote;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.world.World;

import com.grihail.aegraph.AEGraphMod;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import appeng.util.item.AEItemStack;

/**
 * What the main network sees for one craft of the other network: "nothing -> output", offered by one
 * {@link PartRemoteBus}. A run of it is not pushed into a machine: the bus asks the other network to craft the
 * output there, with its own CPU, storage and calculator (see {@link RemoteCrafting#pushRemoteTasks}).
 * <p>
 * Identity is the bus position plus the output, so a CPU that saved this pattern in a job finds the bus again
 * after a reload. The exclusive flag is not part of the identity.
 */
public final class RemotePattern implements ICraftingPatternDetails {

    private static final IAEItemStack[] NONE = new IAEItemStack[0];

    final int dim;
    final int x;
    final int y;
    final int z;
    final int side;
    /** Per run; stack size = how many one run delivers. */
    private final IAEItemStack output;
    private final IAEItemStack[] outputs;
    private final boolean exclusive;
    private final ItemStack pattern;
    private int priority;

    RemotePattern(int dim, int x, int y, int z, int side, IAEItemStack output, boolean exclusive) {
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.side = side;
        this.output = output.copy();
        this.outputs = new IAEItemStack[] { this.output };
        this.exclusive = exclusive;
        this.pattern = new ItemStack(AEGraphMod.remotePattern);
        this.pattern.setTagCompound(toNBT());
        // Exclusive: ahead of every other pattern for the item (AE2's calculator tries patterns by priority).
        // Otherwise behind them. The low bits keep patterns of several buses apart in AE2's sorted sets.
        final int spread = hashCode() & 0xFFFF;
        this.priority = exclusive ? 1_000_000 + spread : -1_000_000 - spread;
    }

    /** Reads the pattern item a CPU saved with its job; null if the tag is broken. */
    static RemotePattern fromNBT(NBTTagCompound tag) {
        if (tag == null || !tag.hasKey("out")) return null;
        final ItemStack out = ItemStack.loadItemStackFromNBT(tag.getCompoundTag("out"));
        if (out == null) return null;
        final IAEItemStack stack = AEItemStack.create(out);
        if (stack == null) return null;
        stack.setStackSize(Math.max(1, tag.getLong("amount")));
        return new RemotePattern(tag.getInteger("dim"), tag.getInteger("x"), tag.getInteger("y"), tag.getInteger("z"),
                tag.getByte("side"), stack, tag.getBoolean("exclusive"));
    }

    private NBTTagCompound toNBT() {
        final NBTTagCompound tag = new NBTTagCompound();
        final ItemStack out = output.getItemStack();
        out.stackSize = 1;
        tag.setTag("out", out.writeToNBT(new NBTTagCompound()));
        tag.setLong("amount", output.getStackSize());
        tag.setInteger("dim", dim);
        tag.setInteger("x", x);
        tag.setInteger("y", y);
        tag.setInteger("z", z);
        tag.setByte("side", (byte) side);
        tag.setBoolean("exclusive", exclusive);
        return tag;
    }

    public IAEItemStack output() {
        return output;
    }

    public boolean isExclusive() {
        return exclusive;
    }

    boolean isAt(int dim, int x, int y, int z, int side) {
        return this.dim == dim && this.x == x && this.y == y && this.z == z && this.side == side;
    }

    @Override
    public ItemStack getPattern() {
        return pattern;
    }

    @Override
    public boolean isValidItemForSlot(int slotIndex, ItemStack itemStack, World world) {
        return false;
    }

    @Override
    public boolean isCraftable() {
        return false;
    }

    @Override
    public IAEItemStack[] getInputs() {
        return NONE;
    }

    @Override
    public IAEItemStack[] getCondensedInputs() {
        return NONE;
    }

    @Override
    public IAEItemStack[] getCondensedOutputs() {
        return outputs;
    }

    @Override
    public IAEItemStack[] getOutputs() {
        return outputs;
    }

    @Override
    public boolean canSubstitute() {
        return false;
    }

    @Override
    public boolean canBeSubstitute() {
        return false;
    }

    @Override
    public ItemStack getOutput(InventoryCrafting craftingInv, World world) {
        return null;
    }

    @Override
    public int getPriority() {
        return priority;
    }

    @Override
    public void setPriority(int priority) {
        this.priority = priority;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof RemotePattern)) return false;
        final RemotePattern other = (RemotePattern) o;
        return isAt(other.dim, other.x, other.y, other.z, other.side)
                && output.getStackSize() == other.output.getStackSize() && output.isSameType(other.output);
    }

    @Override
    public int hashCode() {
        int h = dim;
        h = h * 31 + x;
        h = h * 31 + y;
        h = h * 31 + z;
        h = h * 31 + side;
        h = h * 31 + output.hashCode();
        return h * 31 + Long.hashCode(output.getStackSize());
    }

    @Override
    public String toString() {
        return "RemotePattern[" + output + " @ " + dim + ":" + x + "," + y + "," + z + "/" + side + "]";
    }
}
