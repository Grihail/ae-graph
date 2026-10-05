package com.grihail.aegraph.item;

import java.util.List;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.StatCollector;

import com.grihail.aegraph.AEGraphMod;

import appeng.api.config.Upgrades;
import appeng.api.implementations.items.IUpgradeModule;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Fast Feed Card: an upgrade for the ME Interface (block and part). See {@link FastFeed} for what it does.
 */
public class ItemFastFeedCard extends Item implements IUpgradeModule {

    public ItemFastFeedCard() {
        setUnlocalizedName("aegraph.fastFeedCard");
        setTextureName("aegraph:fast_feed_card");
        setMaxStackSize(64);
        // AE2's own tab exists once AE2 has pre-initialized (we load after it).
        if (appeng.core.CreativeTab.instance != null) setCreativeTab(appeng.core.CreativeTab.instance);
    }

    @Override
    public Upgrades getType(ItemStack itemstack) {
        return FastFeed.type();
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
        for (final String line : StatCollector.translateToLocal("item.aegraph.fastFeedCard.tooltip").split("\\\\n")) {
            lines.add(line);
        }
        if (FastFeed.type() == null) {
            lines.add("§c" + AEGraphMod.MODID + ": upgrade type unavailable, see log");
        }
    }
}
