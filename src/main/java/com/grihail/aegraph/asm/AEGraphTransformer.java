package com.grihail.aegraph.asm;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;
import org.objectweb.asm.ClassReader;
import org.objectweb.asm.ClassWriter;
import org.objectweb.asm.Opcodes;
import org.objectweb.asm.tree.AbstractInsnNode;
import org.objectweb.asm.tree.ClassNode;
import org.objectweb.asm.tree.FieldInsnNode;
import org.objectweb.asm.tree.FrameNode;
import org.objectweb.asm.tree.InsnList;
import org.objectweb.asm.tree.InsnNode;
import org.objectweb.asm.tree.JumpInsnNode;
import org.objectweb.asm.tree.LabelNode;
import org.objectweb.asm.tree.MethodInsnNode;
import org.objectweb.asm.tree.MethodNode;
import org.objectweb.asm.tree.VarInsnNode;

import net.minecraft.launchwrapper.IClassTransformer;

/**
 * Small hooks into AE2 (names are AE2's own, they are not obfuscated):
 * <ul>
 * <li>{@code CraftingGridCache.beginCraftingJob(...)} - every crafting request (terminals, interfaces, export
 * buses, emitters, OC/CC) asks {@code Hooks.beginCraftingJob} first and uses the graph job it returns.</li>
 * <li>{@code CraftingGridCache.submitJob(...)} - lets a graph job pick a CPU with a Graph Co-Processor.</li>
 * <li>{@code CraftingGridCache.setPatternsFromCraftingMethods()} - exclusive Remote Crafting Buses hide the other
 * patterns for their items.</li>
 * <li>{@code CraftingGridCache.updatePatterns()} / {@code onUpdateTick()} - pattern list rebuilds requested within
 * one tick are done once, at the grid's next tick (every ME Interface asks for one when it loads; a network with
 * many interfaces otherwise rebuilds the whole list once per interface).</li>
 * <li>{@code CraftingCPUCluster.updateCraftingLogic(...)} - lets the CPU finish a job whose final output arrived
 * before the last byproduct-processing steps did, and runs the {@code CpuStabilizer}.</li>
 * <li>{@code CraftingCPUCluster.injectItems(...)} - items no remaining task needs go to storage instead of the
 * CPU buffer.</li>
 * </ul>
 */
public class AEGraphTransformer implements IClassTransformer, Opcodes {

    private static final Logger LOG = LogManager.getLogger("AEGraph");
    private static final String HOOKS = "com/grihail/aegraph/ae/Hooks";

    private static final String GRID_CACHE = "appeng.me.cache.CraftingGridCache";
    private static final String BEGIN_DESC = "(Lnet/minecraft/world/World;Lappeng/api/networking/IGrid;"
            + "Lappeng/api/networking/security/BaseActionSource;Lappeng/api/storage/data/IAEItemStack;"
            + "Lappeng/api/config/CraftingMode;Lappeng/api/networking/crafting/ICraftingCallback;)"
            + "Ljava/util/concurrent/Future;";

    private static final String SUBMIT_DESC = "(Lappeng/api/networking/crafting/ICraftingJob;"
            + "Lappeng/api/networking/crafting/ICraftingRequester;Lappeng/api/networking/crafting/ICraftingCPU;Z"
            + "Lappeng/api/networking/security/BaseActionSource;Z)Lappeng/api/networking/crafting/ICraftingLink;";

    private static final String CPU ="appeng.me.cluster.implementations.CraftingCPUCluster";
    private static final String UPDATE_DESC = "(Lappeng/api/networking/IGrid;Lappeng/api/networking/energy/IEnergyGrid;"
            + "Lappeng/me/cache/CraftingGridCache;)V";

    private static final String INJECT_DESC = "(Lappeng/api/storage/data/IAEStack;Lappeng/api/config/Actionable;"
            + "Lappeng/api/networking/security/BaseActionSource;)Lappeng/api/storage/data/IAEStack;";

