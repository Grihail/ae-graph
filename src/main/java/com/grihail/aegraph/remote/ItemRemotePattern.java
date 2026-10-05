package com.grihail.aegraph.remote;

import java.util.List;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import appeng.api.implementations.ICraftingPatternItem;
import appeng.api.networking.crafting.ICraftingPatternDetails;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Not obtainable: the item form of a {@link RemotePattern}. AE2 stores patterns of a running job as items (CPU NBT,
 * the crafting plan sent to the client) and turns them back into patterns through {@link ICraftingPatternItem}.
 */
public class ItemRemotePattern extends Item implements ICraftingPatternItem {

    public ItemRemotePattern() {
        setUnlocalizedName("aegraph.remotePattern");
        setTextureName("aegraph:remote_bus_front");
        setMaxStackSize(1);
    }

    @Override
    public ICraftingPatternDetails getPatternForItem(ItemStack is, World w) {
        return is == null ? null : RemotePattern.fromNBT(is.getTagCompound());
    }

    @Override
    public String getItemStackDisplayName(ItemStack stack) {
        final RemotePattern pattern = RemotePattern.fromNBT(stack.getTagCompound());
        if (pattern == null) return StatCollector.translateToLocal("item.aegraph.remotePattern.empty");
        return StatCollector.translateToLocalFormatted("item.aegraph.remotePattern.name",
                pattern.output().getItemStack().getDisplayName());
    }

    @Override
    @SideOnly(Side.CLIENT)
    @SuppressWarnings("rawtypes")
    public void getSubItems(Item item, net.minecraft.creativetab.CreativeTabs tab, List list) {
        // not obtainable, nothing to list
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerIcons(IIconRegister register) {
        itemIcon = register.registerIcon(getIconString());
    }

    @Override
    @SideOnly(Side.CLIENT)
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void addInformation(ItemStack stack, EntityPlayer player, List lines, boolean advanced) {
        final RemotePattern pattern = RemotePattern.fromNBT(stack.getTagCompound());
        if (pattern == null) return;
        lines.add(StatCollector.translateToLocalFormatted("aegraph.remote.patternInfo", pattern.x, pattern.y,
                pattern.z));
    }
}
