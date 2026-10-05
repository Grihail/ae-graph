package com.grihail.aegraph.client;

import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.gui.GuiScreen;
import net.minecraft.client.renderer.OpenGlHelper;
import net.minecraft.client.renderer.RenderHelper;
import net.minecraft.client.renderer.Tessellator;
import net.minecraft.client.resources.I18n;
import net.minecraft.nbt.NBTTagCompound;
import net.minecraft.util.EnumChatFormatting;

import com.grihail.aegraph.client.PlanGraphModel.Node;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;

/**
 * Viewer for a plan graph. Columns run from raw materials (left) to the requested item (right); square nodes are
 * items, purple nodes are patterns with their run counts. Edges into a pattern are its inputs, edges out of it its
 * outputs; edges that point back to the left close a processing loop and are drawn as orange arcs.
 */
public class GuiPlanGraph extends GuiScreen {

    private static final int NODE = PlanGraphModel.NODE;
    private static final int HEADER = 30;

    private static final int C_INPUT = 0x5A7FA8;
    private static final int C_OUTPUT = 0x5FB86A;
    private static final int C_LOOP = 0xE39B3B;
    private static final int C_TARGET = 0xF2C94C;
    private static final int C_RAW = 0x4FA3E0;
    private static final int C_MISSING = 0xE05050;
    private static final int C_SURPLUS = 0x7BD88F;
    private static final int C_ITEM = 0x8A8F98;
    private static final int C_RECIPE = 0xA87BE0;

    private final NBTTagCompound data;
    private final List<Node> nodes;
    private final List<Node> itemNodes;
    private final List<Node> recipeNodes;
    private final Node targetNode;
    private final int graphW, graphH;

    private double panX, panY, zoom = 1;
    private int dragX, dragY;
    private boolean dragging;
    private boolean fitted;

    public GuiPlanGraph(NBTTagCompound data) {
        final PlanGraphModel model = new PlanGraphModel(data);
        this.data = data;
        this.nodes = model.nodes;
        this.itemNodes = model.itemNodes;
        this.recipeNodes = model.recipeNodes;
        this.targetNode = model.targetNode;
        this.graphW = model.graphW;
        this.graphH = model.graphH;
    }

    // --- view -------------------------------------------------------------------------------------------------

    @Override
    public void initGui() {
        if (!fitted) {
            final double fit = Math.min((width - 20.0) / Math.max(1, graphW), (height - HEADER - 30.0) / Math.max(1, graphH));
            zoom = Math.max(0.3, Math.min(1.5, fit));
            panX = (width - graphW * zoom) / 2;
            panY = HEADER + 8 + Math.max(0, (height - HEADER - 30 - graphH * zoom) / 2);
            fitted = true;
        }
    }

    @Override
    public boolean doesGuiPauseGame() {
        return false;
    }

    @Override
    protected void keyTyped(char c, int key) {
        if (key == Keyboard.KEY_R) {
            fitted = false;
            initGui();
            return;
        }
        super.keyTyped(c, key);
    }

    @Override
    protected void mouseClicked(int x, int y, int button) {
        super.mouseClicked(x, y, button);
        dragging = true;
        dragX = x;
        dragY = y;
    }

    @Override
    protected void mouseClickMove(int x, int y, int button, long time) {
        if (!dragging) return;
        panX += x - dragX;
        panY += y - dragY;
        dragX = x;
        dragY = y;
    }

    @Override
    protected void mouseMovedOrUp(int x, int y, int button) {
        super.mouseMovedOrUp(x, y, button);
        if (button >= 0) dragging = false;
    }

    @Override
    public void handleMouseInput() {
        super.handleMouseInput();
        final int wheel = Mouse.getEventDWheel();
        if (wheel == 0) return;
        final int mx = Mouse.getEventX() * width / mc.displayWidth;
        final int my = height - Mouse.getEventY() * height / mc.displayHeight - 1;
        final double factor = wheel > 0 ? 1.15 : 1 / 1.15;
        final double newZoom = Math.max(0.15, Math.min(4, zoom * factor));
        panX = mx - (mx - panX) * newZoom / zoom;
        panY = my - (my - panY) * newZoom / zoom;
        zoom = newZoom;
    }

