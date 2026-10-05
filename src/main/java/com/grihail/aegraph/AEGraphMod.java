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
        final IDefinitions ae = AEApi.instance().definitions();
        final ItemStack unit = ae.blocks().craftingUnit().maybeStack(1).orNull();
        final ItemStack logic = ae.materials().logicProcessor().maybeStack(1).orNull();
        final ItemStack calc = ae.materials().calcProcessor().maybeStack(1).orNull();
        final ItemStack eng = ae.materials().engProcessor().maybeStack(1).orNull();
        if (unit != null && logic != null && calc != null && eng != null) {
            GameRegistry.addShapedRecipe(new ItemStack(coprocessor), "CLC", "EUE", "CLC", 'C', calc, 'L', logic,
                    'E', eng, 'U', unit);
        } else {
            LOG.warn("AE2 processors or crafting unit are disabled, no recipe for the Graph Co-Processor");
        }

        FastFeed.registerSupport();
        final ItemStack advCard = ae.materials().advCard().maybeStack(1).orNull();
        final ItemStack speedCard = ae.materials().cardSpeed().maybeStack(1).orNull();
        if (advCard != null && eng != null) {
            GameRegistry.addShapelessRecipe(new ItemStack(fastFeedCard), advCard, eng,
                    speedCard != null ? speedCard : new ItemStack(net.minecraft.init.Items.redstone));
        } else {
            LOG.warn("AE2 advanced card or engineering processor is disabled, no recipe for the Fast Feed Card");
        }

        // Remote Crafting Bus: storage bus + pattern provider parts of AE2 + engineering processor.
        final ItemStack storageBus = ae.parts().storageBus().maybeStack(1).orNull();
        final ItemStack ifacePart = ae.parts().iface().maybeStack(1).orNull();
        if (storageBus != null && ifacePart != null && eng != null) {
            GameRegistry.addShapelessRecipe(new ItemStack(remoteBus), storageBus, ifacePart, eng);
        } else {
            LOG.warn("AE2 storage bus, interface part or engineering processor is disabled, no recipe for the Remote "
                    + "Crafting Bus");
        }
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
