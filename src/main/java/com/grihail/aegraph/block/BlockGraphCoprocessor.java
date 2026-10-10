package com.grihail.aegraph.block;

import java.util.List;

import net.minecraft.client.renderer.texture.IIconRegister;
import net.minecraft.creativetab.CreativeTabs;
import net.minecraft.entity.player.EntityPlayer;
import net.minecraft.entity.player.EntityPlayerMP;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.IIcon;
import net.minecraft.world.World;
import net.minecraftforge.common.util.ForgeDirection;

import com.grihail.aegraph.ae.GraphCpus;

import appeng.block.crafting.BlockCraftingUnit;
import appeng.client.texture.FlippableIcon;
import appeng.core.sync.GuiBridge;
import appeng.util.Platform;
import cpw.mods.fml.relauncher.Side;
import cpw.mods.fml.relauncher.SideOnly;

/**
 * Graph Co-Processor. Placed into a crafting CPU multiblock it switches that CPU to graph planning; right-click
 * shows the plan graph (sneak + right-click: AE2's CPU status).
 * <p>
 * It is an AE2 crafting unit, so AE2's own CPU renderer draws it: frame and seams joined with the neighbouring CPU
 * blocks once the multiblock is formed, glowing symbol while powered - like the accelerators and storages.
 */
public class BlockGraphCoprocessor extends BlockCraftingUnit {

    /** Metadata bit set by TileCraftingTile.updateMeta. */
    private static final int FLAG_FORMED = 8;

    @SideOnly(Side.CLIENT)
    private IIcon iconUnformed;
    /** Symbol AE2 draws (lit when powered) on top of its formed-CPU base. */
    @SideOnly(Side.CLIENT)
    private IIcon iconFit;

    public BlockGraphCoprocessor() {
        setTileEntity(TileGraphCoprocessor.class);
        setBlockName("aegraph.coprocessor");
        setBlockTextureName("aegraph:coprocessor");
        // AE2's own tab exists once AE2 has pre-initialized (we load after it).
        setCreativeTab(appeng.core.CreativeTab.instance != null ? appeng.core.CreativeTab.instance
                : CreativeTabs.tabRedstone);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void registerBlockIcons(IIconRegister register) {
        iconUnformed = register.registerIcon("aegraph:coprocessor");
        iconFit = register.registerIcon("aegraph:coprocessor_fit");
        blockIcon = iconUnformed;
        final FlippableIcon icon = new FlippableIcon(iconUnformed);
        getRendererInstance().updateIcons(icon, icon, icon, icon, icon, icon);
    }

    @Override
    @SideOnly(Side.CLIENT)
    public IIcon getIcon(int direction, int metadata) {
        return (metadata & FLAG_FORMED) != 0 ? iconFit : iconUnformed;
    }

    /** Called by AE2 after its own wrench and memory card handling. */
    @Override
    public boolean onActivated(World world, int x, int y, int z, EntityPlayer player, int side, float hitX,
            float hitY, float hitZ) {
        final TileGraphCoprocessor te = getTileEntity(world, x, y, z);
        if (player.isSneaking()) {
            if (te == null || player.getHeldItem() != null || !te.isFormed() || !te.isActive()) return false;
            if (!world.isRemote) {
                Platform.openGUI(player, te, ForgeDirection.getOrientation(side), GuiBridge.GUI_CRAFTING_CPU);
            }
            return true;
        }
        if (!world.isRemote && te != null && player instanceof EntityPlayerMP) {
            GraphCpus.showGraph(te, (EntityPlayerMP) player);
        }
        return true;
    }

    @Override
    @SideOnly(Side.CLIENT)
    public void getCheckedSubBlocks(Item item, CreativeTabs tabs, List<ItemStack> itemStacks) {
        itemStacks.add(new ItemStack(this, 1, 0));
    }

    @Override
    public String getUnlocalizedName(ItemStack is) {
        return getUnlocalizedName();
    }

    @Override
    public int damageDropped(int meta) {
        return 0;
    }
}
