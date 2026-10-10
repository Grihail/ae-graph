package com.grihail.aegraph.remote;

import java.util.Arrays;

import net.minecraft.inventory.InventoryCrafting;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.world.World;

import com.grihail.aegraph.AEGraphMod;

import appeng.api.networking.crafting.ICraftingPatternDetails;
import appeng.api.storage.data.IAEItemStack;
import appeng.util.item.AEItemStack;

/**
 * What the main network sees for one pattern of the other network's ME Interface, offered by one
 * {@link PartRemoteBus}: the same inputs and outputs, as a processing pattern. A CPU of the main network gathers the
 * inputs and pushes a run into the bus like into any ME Interface; the bus hands it to the other network's
 * interface, which pushes it into its machine.
 * <p>
 * Identity is the bus position plus the inputs and outputs, so a CPU that saved this pattern in a job finds the bus
 * again after a reload. The exclusive flag is not part of the identity.
 */
public final class RemotePattern implements ICraftingPatternDetails {

    final int dim;
    final int x;
    final int y;
    final int z;
    final int side;
    /** Slot by slot, like the other network's pattern (null: empty slot). */
    private final IAEItemStack[] inputs;
    private final IAEItemStack[] outputs;
    private final IAEItemStack[] condensedInputs;
    private final IAEItemStack[] condensedOutputs;
    private final boolean exclusive;
    private final ItemStack pattern;
    private int priority;
    /** The other network's pattern this one stands for; null when restored from a saved job. */
    final transient ICraftingPatternDetails source;

    RemotePattern(int dim, int x, int y, int z, int side, ICraftingPatternDetails source, boolean exclusive) {
        this(dim, x, y, z, side, source.getInputs(), source.getOutputs(), exclusive, source);
    }

    private RemotePattern(int dim, int x, int y, int z, int side, IAEItemStack[] inputs, IAEItemStack[] outputs,
            boolean exclusive, ICraftingPatternDetails source) {
        this.dim = dim;
        this.x = x;
        this.y = y;
        this.z = z;
        this.side = side;
        this.inputs = copy(inputs);
        this.outputs = copy(outputs);
        this.condensedInputs = condense(this.inputs);
        this.condensedOutputs = condense(this.outputs);
        this.exclusive = exclusive;
        this.source = source;
        this.pattern = new ItemStack(AEGraphMod.remotePattern);
        this.pattern.setTagCompound(toNBT());
        // Exclusive: ahead of every other pattern for the item (AE2's calculator tries patterns by priority).
        // Otherwise behind them. The low bits keep patterns of several buses apart in AE2's sorted sets.
        final int spread = hashCode() & 0xFFFF;
        this.priority = exclusive ? 1_000_000 + spread : -1_000_000 - spread;
    }

    private static IAEItemStack[] copy(IAEItemStack[] stacks) {
        if (stacks == null) return new IAEItemStack[0];
        final IAEItemStack[] out = new IAEItemStack[stacks.length];
        for (int k = 0; k < stacks.length; k++) out[k] = stacks[k] == null ? null : stacks[k].copy();
        return out;
    }

    /** One entry per item, amounts summed, empty slots dropped. */
    private static IAEItemStack[] condense(IAEItemStack[] stacks) {
        final java.util.List<IAEItemStack> list = new java.util.ArrayList<>();
        outer: for (final IAEItemStack s : stacks) {
            if (s == null || s.getStackSize() <= 0) continue;
            for (final IAEItemStack have : list) {
                if (have.isSameType(s)) {
                    have.incStackSize(s.getStackSize());
                    continue outer;
                }
            }
            list.add(s.copy());
        }
        return list.toArray(new IAEItemStack[0]);
    }

    /** Reads the pattern item a CPU saved with its job; null if the tag is broken. */
    static RemotePattern fromNBT(NBTTagCompound tag) {
        if (tag == null || !tag.hasKey("out", 9)) return null;
        final IAEItemStack[] outputs = readStacks(tag.getTagList("out", 10));
        if (condense(outputs).length == 0) return null;
        return new RemotePattern(tag.getInteger("dim"), tag.getInteger("x"), tag.getInteger("y"), tag.getInteger("z"),
                tag.getByte("side"), readStacks(tag.getTagList("in", 10)), outputs, tag.getBoolean("exclusive"),
                null);
    }

    private NBTTagCompound toNBT() {
        final NBTTagCompound tag = new NBTTagCompound();
        tag.setTag("in", writeStacks(inputs));
        tag.setTag("out", writeStacks(outputs));
        tag.setInteger("dim", dim);
        tag.setInteger("x", x);
        tag.setInteger("y", y);
        tag.setInteger("z", z);
        tag.setByte("side", (byte) side);
        tag.setBoolean("exclusive", exclusive);
        return tag;
    }

    private static NBTTagList writeStacks(IAEItemStack[] stacks) {
        final NBTTagList list = new NBTTagList();
        for (final IAEItemStack s : stacks) {
            final NBTTagCompound t = new NBTTagCompound();
            if (s != null) {
                final ItemStack is = s.getItemStack();
                is.stackSize = 1;
                is.writeToNBT(t);
                t.setLong("amount", s.getStackSize());
            }
            list.appendTag(t);
        }
        return list;
    }

    private static IAEItemStack[] readStacks(NBTTagList list) {
        final IAEItemStack[] stacks = new IAEItemStack[list.tagCount()];
        for (int k = 0; k < stacks.length; k++) {
            final NBTTagCompound t = list.getCompoundTagAt(k);
            final ItemStack is = t.hasKey("id") ? ItemStack.loadItemStackFromNBT(t) : null;
            final IAEItemStack s = is == null ? null : AEItemStack.create(is);
            stacks[k] = s == null ? null : s.setStackSize(Math.max(1, t.getLong("amount")));
        }
        return stacks;
    }

    /** The first output (the one the pattern is named after). */
    public IAEItemStack output() {
        return condensedOutputs[0];
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
        return inputs;
    }

    @Override
    public IAEItemStack[] getCondensedInputs() {
        return condensedInputs;
    }

    @Override
    public IAEItemStack[] getCondensedOutputs() {
        return condensedOutputs;
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
        return isAt(other.dim, other.x, other.y, other.z, other.side) && same(inputs, other.inputs)
                && same(outputs, other.outputs);
    }

    private static boolean same(IAEItemStack[] a, IAEItemStack[] b) {
        if (a.length != b.length) return false;
        for (int k = 0; k < a.length; k++) {
            if (a[k] == null || b[k] == null) {
                if (a[k] != b[k]) return false;
            } else if (!a[k].isSameType(b[k]) || a[k].getStackSize() != b[k].getStackSize()) {
                return false;
            }
        }
        return true;
    }

    @Override
    public int hashCode() {
        int h = dim;
        h = h * 31 + x;
        h = h * 31 + y;
        h = h * 31 + z;
        h = h * 31 + side;
        for (final IAEItemStack s : inputs) h = h * 31 + (s == null ? 0 : s.hashCode() * 31 + (int) s.getStackSize());
        for (final IAEItemStack s : outputs) h = h * 31 + (s == null ? 0 : s.hashCode() * 31 + (int) s.getStackSize());
        return h;
    }

    @Override
    public String toString() {
        return "RemotePattern[" + Arrays.toString(condensedInputs) + " -> " + Arrays.toString(condensedOutputs) + " @ "
                + dim + ":" + x + "," + y + "," + z + "/" + side + "]";
    }
}