    @Override
    public void drawScreen(int mouseX, int mouseY, float partialTicks) {
        drawRect(0, 0, width, height, 0xE0101318);

        GL11.glPushMatrix();
        GL11.glTranslated(panX, panY, 0);
        GL11.glScaled(zoom, zoom, 1);
        drawEdges();
        drawNodes();
        GL11.glPopMatrix();
        // 3D block icons write depth; clear it so the header and legend always cover the graph.
        GL11.glClear(GL11.GL_DEPTH_BUFFER_BIT);

        final Node hovered = nodeAt(mouseX, mouseY);
        drawHeader();
        drawLegend();
        if (hovered != null) drawTooltip(hovered, mouseX, mouseY);
        super.drawScreen(mouseX, mouseY, partialTicks);
    }

    private Node nodeAt(int mouseX, int mouseY) {
        if (mouseY < HEADER) return null;
        final double gx = (mouseX - panX) / zoom;
        final double gy = (mouseY - panY) / zoom;
        for (final Node n : nodes) {
            if (gx >= n.x - 2 && gx <= n.x + NODE + 2 && gy >= n.y - 2 && gy <= n.y + NODE + 2) return n;
        }
        return null;
    }

    private void drawEdges() {
        GL11.glDisable(GL11.GL_TEXTURE_2D);
        GL11.glEnable(GL11.GL_BLEND);
        OpenGlHelper.glBlendFunc(770, 771, 1, 0);
        GL11.glEnable(GL11.GL_LINE_SMOOTH);
        GL11.glLineWidth((float) Math.max(1, 1.6 * zoom));
        final Tessellator t = Tessellator.instance;
        for (final Node r : recipeNodes) {
            for (final long[] e : r.in) edge(t, itemNodes.get((int) e[0]), r, C_INPUT);
            for (final long[] e : r.out) edge(t, r, itemNodes.get((int) e[0]), C_OUTPUT);
        }
        GL11.glDisable(GL11.GL_LINE_SMOOTH);
        GL11.glEnable(GL11.GL_TEXTURE_2D);
    }

    private void edge(Tessellator t, Node from, Node to, int color) {
        final double y1 = from.y + NODE / 2.0;
        final double y2 = to.y + NODE / 2.0;
        t.startDrawing(GL11.GL_LINE_STRIP);
        if (from.x < to.x) {
            t.setColorRGBA_I(color, 200);
            final double x1 = from.x + NODE + 1;
            final double x2 = to.x - 1;
            final double mid = (x1 + x2) / 2;
            for (int s = 0; s <= 12; s++) {
                final double u = s / 12.0;
                // cubic curve with horizontal tangents
                final double x = cubic(x1, mid, mid, x2, u);
                final double y = cubic(y1, y1, y2, y2, u);
                t.addVertex(x, y, 0);
            }
        } else {
            // loop back to the left: arc below both nodes
            t.setColorRGBA_I(C_LOOP, 220);
            final double x1 = from.x + NODE / 2.0;
            final double x2 = to.x + NODE / 2.0;
            final double drop = Math.max(y1, y2) + NODE + 10 + Math.abs(x1 - x2) * 0.15;
            for (int s = 0; s <= 20; s++) {
                final double u = s / 20.0;
                final double x = cubic(x1, x1, x2, x2, u);
                final double y = cubic(from.y + NODE + 1.0, drop, drop, to.y + NODE + 1.0, u);
                t.addVertex(x, y, 0);
            }
        }
        t.draw();
    }

    private static double cubic(double a, double b, double c, double d, double u) {
        final double v = 1 - u;
        return v * v * v * a + 3 * v * v * u * b + 3 * v * u * u * c + u * u * u * d;
    }

