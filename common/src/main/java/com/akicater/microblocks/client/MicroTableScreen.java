package com.akicater.microblocks.client;

import com.akicater.microblocks.MicroTableMenu;
#if MC_VER >= V26_1
import net.minecraft.client.gui.GuiGraphicsExtractor;
#elif MC_VER >= V1_20_1
import net.minecraft.client.gui.GuiGraphics;
#else
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
import net.minecraft.client.gui.Font;
#endif
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.inventory.AbstractContainerScreen;
import net.minecraft.network.chat.Component;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.Slot;

import java.util.ArrayList;
import java.util.List;

/** The screen of both tables: the cutting (axis, place, cut) for the iron one, the prefabs (blueprint, ingredients, result) for the blue one. */
public class MicroTableScreen extends AbstractContainerScreen<MicroTableMenu> {
    private final List<Button> cutButtons = new ArrayList<>();
    private Button[] axisButtons;

    public MicroTableScreen(MicroTableMenu menu, Inventory inventory, Component title) {
        #if MC_VER >= V26_1
        super(menu, inventory, title, 176, 204);
        #else
        super(menu, inventory, title);
        this.imageWidth = 176;
        this.imageHeight = 204;
        #endif
    }

    private Button press(int x, int y, int w, int h, Component label, int id) {
        #if MC_VER >= V1_19_4
        Button button = Button.builder(label, b -> this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, id)).bounds(this.leftPos + x, this.topPos + y, w, h).build();
        #else
        Button button = new Button(this.leftPos + x, this.topPos + y, w, h, label, b -> this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, id));
        #endif
        return addRenderableWidget(button);
    }

    @Override
    protected void init() {
        super.init();
        cutButtons.clear();
        axisButtons = new Button[3];
        String[] names = {"X", "Y", "Z"};
        for (int i = 0; i < 3; i++) {
            axisButtons[i] = press(7 + 11 * (i % 3) - 0, 74, 11, 13, com.akicater.lib.Compat.literal(names[i]), MicroTableMenu.BTN_AXIS + i);
            cutButtons.add(axisButtons[i]);
        }
        cutButtons.add(press(40, 96, 16, 14, com.akicater.lib.Compat.literal("<"), MicroTableMenu.BTN_POS_MINUS));
        cutButtons.add(press(58, 96, 16, 14, com.akicater.lib.Compat.literal(">"), MicroTableMenu.BTN_POS_PLUS));
        cutButtons.add(press(78, 96, 52, 14, com.akicater.lib.Compat.translatable("microblocks.table.do_cut"), MicroTableMenu.BTN_DO_CUT));
    }

    // ---- the turning box: drag to turn it, left click on a face for the axis, right click for the place of the cut, scroll for a sixteenth
    private final MicroCutPreview preview = new MicroCutPreview();
    private boolean pressed, moved;

    private MicroTableMenu.Source source() {
        return MicroTableMenu.source(this.menu.table().getItem(MicroTableMenu.IN));
    }

    /** The axis of the world that the axis {@code i} of the piece lies on (the box is drawn as it will be put down). */
    private int worldAxis(int i) {
        MicroTableMenu.Source source = source();
        return source == null ? i : com.akicater.microblocks.Part.perm(source.rotation())[i];
    }

    private int pieceAxis(int world) {
        for (int i = 0; i < 3; i++) if (worldAxis(i) == world) return i;
        return world;
    }

    private void button(int id) {
        this.minecraft.gameMode.handleInventoryButtonClick(this.menu.containerId, id);
    }

    private boolean onClick(double x, double y, int button) {
        if (this.menu.mode() != MicroTableMenu.MODE_CUT || source() == null || !preview.inside(x, y)) return false;
        if (button == 0) { pressed = true; moved = false; return true; }
        if (button == 1) {
            MicroCutPreview.Pick pick = preview.pick(x, y, worldAxis(this.menu.axis()));
            if (pick != null && pick.along() > 0) button(MicroTableMenu.BTN_POS_SET + pick.along());
            return true;
        }
        return false;
    }

    private boolean onDrag(double dx, double dy) {
        if (!pressed) return false;
        if (Math.abs(dx) + Math.abs(dy) > 0.5) moved = true;
        preview.turn(dx, dy);
        return true;
    }

    private boolean onRelease(double x, double y) {
        if (!pressed) return false;
        pressed = false;
        if (!moved) {
            MicroCutPreview.Pick pick = preview.pick(x, y, worldAxis(this.menu.axis()));
            if (pick != null && pick.faceAxis() != worldAxis(this.menu.axis())) button(MicroTableMenu.BTN_AXIS + pieceAxis(pick.faceAxis()));
        }
        return true;
    }

    private boolean onScroll(double x, double y, double delta) {
        if (this.menu.mode() != MicroTableMenu.MODE_CUT || !preview.inside(x, y) || delta == 0) return false;
        button(delta > 0 ? MicroTableMenu.BTN_POS_PLUS : MicroTableMenu.BTN_POS_MINUS);
        return true;
    }

    #if MC_VER >= V1_21_9
    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        return onClick(event.x(), event.y(), event.button()) || super.mouseClicked(event, doubleClick);
    }

    @Override
    public boolean mouseDragged(net.minecraft.client.input.MouseButtonEvent event, double dx, double dy) {
        return onDrag(dx, dy) || super.mouseDragged(event, dx, dy);
    }

    @Override
    public boolean mouseReleased(net.minecraft.client.input.MouseButtonEvent event) {
        return onRelease(event.x(), event.y()) || super.mouseReleased(event);
    }
    #else
    @Override
    public boolean mouseClicked(double x, double y, int button) {
        return onClick(x, y, button) || super.mouseClicked(x, y, button);
    }

    @Override
    public boolean mouseDragged(double x, double y, int button, double dx, double dy) {
        return onDrag(dx, dy) || super.mouseDragged(x, y, button, dx, dy);
    }

    @Override
    public boolean mouseReleased(double x, double y, int button) {
        return onRelease(x, y) || super.mouseReleased(x, y, button);
    }
    #endif

    #if MC_VER >= V1_20_4
    @Override
    public boolean mouseScrolled(double x, double y, double scrollX, double scrollY) {
        return onScroll(x, y, scrollY) || super.mouseScrolled(x, y, scrollX, scrollY);
    }
    #else
    @Override
    public boolean mouseScrolled(double x, double y, double delta) {
        return onScroll(x, y, delta) || super.mouseScrolled(x, y, delta);
    }
    #endif

    /** Draws the box in the panel (called from the background, with whatever the version fills a rectangle with). */
    private void drawPreview(MicroCutPreview.Fill fill) {
        if (this.menu.mode() != MicroTableMenu.MODE_CUT) return;
        MicroTableMenu.Source source = source();
        int[] world = new int[]{16, 16, 16};
        if (source != null) for (int i = 0; i < 3; i++) world[worldAxis(i)] = source.dims()[i];
        preview.layout(this.leftPos + 40, this.topPos + 17, 90, 77, world);
        if (source == null) {
            MicroCutPreview.sunk(fill, this.leftPos + 40, this.topPos + 17, 90, 77, 0xFF2E2E2E);
            return;
        }
        // the piece itself, in the middle of a block space, turned as it will be put down, with the part of the texture that it was cut from
        int x0 = (16 - world[0]) / 2, y0 = (16 - world[1]) / 2, z0 = (16 - world[2]) / 2;
        int orient = source.rotation() > 0 ? source.rotation() : com.akicater.microblocks.MicroItemData.orientation(source.dims(), world);
        com.akicater.microblocks.Part part = new com.akicater.microblocks.Part(x0, y0, z0, x0 + world[0], y0 + world[1], z0 + world[2], source.material(), orient).withInner(source.inner());
        if (source.off() != null) {
            int[] w = com.akicater.microblocks.MicroItemData.worldOffset(source.off(), orient);
            part = part.withShift(w[0] - x0, w[1] - y0, w[2] - z0);
        }
        preview.draw(fill, MicroGeometry.quads(com.akicater.microblocks.MicroMesh.build(List.of(part))), new double[]{x0 + world[0] / 2.0, y0 + world[1] / 2.0, z0 + world[2] / 2.0},
                worldAxis(this.menu.axis()), Math.min(this.menu.pos(), Math.max(1, this.menu.cutSize() - 1)));
    }

    private void updateButtons() {
        boolean cutting = this.menu.mode() == MicroTableMenu.MODE_CUT;
        for (Button button : cutButtons) button.visible = cutting;
        if (axisButtons != null) for (int i = 0; i < 3; i++) axisButtons[i].active = this.menu.axis() != i;       // the chosen axis is pressed in
    }

    #if MC_VER >= V26_1
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        updateButtons();
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    public void extractBackground(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractBackground(graphics, mouseX, mouseY, partialTick);
        panel(graphics);
    }

    @Override
    protected void extractLabels(GuiGraphicsExtractor graphics, int mouseX, int mouseY) {
        labels(graphics);
    }

    private void panel(GuiGraphicsExtractor graphics) {
    #elif MC_VER >= V1_20_1
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        updateButtons();
        super.render(graphics, mouseX, mouseY, partialTick);
    }

    @Override
    protected void renderBg(GuiGraphics graphics, float partialTick, int mouseX, int mouseY) {
        panel(graphics);
    }

    @Override
    protected void renderLabels(GuiGraphics graphics, int mouseX, int mouseY) {
        labels(graphics);
    }

    private void panel(GuiGraphics graphics) {
    #else
    /** The few drawing calls of the screen, as the game before 1.20 has them (static helpers of GuiComponent that take the pose). */
    private record Gfx(PoseStack pose) {
        void fill(int x0, int y0, int x1, int y1, int color) {
            GuiComponent.fill(pose, x0, y0, x1, y1, color);
        }

        void drawString(Font font, Component text, int x, int y, int color, boolean shadow) {
            font.draw(pose, text, x, y, color);
        }

        void drawCenteredString(Font font, Component text, int x, int y, int color) {
            GuiComponent.drawCenteredString(pose, font, text, x, y, color);
        }
    }

    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        updateButtons();
        super.render(pose, mouseX, mouseY, partialTick);
    }

    @Override
    protected void renderBg(PoseStack pose, float partialTick, int mouseX, int mouseY) {
        panel(new Gfx(pose));
    }

    @Override
    protected void renderLabels(PoseStack pose, int mouseX, int mouseY) {
        labels(new Gfx(pose));
    }

    private void panel(Gfx graphics) {
    #endif
        int x = this.leftPos, y = this.topPos;
        // the frame of the screens of the game: a black line with cut corners, light above and on the left, dark below and on the right
        int w = this.imageWidth, h = this.imageHeight;
        graphics.fill(x + 2, y, x + w - 3, y + 1, 0xFF000000);
        graphics.fill(x, y + 2, x + 1, y + h - 3, 0xFF000000);
        graphics.fill(x + 3, y + h - 1, x + w - 2, y + h, 0xFF000000);
        graphics.fill(x + w - 1, y + 3, x + w, y + h - 2, 0xFF000000);
        graphics.fill(x + 1, y + 1, x + 2, y + 2, 0xFF000000);
        graphics.fill(x + w - 3, y + 1, x + w - 2, y + 2, 0xFF000000);
        graphics.fill(x + w - 2, y + 2, x + w - 1, y + 3, 0xFF000000);
        graphics.fill(x + 1, y + h - 3, x + 2, y + h - 2, 0xFF000000);
        graphics.fill(x + 2, y + h - 2, x + 3, y + h - 1, 0xFF000000);
        graphics.fill(x + w - 2, y + h - 2, x + w - 1, y + h - 1, 0xFF000000);
        graphics.fill(x + 2, y + 1, x + w - 3, y + 3, 0xFFFFFFFF);
        graphics.fill(x + 1, y + 2, x + 3, y + h - 3, 0xFFFFFFFF);
        graphics.fill(x + 3, y + 3, x + 4, y + 4, 0xFFFFFFFF);
        graphics.fill(x + 3, y + h - 3, x + w - 2, y + h - 1, 0xFF555555);
        graphics.fill(x + w - 3, y + 3, x + w - 1, y + h - 2, 0xFF555555);
        graphics.fill(x + w - 4, y + h - 4, x + w - 3, y + h - 3, 0xFF555555);
        graphics.fill(x + 3, y + 3, x + w - 3, y + h - 3, 0xFFC6C6C6);
        graphics.fill(x + 3, y + 3, x + 4, y + 4, 0xFFFFFFFF);
        graphics.fill(x + w - 4, y + h - 4, x + w - 3, y + h - 3, 0xFF555555);
        graphics.fill(x + w - 3, y + 2, x + w - 2, y + 3, 0xFFC6C6C6);
        graphics.fill(x + 2, y + h - 3, x + 3, y + h - 2, 0xFFC6C6C6);
        for (Slot slot : this.menu.slots) {
            if (!slot.isActive()) continue;
            graphics.fill(slot.x + x - 1, slot.y + y - 1, slot.x + x + 17, slot.y + y + 17, 0xFF373737);
            graphics.fill(slot.x + x, slot.y + y, slot.x + x + 17, slot.y + y + 17, 0xFFFFFFFF);
            graphics.fill(slot.x + x, slot.y + y, slot.x + x + 16, slot.y + y + 16, 0xFF8B8B8B);
        }
        drawPreview(graphics::fill);
    }

    #if MC_VER >= V26_1
    private void labels(GuiGraphicsExtractor graphics) {
        graphics.text(this.font, this.title, 8, 5, 0xFF404040, false);
        graphics.text(this.font, this.playerInventoryTitle, 8, 111, 0xFF404040, false);
        if (this.menu.mode() == MicroTableMenu.MODE_CUT) {
            int size = this.menu.cutSize();
            int p = Math.min(this.menu.pos(), Math.max(1, size - 1));
            graphics.text(this.font, com.akicater.lib.Compat.literal(p + " + " + (size - p)), 43, 20, 0xFFFFFFFF, true);
        } else {
            int n = this.menu.prefabCount();
            graphics.text(this.font, n > 0 ? com.akicater.lib.Compat.translatable("microblocks.table.makes", n) : com.akicater.lib.Compat.translatable("microblocks.table.put_blueprint"), 8, 98, 0xFF404040, false);
        }
    }
    #else
    private void labels(#if MC_VER >= V1_20_1 GuiGraphics #else Gfx #endif graphics) {
        graphics.drawString(this.font, this.title, 8, 5, 0xFF404040, false);
        graphics.drawString(this.font, this.playerInventoryTitle, 8, 111, 0xFF404040, false);
        if (this.menu.mode() == MicroTableMenu.MODE_CUT) {
            int size = this.menu.cutSize();
            int p = Math.min(this.menu.pos(), Math.max(1, size - 1));
            graphics.drawString(this.font, com.akicater.lib.Compat.literal(p + " + " + (size - p)), 43, 20, 0xFFFFFFFF, true);
        } else {
            int n = this.menu.prefabCount();
            graphics.drawString(this.font, n > 0 ? com.akicater.lib.Compat.translatable("microblocks.table.makes", n) : com.akicater.lib.Compat.translatable("microblocks.table.put_blueprint"), 8, 98, 0xFF404040, false);
        }
    }
    #endif
}
