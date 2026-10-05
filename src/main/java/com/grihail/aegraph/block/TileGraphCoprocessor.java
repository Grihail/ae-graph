package com.grihail.aegraph.block;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import com.grihail.aegraph.AEGraphConfig;
import com.grihail.aegraph.AEGraphMod;

import appeng.tile.TileEvent;
import appeng.tile.crafting.TileCraftingTile;
import appeng.tile.events.TileEventType;

/**
 * A crafting CPU block: AE2 forms it into the CPU multiblock like any crafting unit. A CPU that contains one
 * runs graph plans. The tile also keeps the last plan its CPU started, for the graph viewer.
 */
public class TileGraphCoprocessor extends TileCraftingTile {

    private NBTTagCompound lastPlan;

    @Override
    protected ItemStack getItemFromTile(final Object obj) {
        return AEGraphMod.coprocessor == null ? null : new ItemStack(AEGraphMod.coprocessor);
    }

    /**
     * The co-processor also works as a strong crafting accelerator. AE2 lets a CPU push only (accelerators + 1)
     * pattern runs per 3 ticks; graph plans often consist of many small runs (one byproduct each), so without
     * acceleration the CPU feeds a fast machine one item every few ticks.
     */
    @Override
    public boolean isAccelerator() {
        return AEGraphConfig.coprocessorAcceleration > 0;
    }

    @Override
    public int acceleratorValue() {
        return AEGraphConfig.coprocessorAcceleration;
    }

    public NBTTagCompound getLastPlan() {
        return lastPlan;
    }

    public void setLastPlan(NBTTagCompound plan) {
        this.lastPlan = plan;
        this.markDirty();
    }

    @TileEvent(TileEventType.WORLD_NBT_WRITE)
    public void writeToNBT_GraphCoprocessor(final NBTTagCompound data) {
        if (lastPlan != null) data.setTag("aegraphPlan", lastPlan);
    }

    @TileEvent(TileEventType.WORLD_NBT_READ)
    public void readFromNBT_GraphCoprocessor(final NBTTagCompound data) {
        lastPlan = data.hasKey("aegraphPlan") ? data.getCompoundTag("aegraphPlan") : null;
    }
}
