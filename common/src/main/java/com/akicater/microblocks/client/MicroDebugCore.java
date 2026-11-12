package com.akicater.microblocks.client;

import com.akicater.lib.client.Draw;
import com.akicater.lib.client.Overlay;
import com.akicater.lib.client.QuadData;
import com.akicater.lib.client.Sprites;
import com.akicater.microblocks.MicroBlockEntity;
import com.akicater.microblocks.MicroConfig;
import com.akicater.microblocks.MicroMesh;
import com.akicater.microblocks.Part;
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.chunk.LevelChunk;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.List;

/**
 * The check of the greedy mesh (switched on in the settings): every rectangle that the chunk mesh gets, filled with a colour of its own (and outlined), and the parts in grey, around the
 * microblocks that are near the player. A rectangle that is far bigger than it needs to be, or two that could be one, show up at once.
 */
public final class MicroDebugCore implements Overlay.Painter {
    private static final int RADIUS = 24;

    /** A colour from the hue (0..360), the saturation and the value, as 0xRRGGBB. */
    private static int rgb(double hue, double s, double v) {
        double c = v * s, x = c * (1 - Math.abs((hue / 60) % 2 - 1)), m = v - c;
        double r, g, b;
        int sector = (int) (hue / 60) % 6;
        switch (sector) {
            case 0 -> { r = c; g = x; b = 0; }
            case 1 -> { r = x; g = c; b = 0; }
            case 2 -> { r = 0; g = c; b = x; }
            case 3 -> { r = 0; g = x; b = c; }
            case 4 -> { r = x; g = 0; b = c; }
            default -> { r = c; g = 0; b = x; }
        }
        return (int) Math.round((r + m) * 255) << 16 | (int) Math.round((g + m) * 255) << 8 | (int) Math.round((b + m) * 255);
    }

    @Override
    public void paint(PoseStack poseStack, Overlay.Sink sink, Vec3 camera) {
        if (!MicroConfig.debugMesh) return;
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.level == null) return;
        final List<Object[]> lines = new ArrayList<>();      // {AABB, color}
        final List<Object[]> fills = new ArrayList<>();      // {QuadData, ox, oy, oz, color}
        final net.minecraft.client.renderer.texture.TextureAtlasSprite white = Sprites.spriteOf(net.minecraft.world.level.block.Blocks.SNOW_BLOCK, net.minecraft.core.Direction.UP);
        BlockPos at = mc.player.blockPosition();
        for (int cx = (at.getX() - RADIUS) >> 4; cx <= (at.getX() + RADIUS) >> 4; cx++) {
            for (int cz = (at.getZ() - RADIUS) >> 4; cz <= (at.getZ() + RADIUS) >> 4; cz++) {
                LevelChunk chunk = mc.level.getChunk(cx, cz);
                for (BlockEntity be : new ArrayList<>(chunk.getBlockEntities().values())) {
                    if (!(be instanceof MicroBlockEntity micro) || micro.getBlockPos().distSqr(at) > RADIUS * RADIUS) continue;
                    BlockPos p = micro.getBlockPos();
                    double ox = p.getX() - camera.x, oy = p.getY() - camera.y, oz = p.getZ() - camera.z;
                    int index = 0;
                    for (MicroMesh.Quad q : micro.mesh().quads) {
                        // the golden angle between one face and the next of the same block, and the position of the block as the start: neighbours never look alike
                        int mixed = p.getX() * 73856093 ^ p.getY() * 19349663 ^ p.getZ() * 83492791;
                        mixed ^= mixed >>> 16; mixed *= 0x45D9F3B; mixed ^= mixed >>> 16;
                        index++;
                        double hue = ((index - 1) * 137.508 + (mixed & 0x7FFF) % 360) % 360;
                        float bright = 0.70f + 0.30f * ((q.face().ordinal() + index) % 3) / 2f;
                        int color = 0xFF000000 | rgb(hue, 0.75, bright);
                        double off = 0.003 * (q.face().getStepX() + q.face().getStepY() + q.face().getStepZ());     // a hair off the face, so that it does not hide in it
                        QuadData flat = MicroGeometry.quad(q);
                        float[] pos = flat.pos().clone();
                        // a hair off the face (it does not hide in it) and a hair in from the edges (the neighbours do not fight over them)
                        float mx = (q.minX() + q.maxX()) / 32f, my = (q.minY() + q.maxY()) / 32f, mz = (q.minZ() + q.maxZ()) / 32f;
                        float[] mid = {mx, my, mz};
                        for (int v = 0; v < 4; v++) {
                            for (int axis = 0; axis < 3; axis++) {
                                float value = pos[v * 3 + axis];
                                int step = axis == 0 ? q.face().getStepX() : axis == 1 ? q.face().getStepY() : q.face().getStepZ();
                                pos[v * 3 + axis] = step != 0 ? value + step * 0.004f : value + (value > mid[axis] ? -0.003f : 0.003f);
                            }
                        }
                        QuadData shifted = new QuadData(flat.face(), pos, flat.uv(), flat.sprite(), false, flat.block());
                        if (!MicroPreviewCore.facesCamera(shifted, ox, oy, oz)) continue;      // the faces on the far side are not drawn
                        float u = (white.getU0() + white.getU1()) / 2, vv = (white.getV0() + white.getV1()) / 2;
                        fills.add(new Object[]{new QuadData(flat.face(), pos, new float[]{u, vv, u, vv, u, vv, u, vv}, white, false, flat.block()), ox, oy, oz, 0xB0000000 | (color & 0xFFFFFF)});
                        lines.add(new Object[]{new AABB(q.minX() / 16.0, q.minY() / 16.0, q.minZ() / 16.0, q.maxX() / 16.0, q.maxY() / 16.0, q.maxZ() / 16.0).move(ox + (q.face().getStepX() != 0 ? off : 0), oy + (q.face().getStepY() != 0 ? off : 0), oz + (q.face().getStepZ() != 0 ? off : 0)), color});
                    }
                    for (Part part : micro.parts()) if (part.group == null) lines.add(new Object[]{part.box().move(ox, oy, oz).inflate(0.004), 0xFF808080});      // the parts, dim; a box across block spaces is one piece of faces
                }
            }
        }
        if (lines.isEmpty()) return;
        sink.geometry(Draw.translucent(), (pose, consumer) -> {
            for (Object[] f : fills) Draw.ghost(pose, consumer, (QuadData) f[0], (Double) f[1], (Double) f[2], (Double) f[3], (Integer) f[4]);
        });
        sink.geometry(Draw.lines(), (pose, consumer) -> {
            for (Object[] line : lines) Draw.outline(pose, consumer, (AABB) line[0], (Integer) line[1]);
        });
    }
}
