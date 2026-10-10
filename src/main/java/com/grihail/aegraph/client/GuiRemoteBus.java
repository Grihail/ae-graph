package com.grihail.aegraph.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiButton;
import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.item.ItemStack;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.nbt.NBTTagList;
import net.minecraft.util.StatCollector;

import org.lwjgl.opengl.GL11;

import com.grihail.aegraph.network.AEGraphNetwork;

/** Settings and state of a Remote Crafting Bus. Refreshes itself from the server once a second. */
public class GuiRemoteBus extends GuiScreen {

    private static final int W = 256;
    private static final int H = 222;
    private static final int COLS = 13;
    /** Vanilla container colours. */
    private static final int TEXT = 0xFF404040;
    private static final int GOOD = 0xFF1E6E1E;
    private static final int BAD = 0xFFA01818;
    private static final int WARN = 0xFF8A4B00;

    private NBTTagCompound state;
    private GuiButton exclusiveButton;
    private int left, top;
    private int ticks;

    public GuiRemoteBus(NBTTagCompound state) {
        this.state = state;
    }

    boolean isFor(NBTTagCompound other) {
        return other.getInteger("x") == state.getInteger("x") && other.getInteger("y") == state.getInteger("y")
                && other.getInteger("z") == state.getInteger("z") && other.getByte("side") == state.getByte("side");
    }

    void update(NBTTagCompound newState) {
        state = newState;
        updateButton();
    }

    @Override
    @SuppressWarnings("unchecked")
    public void initGui() {
        left = (width - W) / 2;
        top = (height - H) / 2;
        buttonList.clear();
        exclusiveButton = new GuiButton(0, left + 8, top + 34, W - 16, 20, "");
        buttonList.add(exclusiveButton);
        updateButton();
    }

    private void updateButton() {
        if (exclusiveButton == null) return;
        exclusiveButton.displayString = StatCollector.translateToLocal(
                state.getBoolean("exclusive") ? "aegraph.remote.gui.exclusiveOn" : "aegraph.remote.gui.exclusiveOff");
    }

    @Override
    protected void actionPerformed(GuiButton button) {
        if (button == exclusiveButton) send(state.getBoolean("exclusive") ? 0 : 1);
    }

    private void send(int exclusive) {
        AEGraphNetwork.sendRemoteBusSetting(state.getInteger("x"), state.getInteger("y"), state.getInteger("z"),
                state.getByte("side"), exclusive);
    }