    private static final String INTERFACE = "appeng.helpers.DualityInterface";
    private static final String STORAGE_CACHE = "appeng.me.cache.GridStorageCache";
    private static final String CELL_UPDATE_DESC = "(Lappeng/api/networking/events/MENetworkCellArrayUpdate;)V";
    public static volatile boolean patchedCellUpdate = false;

    public static volatile boolean patchedJobFactory = false;
    public static volatile boolean patchedCpu = false;
    public static volatile boolean patchedCpuInject = false;
    public static volatile boolean patchedInterface = false;
    public static volatile boolean patchedPatternFilter = false;
    public static volatile boolean patchedRebuildDefer = false;
    public static volatile boolean patchedRebuildFlush = false;

    @Override
    public byte[] transform(String name, String transformedName, byte[] basicClass) {
        if (basicClass == null) return null;
        try {
            if (GRID_CACHE.equals(transformedName)) {
                return patch(basicClass, transformedName, this::patchGridCache);
            }
            if (CPU.equals(transformedName)) {
                return patch(basicClass, transformedName, this::patchCpu);
            }
            if (INTERFACE.equals(transformedName)) {
                return patch(basicClass, transformedName, this::patchInterface);
            }
            if (STORAGE_CACHE.equals(transformedName)) {
                return patch(basicClass, transformedName, this::patchStorageCache);
            }
        } catch (Throwable t) {
            LOG.error("Failed to patch " + transformedName + ", leaving it untouched", t);
        }
        return basicClass;
    }

    private interface Patcher {

        boolean apply(ClassNode node);
    }

    private static byte[] patch(byte[] bytes, String className, Patcher patcher) {
        final ClassNode node = new ClassNode();
        new ClassReader(bytes).accept(node, 0);
        if (!patcher.apply(node)) {
            LOG.error("Target method not found in {}, leaving it untouched", className);
            return bytes;
        }
        final ClassWriter writer = new ClassWriter(ClassWriter.COMPUTE_MAXS);
        node.accept(writer);
        LOG.info("Patched {}", className);
        return writer.toByteArray();
    }

