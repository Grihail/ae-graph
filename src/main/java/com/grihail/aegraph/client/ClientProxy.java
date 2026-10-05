package com.grihail.aegraph.client;

import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraftforge.client.MinecraftForgeClient;

import com.grihail.aegraph.AEGraphMod;

import appeng.client.render.BusRenderer;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Loader;
import cpw.mods.fml.common.eventhandler.SubscribeEvent;
import cpw.mods.fml.common.gameevent.TickEvent;

public class ClientProxy extends AEGraphMod.CommonProxy {

    /** Packets arrive on the network thread; GUIs are opened on the next client tick. */
    private volatile NBTTagCompound pending;
    private volatile NBTTagCompound pendingRemoteBus;

    @Override
    public void init() {
        FMLCommonHandler.instance().bus().register(this);
        // AE2 draws bus items with the part's own inventory renderer.
        MinecraftForgeClient.registerItemRenderer(AEGraphMod.remoteBus, BusRenderer.INSTANCE);
        hideFromNei(new ItemStack(AEGraphMod.remotePattern));
    }

    /** The remote pattern item only exists inside running jobs; NEI lists every registered item otherwise. */
    private static void hideFromNei(ItemStack stack) {
        if (!Loader.isModLoaded("NotEnoughItems")) return;
        try {
            Class.forName("codechicken.nei.api.API").getMethod("hideItem", ItemStack.class).invoke(null, stack);
        } catch (Throwable t) {
            AEGraphMod.LOG.warn("Could not hide {} in NEI", stack, t);
        }
    }

    @Override
    public void openGraph(NBTTagCompound snapshot) {
        pending = snapshot;
    }

    @Override
    public void openRemoteBus(NBTTagCompound state) {
        pendingRemoteBus = state;
    }

    @SubscribeEvent
    public void onClientTick(TickEvent.ClientTickEvent event) {
        if (event.phase != TickEvent.Phase.END) return;
        final NBTTagCompound snapshot = pending;
        if (snapshot != null) {
            pending = null;
            Minecraft.getMinecraft().displayGuiScreen(new GuiPlanGraph(snapshot));
        }
        final NBTTagCompound state = pendingRemoteBus;
        if (state != null) {
            pendingRemoteBus = null;
            final GuiScreen open = Minecraft.getMinecraft().currentScreen;
            if (open instanceof GuiRemoteBus && ((GuiRemoteBus) open).isFor(state)) ((GuiRemoteBus) open).update(state);
            else Minecraft.getMinecraft().displayGuiScreen(new GuiRemoteBus(state));
        }
    }
}