    @Override
    public void updateScreen() {
        if (++ticks % 20 == 0) send(-1);
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partial) {
        drawDefaultBackground();
        drawPanel(left, top, W, H);
        fontRendererObj.drawString(StatCollector.translateToLocal("aegraph.remote.gui.title"), left + 8, top + 7,
                TEXT);
        final boolean ok = "aegraph.remote.state.ok".equals(state.getString("state"));
        drawWrapped(StatCollector.translateToLocalFormatted(state.getString("state"), state.getString("stateArg")),
                left + 8, top + 19, ok ? GOOD : BAD, 1);

        super.drawScreen(mouseX, mouseY, partial);

        // Offered crafts
        int y = top + 60;
        final NBTTagList exports = state.getTagList("exports", 10);
        fontRendererObj.drawString(
                StatCollector.translateToLocalFormatted("aegraph.remote.gui.exports", exports.tagCount()), left + 8, y,
                TEXT);
        y += 11;
        final List<String> tooltip = new ArrayList<>();
        final int rows = Math.min(3, (exports.tagCount() + COLS - 1) / COLS);
        for (int k = 0; k < Math.max(1, rows) * COLS; k++) drawSlot(left + 8 + (k % COLS) * 18, y + (k / COLS) * 18);
        GL11.glPushMatrix();
        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL11.GL_DEPTH_TEST);
        for (int k = 0; k < exports.tagCount() && k < rows * COLS; k++) {
            final NBTTagCompound e = exports.getCompoundTagAt(k);
            final ItemStack stack = ItemStack.loadItemStackFromNBT(e.getCompoundTag("item"));
            if (stack == null) continue;
            final int ix = left + 9 + (k % COLS) * 18;
            final int iy = y + 1 + (k / COLS) * 18;
            itemRender.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), stack, ix, iy);
            if (mouseX >= ix && mouseX < ix + 16 && mouseY >= iy && mouseY < iy + 16) {
                tooltip.add(stack.getDisplayName());
                tooltip.add(StatCollector.translateToLocalFormatted("aegraph.remote.gui.perRun", e.getLong("amount")));
                final NBTTagList in = e.getTagList("in", 10);
                for (int q = 0; q < in.tagCount(); q++) {
                    tooltip.add(StatCollector.translateToLocalFormatted("aegraph.remote.gui.input",
                            in.getCompoundTagAt(q).getLong("amount"), name(in.getCompoundTagAt(q))));
                }
            }
        }
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        GL11.glPopMatrix();
        if (exports.tagCount() > rows * COLS) {
            fontRendererObj.drawString("+" + (exports.tagCount() - rows * COLS), left + 8, y + rows * 18 + 2, TEXT);
            y += 10;
        }
        y += Math.max(1, rows) * 18 + 5;

        // Requests
        fontRendererObj.drawString(StatCollector.translateToLocal("aegraph.remote.gui.requests"), left + 8, y,
                TEXT);
        y += 11;
        final List<String> lines = new ArrayList<>();
        final NBTTagList owed = state.getTagList("owed", 10);
        for (int k = 0; k < owed.tagCount(); k++) {
            final NBTTagCompound o = owed.getCompoundTagAt(k);
            lines.add(StatCollector.translateToLocalFormatted("aegraph.remote.gui.owed", o.getLong("amount"), name(o)));
        }
        final NBTTagList deliver = state.getTagList("deliver", 10);
        for (int k = 0; k < deliver.tagCount(); k++) {
            final NBTTagCompound d = deliver.getCompoundTagAt(k);
            lines.add(StatCollector.translateToLocalFormatted("aegraph.remote.gui.deliver", d.getLong("amount"),
                    name(d)));
        }
        if (lines.isEmpty()) lines.add(StatCollector.translateToLocal("aegraph.remote.gui.idle"));
        final int maxLines = (top + H - 22 - y) / 10;
        for (int k = 0; k < lines.size() && k < maxLines; k++) {
            final String line = k == maxLines - 1 && lines.size() > maxLines ? "... (+" + (lines.size() - k) + ")"
                    : lines.get(k);
            fontRendererObj.drawString(fontRendererObj.trimStringToWidth(line, W - 16), left + 8, y, TEXT);
            y += 10;
        }

        if (state.hasKey("problem")) {
            drawWrapped(StatCollector.translateToLocalFormatted(state.getString("problem"),
                    state.getString("problemArg")), left + 8, top + H - 22, WARN, 2);
        }

        if (mouseX >= exclusiveButton.xPosition && mouseX < exclusiveButton.xPosition + exclusiveButton.width
                && mouseY >= exclusiveButton.yPosition && mouseY < exclusiveButton.yPosition + 20) {
            for (final String line : StatCollector.translateToLocal("aegraph.remote.gui.exclusiveHelp")
                    .split("\\\\n")) {
                tooltip.add(line);
            }
        }
        if (!tooltip.isEmpty()) drawHoveringText(tooltip, mouseX, mouseY, fontRendererObj);
    }

    /** Background of a vanilla container: black outline with cut corners, white light, dark shadow, grey body. */
    private static void drawPanel(int x, int y, int w, int h) {
        drawRect(x + 1, y, x + w - 1, y + h, 0xFF000000);
        drawRect(x, y + 1, x + w, y + h - 1, 0xFF000000);
        drawRect(x + 1, y + 1, x + w - 1, y + h - 1, 0xFF555555);
        drawRect(x + 1, y + 1, x + w - 3, y + h - 3, 0xFFFFFFFF);
        drawRect(x + 3, y + 3, x + w - 3, y + h - 3, 0xFFC6C6C6);
        drawRect(x + w - 3, y + 1, x + w - 1, y + 3, 0xFFC6C6C6);
        drawRect(x + 1, y + h - 3, x + 3, y + h - 1, 0xFFC6C6C6);
    }

    /** A vanilla inventory slot, 18x18 with the item at +1,+1. */
    private static void drawSlot(int x, int y) {
        drawRect(x, y, x + 18, y + 18, 0xFFFFFFFF);
        drawRect(x, y, x + 17, y + 17, 0xFF373737);
        drawRect(x + 1, y + 1, x + 17, y + 17, 0xFF8B8B8B);
    }

    private void drawWrapped(String text, int x, int y, int color, int maxLines) {
        @SuppressWarnings("unchecked")
        final List<String> lines = fontRendererObj.listFormattedStringToWidth(text, W - 16);
        for (int k = 0; k < lines.size() && k < maxLines; k++) {
            fontRendererObj.drawString(lines.get(k), x, y + k * 10, color);
        }
    }

    private static String name(NBTTagCompound tag) {
        final ItemStack stack = ItemStack.loadItemStackFromNBT(tag.getCompoundTag("item"));
        return stack == null ? "?" : stack.getDisplayName();
    }
}