    private void drawNodes() {
        for (final Node n : nodes) {
            final int color = n.recipe ? C_RECIPE : itemColor(n);
            drawRect(n.x - 2, n.y - 2, n.x + NODE + 2, n.y + NODE + 2, 0xFF000000 | color);
            drawRect(n.x - 1, n.y - 1, n.x + NODE + 1, n.y + NODE + 1, n.recipe ? 0xFF2A2140 : 0xFF1C2028);
            if (n.recipe && n.remaining >= 0 && n.runs > 0) {
                final int done = (int) Math.round((NODE + 2) * (n.runs - n.remaining) / (double) n.runs);
                drawRect(n.x - 1, n.y + NODE + 3, n.x - 1 + done, n.y + NODE + 5, 0xFF7BD88F);
            }
        }
        RenderHelper.enableGUIStandardItemLighting();
        GL11.glEnable(GL12.GL_RESCALE_NORMAL);
        for (final Node n : nodes) {
            if (n.stack == null) continue;
            try {
                itemRender.renderItemAndEffectIntoGUI(fontRendererObj, mc.getTextureManager(), n.stack, n.x + 2,
                        n.y + 2);
            } catch (Throwable ignored) {
                // broken item renderers must not kill the viewer
            }
        }
        GL11.glDisable(GL12.GL_RESCALE_NORMAL);
        RenderHelper.disableStandardItemLighting();
        GL11.glDisable(GL11.GL_LIGHTING);
        GL11.glDisable(GL11.GL_DEPTH_TEST);
        for (final Node n : nodes) {
            final String label;
            final int color;
            if (n.recipe) {
                label = "×" + fmt(n.runs);
                color = 0xD9C2FF;
            } else if (n == targetNode) {
                label = fmtAmount(n, data.getLong("amount"));
                color = C_TARGET;
            } else if (n.missing > 0) {
                label = "!" + fmtAmount(n, n.missing);
                color = C_MISSING;
            } else if (n.take > 0) {
                label = "-" + fmtAmount(n, n.take);
                color = C_RAW;
            } else if (surplus(n) > 0) {
                label = "+" + fmtAmount(n, surplus(n));
                color = C_SURPLUS;
            } else {
                label = fmtAmount(n, n.produced);
                color = 0xC8CCD2;
            }
            drawSmallCentered(label, n.x + NODE / 2, n.y + NODE + (n.recipe && n.remaining >= 0 ? 6 : 4), color);
        }
        GL11.glEnable(GL11.GL_DEPTH_TEST);
    }

    private int itemColor(Node n) {
        if (n == targetNode) return C_TARGET;
        if (n.missing > 0) return C_MISSING;
        if (n.take > 0) return C_RAW;
        if (surplus(n) > 0 && n.consumed == 0) return C_SURPLUS;
        return C_ITEM;
    }

    private static long surplus(Node n) {
        return n.take + n.missing + n.produced - n.consumed;
    }

    private void drawSmallCentered(String text, int cx, int y, int color) {
        GL11.glPushMatrix();
        GL11.glTranslated(cx, y, 0);
        GL11.glScaled(0.6, 0.6, 1);
        fontRendererObj.drawStringWithShadow(text, -fontRendererObj.getStringWidth(text) / 2, 0, color);
        GL11.glPopMatrix();
    }

    private void drawHeader() {
        drawRect(0, 0, width, HEADER, 0xF0181C24);
        drawRect(0, HEADER, width, HEADER + 1, 0xFF3A4150);
        String title = I18n.format("aegraph.gui.title");
        if (targetNode != null && targetNode.stack != null) {
            title += ": " + targetNode.stack.getDisplayName() + " ×" + fmtAmount(targetNode, data.getLong("amount"));
        }
        if (data.hasKey("fallback")) {
            title += "  " + EnumChatFormatting.RED + I18n.format("aegraph.gui.fallback",
                    I18n.format(data.getString("fallback"), data.getString("fallbackArg")));
        } else if (data.getBoolean("preview")) {
            title += "  " + EnumChatFormatting.YELLOW + I18n.format("aegraph.gui.preview");
        } else if (data.getBoolean("running")) {
            title += "  " + EnumChatFormatting.GREEN + I18n.format("aegraph.gui.running");
        }
        fontRendererObj.drawStringWithShadow(title, 6, 5, 0xFFFFFF);

        long runs = 0;
        for (final Node r : recipeNodes) runs += r.runs;
        String stats = I18n.format("aegraph.gui.stats", fmt(data.getLong("takeItems")), fmtFluid(data.getLong("takeFluid")),
                recipeNodes.size(), fmt(runs));
        if (data.hasKey("stockItems")) {
            stats += "   " + EnumChatFormatting.GRAY + I18n.format("aegraph.gui.stock", fmt(data.getLong("stockItems")),
                    fmtFluid(data.getLong("stockFluid")))
                    + (data.getBoolean("stockMissing") ? " " + I18n.format("aegraph.gui.stockMissing") : "");
        }
        if (data.getBoolean("missing")) stats += "   " + EnumChatFormatting.RED + I18n.format("aegraph.gui.missing");
        fontRendererObj.drawStringWithShadow(stats, 6, 17, 0xB8C0CC);
    }

    private void drawLegend() {
        final int y = height - 12;
        drawRect(0, y - 3, width, height, 0xE0181C24);
        int x = 6;
        x = legend(x, y, C_TARGET, "aegraph.gui.legend.target");
        x = legend(x, y, C_RAW, "aegraph.gui.legend.raw");
        x = legend(x, y, C_RECIPE, "aegraph.gui.legend.pattern");
        x = legend(x, y, C_SURPLUS, "aegraph.gui.legend.surplus");
        x = legend(x, y, C_MISSING, "aegraph.gui.legend.missing");
        x = legend(x, y, C_LOOP, "aegraph.gui.legend.loop");
        final String help = I18n.format("aegraph.gui.help");
        fontRendererObj.drawString(help, Math.max(x + 10, width - fontRendererObj.getStringWidth(help) - 6), y, 0x8A93A0);
    }

