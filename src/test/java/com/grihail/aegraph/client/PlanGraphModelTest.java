package com.grihail.aegraph.client;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertTrue;

import java.util.HashSet;
import java.util.Set;

import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;

import org.junit.Test;

public class PlanGraphModelTest {

    private static NBTTagList stacks(long... pairs) {
        final NBTTagList list = new NBTTagList();
        for (int k = 0; k < pairs.length; k += 2) {
            final NBTTagCompound tag = new NBTTagCompound();
            tag.setInteger("i", (int) pairs[k]);
            tag.setLong("a", pairs[k + 1]);
            list.appendTag(tag);
        }
        return list;
    }

    private static NBTTagCompound recipe(long runs, NBTTagList in, NBTTagList out) {
        final NBTTagCompound tag = new NBTTagCompound();
        tag.setLong("runs", runs);
        tag.setTag("in", in);
        tag.setTag("out", out);
        return tag;
    }

    /** items: 0 gravel(raw) 1 flint(target) 2 sand(byproduct) 3 seed 4 half(loop) */
    private static NBTTagCompound snapshot() {
        final NBTTagList items = new NBTTagList();
        for (int i = 0; i < 5; i++) {
            final NBTTagCompound tag = new NBTTagCompound();
            final NBTTagCompound stack = new NBTTagCompound();
            stack.setShort("id", (short) 31999); // unknown item -> rendered as "?"
            tag.setTag("item", stack);
            tag.setLong("take", i == 0 ? 6 : 0);
            items.appendTag(tag);
        }
        final NBTTagList recipes = new NBTTagList();
        recipes.appendTag(recipe(6, stacks(0, 1), stacks(1, 1, 2, 1)));
        recipes.appendTag(recipe(3, stacks(2, 2), stacks(1, 1)));
        recipes.appendTag(recipe(2, stacks(3, 1), stacks(4, 2)));
        recipes.appendTag(recipe(4, stacks(4, 1), stacks(3, 1, 1, 1)));
        final NBTTagCompound root = new NBTTagCompound();
        root.setInteger("target", 1);
        root.setLong("amount", 9);
        root.setTag("items", items);
        root.setTag("recipes", recipes);
        return root;
    }

    @Test
    public void columnsRunFromRawMaterialsToTarget() {
        final PlanGraphModel gui = new PlanGraphModel(snapshot());
        final int[] target = gui.position(false, 1);
        final int[] gravelToFlint = gui.position(true, 0);
        final int[] gravel = gui.position(false, 0);
        assertEquals(0, target[0]);
        assertEquals(1, gravelToFlint[0]);
        assertEquals(2, gravel[0]);
        // target is the rightmost node, raw material left of its pattern
        assertTrue(target[1] > gravelToFlint[1]);
        assertTrue(gravelToFlint[1] > gravel[1]);
    }

    @Test
    public void nodesDoNotOverlap() {
        final PlanGraphModel gui = new PlanGraphModel(snapshot());
        final Set<String> seen = new HashSet<>();
        for (int i = 0; i < 5; i++) assertTrue(seen.add(key(gui.position(false, i))));
        for (int r = 0; r < 4; r++) assertTrue(seen.add(key(gui.position(true, r))));
    }

    @Test
    public void byproductSitsNextToItsProducer() {
        final PlanGraphModel gui = new PlanGraphModel(snapshot());
        final int[] sand = gui.position(false, 2);
        final int[] sandToFlint = gui.position(true, 1);
        assertNotEquals(-1, sand[0]);
        assertEquals(sandToFlint[0] + 1, sand[0]); // sand is an input of the sand->flint pattern
    }

    private static String key(int[] p) {
        return p[1] + "," + p[2];
    }
}