    /**
     * <pre>
     * Future f = Hooks.beginCraftingJob(this, world, grid, src, what, mode, callback);
     * if (f != null) return f;
     * ...original body...
     * </pre>
     */
    private boolean patchGridCache(ClassNode node) {
        boolean begin = false;
        boolean submit = false;
        for (final MethodNode method : node.methods) {
            if ("updatePatterns".equals(method.name) && "()V".equals(method.desc)) {
                // if (Hooks.deferPatternRebuild(this)) return;
                final InsnList head = new InsnList();
                head.add(new VarInsnNode(ALOAD, 0));
                head.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "deferPatternRebuild",
                        "(Lappeng/me/cache/CraftingGridCache;)Z", false));
                final LabelNode proceed = new LabelNode();
                head.add(new JumpInsnNode(IFEQ, proceed));
                head.add(new InsnNode(RETURN));
                head.add(proceed);
                head.add(new FrameNode(F_SAME, 0, null, 0, null));
                method.instructions.insert(head);
                patchedRebuildDefer = true;
            }
            if ("onUpdateTick".equals(method.name) && "()V".equals(method.desc)) {
                // if (Hooks.takeDeferredRebuild(this)) this.updatePatterns();
                final InsnList head = new InsnList();
                head.add(new VarInsnNode(ALOAD, 0));
                head.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "takeDeferredRebuild",
                        "(Lappeng/me/cache/CraftingGridCache;)Z", false));
                final LabelNode skip = new LabelNode();
                head.add(new JumpInsnNode(IFEQ, skip));
                head.add(new VarInsnNode(ALOAD, 0));
                head.add(new MethodInsnNode(INVOKESPECIAL, "appeng/me/cache/CraftingGridCache", "updatePatterns",
                        "()V", false));
                head.add(skip);
                head.add(new FrameNode(F_SAME, 0, null, 0, null));
                method.instructions.insert(head);
                patchedRebuildFlush = true;
            }
            if ("setPatternsFromCraftingMethods".equals(method.name) && "()V".equals(method.desc)) {
                // Hooks.afterPatternsBuilt(this.craftableItems); before every return
                for (final AbstractInsnNode insn : method.instructions.toArray()) {
                    if (insn.getOpcode() != RETURN) continue;
                    final InsnList call = new InsnList();
                    call.add(new VarInsnNode(ALOAD, 0));
                    call.add(new FieldInsnNode(GETFIELD, "appeng/me/cache/CraftingGridCache", "craftableItems",
                            "Ljava/util/Map;"));
                    call.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "afterPatternsBuilt", "(Ljava/util/Map;)V",
                            false));
                    method.instructions.insertBefore(insn, call);
                    patchedPatternFilter = true;
                }
            }
            if ("submitJob".equals(method.name) && SUBMIT_DESC.equals(method.desc)) {
                // target = Hooks.chooseCpu(this, job, target, src);
                final InsnList head = new InsnList();
                head.add(new VarInsnNode(ALOAD, 0));
                head.add(new VarInsnNode(ALOAD, 1));
                head.add(new VarInsnNode(ALOAD, 3));
                head.add(new VarInsnNode(ALOAD, 5));
                head.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "chooseCpu",
                        "(Lappeng/me/cache/CraftingGridCache;Lappeng/api/networking/crafting/ICraftingJob;"
                                + "Lappeng/api/networking/crafting/ICraftingCPU;"
                                + "Lappeng/api/networking/security/BaseActionSource;)"
                                + "Lappeng/api/networking/crafting/ICraftingCPU;",
                        false));
                head.add(new VarInsnNode(ASTORE, 3));
                method.instructions.insert(head);
                submit = true;
            }
            if (!"beginCraftingJob".equals(method.name) || !BEGIN_DESC.equals(method.desc)) continue;
            final InsnList head = new InsnList();
            for (int slot = 0; slot <= 6; slot++) {
                head.add(new VarInsnNode(ALOAD, slot));
            }
            head.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "beginCraftingJob",
                    "(Lappeng/me/cache/CraftingGridCache;" + BEGIN_DESC.substring(1), false));
            final LabelNode proceed = new LabelNode();
            head.add(new InsnNode(DUP));
            head.add(new JumpInsnNode(IFNULL, proceed));
            head.add(new InsnNode(ARETURN));
            head.add(proceed);
            head.add(new FrameNode(F_SAME1, 0, null, 1, new Object[] { "java/util/concurrent/Future" }));
            head.add(new InsnNode(POP));
            method.instructions.insert(head);
            begin = true;
        }
        if (!submit) LOG.error("CraftingGridCache.submitJob not found, graph CPUs will not be preferred");
        if (!patchedPatternFilter) {
            LOG.error("CraftingGridCache.setPatternsFromCraftingMethods not found, exclusive remote buses won't work");
        }
        patchedJobFactory = begin;
        return begin || submit || patchedPatternFilter;
    }

    /**
     * {@code GridStorageCache.cellUpdate(...)}: every drive, cell or storage bus change rebuilds the whole list of
     * storages and the network's item list. Requests within one tick are merged into one rebuild at the next tick:
     * {@code if (Hooks.deferCellUpdate(this)) return;} at its start and
     * {@code if (Hooks.takeDeferredCellUpdate(this)) this.cellUpdate(null);} at the start of onUpdateTick.
     */
    private boolean patchStorageCache(ClassNode node) {
        boolean update = false, tick = false;
        for (final MethodNode method : node.methods) {
            if ("cellUpdate".equals(method.name) && CELL_UPDATE_DESC.equals(method.desc)) {
                final InsnList head = new InsnList();
                head.add(new VarInsnNode(ALOAD, 0));
                head.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "deferCellUpdate",
                        "(Lappeng/me/cache/GridStorageCache;)Z", false));
                final LabelNode proceed = new LabelNode();
                head.add(new JumpInsnNode(IFEQ, proceed));
                head.add(new InsnNode(RETURN));
                head.add(proceed);
                head.add(new FrameNode(F_SAME, 0, null, 0, null));
                method.instructions.insert(head);
                update = true;
            }
            if ("onUpdateTick".equals(method.name) && "()V".equals(method.desc)) {
                final InsnList head = new InsnList();
                head.add(new VarInsnNode(ALOAD, 0));
                head.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "takeDeferredCellUpdate",
                        "(Lappeng/me/cache/GridStorageCache;)Z", false));
                final LabelNode skip = new LabelNode();
                head.add(new JumpInsnNode(IFEQ, skip));
                head.add(new VarInsnNode(ALOAD, 0));
                head.add(new InsnNode(ACONST_NULL));
                head.add(new MethodInsnNode(INVOKEVIRTUAL, "appeng/me/cache/GridStorageCache", "cellUpdate",
                        CELL_UPDATE_DESC, false));
                head.add(skip);
                head.add(new FrameNode(F_SAME, 0, null, 0, null));
                method.instructions.insert(head);
                tick = true;
            }
        }
        patchedCellUpdate = update && tick;
        if (update != tick) LOG.error("GridStorageCache: only one of cellUpdate/onUpdateTick found, not patched");
        return patchedCellUpdate;
    }

    /** {@code FastFeed.beforeHasItemsToSend(this);} at the start of DualityInterface.hasItemsToSend. */
    private boolean patchInterface(ClassNode node) {
        for (final MethodNode method : node.methods) {
            if (!"hasItemsToSend".equals(method.name) || !"()Z".equals(method.desc)) continue;
            final InsnList head = new InsnList();
            head.add(new VarInsnNode(ALOAD, 0));
            head.add(new MethodInsnNode(INVOKESTATIC, "com/grihail/aegraph/item/FastFeed", "beforeHasItemsToSend",
                    "(Lappeng/helpers/DualityInterface;)V", false));
            method.instructions.insert(head);
            patchedInterface = true;
            return true;
        }
        return false;
    }

    /**
     * {@code Hooks.onCpuUpdate(this, craftingGridCache);} at the start of updateCraftingLogic, and
     * {@code if (Hooks.cpuPassThrough(this, input, type, src)) return input;} at the start of injectItems.
     */
    private boolean patchCpu(ClassNode node) {
        boolean update = false;
        for (final MethodNode method : node.methods) {
            if ("injectItems".equals(method.name) && INJECT_DESC.equals(method.desc)) {
                final InsnList head = new InsnList();
                for (int slot = 0; slot <= 3; slot++) {
                    head.add(new VarInsnNode(ALOAD, slot));
                }
                head.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "cpuPassThrough",
                        "(Lappeng/me/cluster/implementations/CraftingCPUCluster;Lappeng/api/storage/data/IAEStack;"
                                + "Lappeng/api/config/Actionable;Lappeng/api/networking/security/BaseActionSource;)Z",
                        false));
                final LabelNode proceed = new LabelNode();
                head.add(new JumpInsnNode(IFEQ, proceed));
                head.add(new VarInsnNode(ALOAD, 1));
                head.add(new InsnNode(ARETURN));
                head.add(proceed);
                head.add(new FrameNode(F_SAME, 0, null, 0, null));
                method.instructions.insert(head);
                patchedCpuInject = true;
            }
            if (!"updateCraftingLogic".equals(method.name) || !UPDATE_DESC.equals(method.desc)) continue;
            final InsnList head = new InsnList();
            head.add(new VarInsnNode(ALOAD, 0));
            head.add(new VarInsnNode(ALOAD, 3));
            head.add(new MethodInsnNode(INVOKESTATIC, HOOKS, "onCpuUpdate",
                    "(Lappeng/me/cluster/implementations/CraftingCPUCluster;Lappeng/me/cache/CraftingGridCache;)V",
                    false));
            method.instructions.insert(head);
            patchedCpu = true;
            update = true;
        }
        return update || patchedCpuInject;
    }
}
