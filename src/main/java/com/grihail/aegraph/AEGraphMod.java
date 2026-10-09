package com.grihail.aegraph;

import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import com.grihail.aegraph.asm.AEGraphTransformer;
import com.grihail.aegraph.block.BlockGraphCoprocessor;
import com.grihail.aegraph.block.TileGraphCoprocessor;
import com.grihail.aegraph.item.FastFeed;
import com.grihail.aegraph.item.ItemFastFeedCard;
import com.grihail.aegraph.network.AEGraphNetwork;
import com.grihail.aegraph.remote.ItemRemoteBus;
import com.grihail.aegraph.remote.ItemRemotePattern;

import appeng.api.AEApi;
import appeng.api.definitions.IDefinitions;
import appeng.block.AEBaseItemBlock;
import cpw.mods.fml.common.FMLCommonHandler;
import cpw.mods.fml.common.Mod;
import cpw.mods.fml.common.SidedProxy;
import cpw.mods.fml.common.event.FMLInitializationEvent;
import cpw.mods.fml.common.event.FMLPostInitializationEvent;
import cpw.mods.fml.common.event.FMLPreInitializationEvent;
import cpw.mods.fml.common.event.FMLServerStartingEvent;
import cpw.mods.fml.common.registry.GameRegistry;

@Mod(modid = AEGraphMod.MODID, name = "AE2 Graph Crafting", version = "1.0.0",
        dependencies = "required-after:appliedenergistics2")
public class AEGraphMod {

    public static final String MODID = "aegraph";
    public static final Logger LOG = LogManager.getLogger("AEGraph");

    public static BlockGraphCoprocessor coprocessor;
    public static ItemFastFeedCard fastFeedCard;
    public static ItemRemoteBus remoteBus;
    public static ItemRemotePattern remotePattern;

    @SidedProxy(clientSide = "com.grihail.aegraph.client.ClientProxy",
            serverSide = "com.grihail.aegraph.AEGraphMod$CommonProxy")
    public static CommonProxy proxy;

    public static class CommonProxy {

        public void init() {}

        /** Client only: open the plan graph viewer. */
        public void openGraph(NBTTagCompound snapshot) {}

        /** Client only: open or refresh the Remote Crafting Bus screen. */
        public void openRemoteBus(NBTTagCompound state) {}
    }

    @Mod.EventHandler
    public void preInit(FMLPreInitializationEvent event) {
        AEGraphConfig.load(event.getSuggestedConfigurationFile());
        coprocessor = new BlockGraphCoprocessor();
        // AE2's item block: AE2 draws its blocks in inventories only through it (and it sets the owner on placing).
        GameRegistry.registerBlock(coprocessor, AEBaseItemBlock.class, "coprocessor");
        GameRegistry.registerTileEntity(TileGraphCoprocessor.class, MODID + ".coprocessor");
        FastFeed.createType();
        fastFeedCard = new ItemFastFeedCard();
        GameRegistry.registerItem(fastFeedCard, "fastFeedCard");
        remoteBus = new ItemRemoteBus();
        GameRegistry.registerItem(remoteBus, "remoteBus");
        remotePattern = new ItemRemotePattern();
        GameRegistry.registerItem(remotePattern, "remotePattern");
        AEGraphNetwork.init();
    }

    @Mod.EventHandler
    public void init(FMLInitializationEvent event) {
        proxy.init();
        FastFeed.registerSupport();
        final IDefinitions ae = AEApi.instance().definitions();
        final ItemStack logic = ae.materials().logicProcessor().maybeStack(1).orNull();
        final ItemStack calc = ae.materials().calcProcessor().maybeStack(1).orNull();
        final ItemStack eng = ae.materials().engProcessor().maybeStack(1).orNull();
        final ItemStack pearl = ae.materials().fluixPearl().maybeStack(1).orNull();

        // Graph Co-Processor: it also accelerates its CPU like a 64x Crafting Co-Processing Unit
        // (coprocessorAcceleration), so it is built around one.
        addShaped("Graph Co-Processor", new ItemStack(coprocessor), new String[] { "ECE", "LXL", "ECE" }, 'E', eng,
                'C', calc, 'L', logic, 'X', ae.blocks().craftingAccelerator64x().maybeStack(1).orNull());

        // Fast Feed Card: removes the interface's multi-second stall between patterns, so it is above the
        // Super Speed Card.
        addShaped("Fast Feed Card", new ItemStack(fastFeedCard), new String[] { "EPE", "ASA", "EPE" }, 'E', eng,
                'P', pearl, 'A', ae.materials().advCard().maybeStack(1).orNull(), 'S',
                ae.materials().cardSuperSpeed().maybeStack(1).orNull());

        // Remote Crafting Bus: a link to the whole crafting setup of another network.
        addShaped("Remote Crafting Bus", new ItemStack(remoteBus), new String[] { "EPE", "SIS", "EPE" }, 'E', eng,
                'P', pearl, 'S', ae.parts().storageBus().maybeStack(1).orNull(), 'I',
                ae.parts().iface().maybeStack(1).orNull());
    }

    /** Shaped recipe {@code rows} with ({@code char}, {@code ItemStack}) pairs; none if an AE2 item is disabled. */
    private static void addShaped(String name, ItemStack result, String[] rows, Object... keys) {
        for (int k = 1; k < keys.length; k += 2) {
            if (keys[k] == null) {
                LOG.warn("An AE2 ingredient ('{}') is disabled, no recipe for the {}", keys[k - 1], name);
                return;
            }
        }
        final Object[] recipe = new Object[rows.length + keys.length];
        System.arraycopy(rows, 0, recipe, 0, rows.length);
        System.arraycopy(keys, 0, recipe, rows.length, keys.length);
        GameRegistry.addShapedRecipe(result, recipe);
    }

    @Mod.EventHandler
    public void postInit(FMLPostInitializationEvent event) {
        // Link the patched classes now, so a bytecode problem shows up at startup instead of mid-game.
        for (final String name : new String[] { "appeng.me.cache.CraftingGridCache",
                "appeng.me.cluster.implementations.CraftingCPUCluster", "appeng.helpers.DualityInterface" }) {
            try {
                Class.forName(name, true, AEGraphMod.class.getClassLoader());
            } catch (Throwable t) {
                throw new RuntimeException("AEGraph: patched AE2 class " + name + " failed to load", t);
            }
        }
        if (!AEGraphTransformer.patchedInterface) LOG.error("ME Interface was not patched, the Fast Feed Card does nothing");
        if (!AEGraphTransformer.patchedCpuInject) {
            LOG.error("CraftingCPUCluster.injectItems was not patched, CPUs keep byproducts until the job ends");
        }
        if (AEGraphTransformer.patchedJobFactory && AEGraphTransformer.patchedCpu) {
            LOG.info("AE2 crafting calculator replaced by the graph planner.");
        } else {
            LOG.error("AE2 classes were not patched (job factory: {}, cpu: {}). The graph planner is inactive.",
                    AEGraphTransformer.patchedJobFactory, AEGraphTransformer.patchedCpu);
        }
    }

    @Mod.EventHandler
    public void serverStarting(FMLServerStartingEvent event) {
        event.registerServerCommand(new CommandAEGraph());
        if (SelfTest.isEnabled()) {
            LOG.warn("Self-test mode: an ME test network will be built at world spawn, y=200");
            FMLCommonHandler.instance().bus().register(new SelfTest());
        }
    }
}
