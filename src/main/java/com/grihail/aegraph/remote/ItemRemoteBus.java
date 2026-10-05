package com.grihail.aegraph.remote;

import java.util.List;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;
import net.minecraft.world.World;

import appeng.api.AEApi;
import appeng.api.parts.IPart;
import appeng.api.parts.IPartItem;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/** Item of the {@link PartRemoteBus}: placed on an ME cable like any AE2 bus. */
public class ItemRemoteBus extends Item implements IPartItem {

    public ItemRemoteBus() {
        setUnlocalizedName("aegraph.remoteBus");
        setTextureName("aegraph:remote_bus_front");
        setMaxStackSize(64);
        // AE2's own tab exists once AE2 has pre-initialized (we load after it).
        if (appeng.core.CreativeTab.instance != null) setCreativeTab(appeng.core.CreativeTab.instance);
    }

    @Override
    public IPart createPartFromItemStack(ItemStack is) {
        return new PartRemoteBus(is);
    }

    @Override
    public boolean onItemUse(ItemStack is, EntityPlayer player, World w, int x, int y, int z, int side, float hitX,
            float hitY, float hitZ) {
        return AEApi.instance().partHelper().placeBus(is, x, y, z, side, player, w);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerIcons(IIconRegister register) {
        itemIcon = register.registerIcon(getIconString());
    }

    @Override
    @SideOnly(Side.CLIENT)
    public int getSpriteNumber() {
        return 0; // the front icon lives on the block atlas, the part renderer draws it there
    }

    @Override
    @SideOnly(Side.CLIENT)
    @SuppressWarnings({ "rawtypes", "unchecked" })
    public void addInformation(ItemStack stack, EntityPlayer player, List lines, boolean advanced) {
        for (final String line : StatCollector.translateToLocal("item.aegraph.remoteBus.tooltip").split("\\\\n")) {
            lines.add(line);
        }
    }
}
