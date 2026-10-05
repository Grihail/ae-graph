package com.grihail.aegraph.item;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.EnumSet;

import net.minecraft.inventory.IInventory;
import net.minecraft.item.ItemStack;
import net.minecraftforge.common.util.EnumHelper;
import net.minecraftforge.common.util.ForgeDirection;

import com.grihail.aegraph.AEGraphMod;

import appeng.api.AEApi;
import appeng.api.config.Upgrades;
import appeng.helpers.DualityInterface;
import appeng.helpers.IInterfaceHost;
import appeng.parts.automation.StackUpgradeInventory;

/**
 * Fast Feed Card for ME Interfaces.
 * <p>
 * When the CPU pushes a pattern, the interface asks its neighbours whether they take the inputs and then inserts.
 * Whatever does not go in lands in the interface's send buffer; while that buffer is not empty the interface counts
 * as busy ("something stuck") and refuses every further pattern, and it only retries on its own grid tick, which
 * backs off to several seconds. A fast machine then gets one pattern every one to three seconds.
 * <p>
 * With the card, the interface retries its buffer (through every side, as it would on its tick) each time anyone
 * asks whether it still has items to send - right when the CPU wants to push the next pattern. The CPU can then
 * feed the machine pattern after pattern in the same tick, as long as the machine accepts them.
 */
public final class FastFeed {

    private static Upgrades type;
    private static boolean reflectionBroken;
    private static Field hostField;
    private static Field waitingField;
    private static Method pushItemsOut;
    /** pushItemsOut itself asks hasItemsToSend - don't recurse. */
    private static boolean flushing;

    private FastFeed() {}

    public static Upgrades type() {
        return type;
    }

    /** Pre-init: add the upgrade type to AE2's enum. */
    public static void createType() {
        try {
            type = EnumHelper.addEnum(Upgrades.class, "AEGRAPH_FAST_FEED", new Class<?>[] { int.class },
                    new Object[] { 1 });
        } catch (Throwable t) {
            AEGraphMod.LOG.error("Could not add the Fast Feed upgrade type to AE2", t);
            type = null;
        }
    }

    /** Init: let ME Interfaces (block and part) accept one card, and check AE2's code copes with the new type. */
    public static void registerSupport() {
        if (type == null) return;
        try {
            type.registerItem(AEApi.instance().definitions().blocks().iface(), 1);
            type.registerItem(AEApi.instance().definitions().parts().iface(), 1);
            // AE2 switches over upgrade types; make sure the new one goes through such a switch without trouble.
            final ItemStack ifaceStack = AEApi.instance().definitions().blocks().iface().maybeStack(1).orNull();
            new StackUpgradeInventory(ifaceStack, null, 1).getInstalledUpgrades(type);
        } catch (Throwable t) {
            AEGraphMod.LOG.error("Fast Feed upgrade type does not work with this AE2, the card is disabled", t);
            type = null;
        }
    }

    public static boolean isInstalled(DualityInterface duality) {
        if (type == null) return false;
        final IInventory upgrades = duality.getInventoryByName("upgrades");
        if (upgrades == null) return false;
        for (int slot = 0; slot < upgrades.getSizeInventory(); slot++) {
            final ItemStack stack = upgrades.getStackInSlot(slot);
            if (stack != null && stack.getItem() == AEGraphMod.fastFeedCard) return true;
        }
        return false;
    }

    /** Called at the start of {@code DualityInterface.hasItemsToSend()}. */
    public static void beforeHasItemsToSend(DualityInterface duality) {
        if (flushing || reflectionBroken || type == null) return;
        try {
            if (hostField == null) init();
            final java.util.List<?> waiting = (java.util.List<?>) waitingField.get(duality);
            if (waiting == null || waiting.isEmpty() || !isInstalled(duality)) return;
            final IInterfaceHost host = (IInterfaceHost) hostField.get(duality);
            final EnumSet<ForgeDirection> targets = host.getTargets();
            flushing = true;
            try {
                pushItemsOut.invoke(duality, targets);
            } finally {
                flushing = false;
            }
        } catch (Throwable t) {
            reflectionBroken = true;
            AEGraphMod.LOG.error("Fast Feed card disabled", t);
        }
    }

    private static synchronized void init() throws ReflectiveOperationException {
        if (hostField != null) return;
        waitingField = DualityInterface.class.getDeclaredField("waitingToSend");
        waitingField.setAccessible(true);
        pushItemsOut = DualityInterface.class.getDeclaredMethod("pushItemsOut", EnumSet.class);
        pushItemsOut.setAccessible(true);
        final Field host = DualityInterface.class.getDeclaredField("iHost");
        host.setAccessible(true);
        hostField = host;
    }
}