    private int legend(int x, int y, int color, String key) {
        drawRect(x, y + 1, x + 7, y + 8, 0xFF000000 | color);
        final String text = I18n.format(key);
        fontRendererObj.drawString(text, x + 10, y, 0xC8CCD2);
        return x + 10 + fontRendererObj.getStringWidth(text) + 10;
    }

    private void drawTooltip(Node n, int mouseX, int mouseY) {
        final List<String> lines = new ArrayList<>();
        if (n.recipe && n.substitution) {
            lines.add(EnumChatFormatting.LIGHT_PURPLE + I18n.format("aegraph.gui.substitution"));
            for (final long[] e : n.in) lines.add("  " + name(itemNodes.get((int) e[0])));
            lines.add(I18n.format("aegraph.gui.runs", fmt(n.runs)));
        } else if (n.recipe) {
            lines.add(EnumChatFormatting.LIGHT_PURPLE + I18n.format(n.crafting ? "aegraph.gui.craftPattern"
                    : "aegraph.gui.processPattern") + (n.stack != null ? ": " + n.stack.getDisplayName() : ""));
            lines.add(I18n.format("aegraph.gui.runs", fmt(n.runs)));
            if (n.remaining >= 0) lines.add(EnumChatFormatting.GREEN + I18n.format("aegraph.gui.remaining", fmt(n.remaining)));
            lines.add(EnumChatFormatting.GRAY + I18n.format("aegraph.gui.perRunIn"));
            for (final long[] e : n.in) lines.add("  " + fmtAmount(itemNodes.get((int) e[0]), e[1]) + "  " + name(itemNodes.get((int) e[0])));
            lines.add(EnumChatFormatting.GRAY + I18n.format("aegraph.gui.perRunOut"));
            for (final long[] e : n.out) lines.add("  " + fmtAmount(itemNodes.get((int) e[0]), e[1]) + "  " + name(itemNodes.get((int) e[0])));
        } else {
            lines.add(name(n));
            if (n.any) lines.add(EnumChatFormatting.LIGHT_PURPLE + I18n.format("aegraph.gui.any"));
            if (n == targetNode) lines.add(EnumChatFormatting.YELLOW + I18n.format("aegraph.gui.target", fmtAmount(n, data.getLong("amount"))));
            if (n.take > 0) lines.add(EnumChatFormatting.AQUA + I18n.format("aegraph.gui.take", fmtAmount(n, n.take)));
            if (n.missing > 0) lines.add(EnumChatFormatting.RED + I18n.format("aegraph.gui.lack", fmtAmount(n, n.missing)));
            if (n.produced > 0) lines.add(I18n.format("aegraph.gui.produced", fmtAmount(n, n.produced)));
            if (n.consumed > 0) lines.add(I18n.format("aegraph.gui.consumed", fmtAmount(n, n.consumed)));
            final long surplus = surplus(n);
            if (n != targetNode && surplus > 0) lines.add(EnumChatFormatting.GREEN + I18n.format("aegraph.gui.surplus", fmtAmount(n, surplus)));
        }
        drawHoveringText(lines, mouseX, mouseY, fontRendererObj);
    }

    private static String name(Node n) {
        if (n.stack == null) return "?";
        try {
            return n.stack.getDisplayName();
        } catch (Throwable t) {
            return String.valueOf(n.stack.getItem());
        }
    }

    private static String fmtAmount(Node n, long amount) {
        return n != null && n.fluid ? fmtFluid(amount) : fmt(amount);
    }

    private static String fmtFluid(long mb) {
        if (mb < 1000) return mb + " mB";
        return trim(mb / 1000.0) + " B";
    }

    private static String fmt(long v) {
        if (v < 10_000) return Long.toString(v);
        if (v < 1_000_000) return trim(v / 1000.0) + "k";
        if (v < 1_000_000_000L) return trim(v / 1_000_000.0) + "M";
        return trim(v / 1_000_000_000.0) + "G";
    }

    private static String trim(double d) {
        final String s = String.format(java.util.Locale.ROOT, "%.1f", d);
        return s.endsWith(".0") ? s.substring(0, s.length() - 2) : s;
    }
}
