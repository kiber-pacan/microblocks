package com.akicater.microblocks.client;

import com.akicater.microblocks.MicroItem;
import com.akicater.microblocks.MicroItemData;
import com.akicater.microblocks.MicroSettings;

import net.minecraft.client.Minecraft;
import net.minecraft.world.item.ItemStack;

/**
 * The wheel (the key End, or whatever is put on it in the controls): how the box follows the click (free, snap, voxel), whether it may be put into others, and the kind of turn (automatic, as cut, upright, flat). Click a sector to choose, click outside the wheel or press Escape to close.
 */
public class MicroWheelScreen extends MicroScreenBase {
    private static final int SECTORS = 8;
    // placing: free, snap, voxel, replace; the kind of turn: automatic, as it was cut, upright, flat
    private static final String[] LABELS = {"Free", "Snap", "Voxel", "Overlap", "Auto turn", "As cut", "Upright", "Flat"};
    private static final String[] HINTS = {
            "Free: any sixteenth, also across blocks", "Snap: to the edges and the middle of a block", "Voxel: any sixteenth inside one block",
            "Overlap: nothing, replace (draws over what is there), fill (only the empty cells)",
            "Auto turn: the thin side to the surface, the long one along the edge or the view", "As cut: the sizes lie on x, y, z as they were cut",
            "Upright: the longest side up, the thin side to you", "Flat: the thinnest side up, the long side along the view"};

    public MicroWheelScreen() {
        super(com.akicater.lib.Compat.literal("Microblocks"));
    }

    @Override
    public boolean isPauseScreen() {
        return false;
    }

    private boolean active(int sector) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return false;
        MicroSettings.State state = MicroSettings.of(mc.player);
        if (sector < 3) return state.mode == sector;
        if (sector == 3) return state.replace || state.fill;
        return state.rotMode == sector - 4;
    }

    private void choose(int sector) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        MicroSettings.State state = MicroSettings.of(mc.player);
        if (sector < 3) {
            MicroNet.change(MicroSettings.KIND_MODE, sector);
        } else if (sector == 3) {
            MicroNet.change(MicroSettings.KIND_REPLACE, state.replace ? 2 : state.fill ? 0 : 1);      // refuse, replace, fill
        } else {
            // a kind of turn is for a box whose turn was not chosen: the turn of the box in the hand (R) is let go
            ItemStack stack = mc.player.getMainHandItem();
            if (stack.getItem() instanceof MicroItem && MicroItemData.rotation(stack) != 0) {
                MicroItemData.setRotation(stack, 0);
                MicroNet.send(MicroSettings.KIND_ROTATION, 0);
            }
            MicroNet.change(MicroSettings.KIND_ROTMODE, sector - 4);
        }
    }

    private int cx() { return this.width / 2; }
    private int cy() { return this.height / 2; }
    private int outer() { return Math.max(60, Math.min(this.height / 2 - 30, 110)); }      // room under the wheel for the hint
    private int inner() { return outer() * 2 / 5; }

    /** The sector under the point, -1 for none. */
    private int sectorAt(double x, double y) {
        double dx = x - cx(), dy = y - cy(), r = Math.hypot(dx, dy);
        if (r < inner() || r > outer()) return -1;
        double angle = Math.toDegrees(Math.atan2(dy, dx)) + 90 + 360.0 / SECTORS / 2;      // sector 0 is at the top, clockwise
        angle = ((angle % 360) + 360) % 360;
        return (int) (angle / (360.0 / SECTORS)) % SECTORS;
    }

    @Override
    protected void paint(Gfx g, int mouseX, int mouseY) {
        int hover = sectorAt(mouseX, mouseY);
        double step = Math.toRadians(360.0 / SECTORS);
        for (int i = 0; i < SECTORS; i++) {
            double a0 = -Math.PI / 2 + (i - 0.5) * step + 0.03, a1 = -Math.PI / 2 + (i + 0.5) * step - 0.03;
            double[][] quad = {
                    {cx() + Math.cos(a0) * inner(), cy() + Math.sin(a0) * inner()}, {cx() + Math.cos(a1) * inner(), cy() + Math.sin(a1) * inner()},
                    {cx() + Math.cos(a1) * outer(), cy() + Math.sin(a1) * outer()}, {cx() + Math.cos(a0) * outer(), cy() + Math.sin(a0) * outer()}};
            boolean on = active(i);
            int color = on ? (i == hover ? 0xF0D8A040 : 0xE0B08020) : (i == hover ? 0xE0707070 : 0xD0303030);
            MicroCutPreview.polygon(g::fill, quad, color);
            double am = -Math.PI / 2 + i * step;
            double mid = (inner() + outer()) / 2.0;
            g.center(LABELS[i], (int) Math.round(cx() + Math.cos(am) * mid), (int) Math.round(cy() + Math.sin(am) * mid) - 4, on ? 0xFFFFFFFF : 0xFFC0C0C0);
        }
        // in the middle only what fits there: the way of placing and the overlap; the long hint goes under the wheel, in lines as wide as the screen lets
        MicroSettings.State state = Minecraft.getInstance().player == null ? null : MicroSettings.of(Minecraft.getInstance().player);
        if (state != null) {
            g.center(MicroSettings.Mode.values()[state.mode].name().toLowerCase(), cx(), cy() - 9, 0xFFFFFFFF);
            g.center(state.replace ? "replace" : state.fill ? "fill" : "no overlap", cx(), cy() + 1, 0xFFB0B0B0);
        }
        if (hover >= 0) {
            int y = cy() + outer() + 6;
            for (String line : wrap(HINTS[hover], this.width - 16)) {
                g.center(line, cx(), y, 0xFFFFFFFF);
                y += 10;
            }
        }
    }

    /** The text in lines that are not wider than {@code width}, broken between words. */
    private java.util.List<String> wrap(String text, int width) {
        java.util.List<String> lines = new java.util.ArrayList<>();
        String line = "";
        for (String word : text.split(" ")) {
            String longer = line.isEmpty() ? word : line + " " + word;
            if (!line.isEmpty() && this.font.width(longer) > width) {
                lines.add(line);
                line = word;
            } else {
                line = longer;
            }
        }
        if (!line.isEmpty()) lines.add(line);
        return lines;
    }

    #if MC_VER >= V1_21_9
    @Override
    public boolean mouseClicked(net.minecraft.client.input.MouseButtonEvent event, boolean doubleClick) {
        return click(event.x(), event.y()) || super.mouseClicked(event, doubleClick);
    }
    #else
    @Override
    public boolean mouseClicked(double x, double y, int button) {
        return click(x, y) || super.mouseClicked(x, y, button);
    }
    #endif

    private boolean click(double x, double y) {
        int sector = sectorAt(x, y);
        if (sector >= 0) {
            choose(sector);
            return true;
        }
        if (Math.hypot(x - cx(), y - cy()) > outer()) {
            this.onClose();
            return true;
        }
        return false;
    }
}
