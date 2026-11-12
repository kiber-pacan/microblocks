package com.akicater.microblocks.client;

import com.akicater.lib.client.QuadData;
import com.akicater.lib.client.QuadSet;
import com.akicater.lib.client.Sprites;
import com.akicater.microblocks.MicroMesh;
import net.minecraft.client.renderer.texture.TextureAtlasSprite;
import net.minecraft.core.Direction;

import java.util.ArrayList;
import java.util.List;

/**
 * The faces of a {@link MicroMesh} as plain numbers: four corners (counter-clockwise seen from outside, in block space 0..1) and their texture coordinates in the
 * sprite, locked to the block space so that a slab shows the lower part of the texture. The library turns these into what the renderer of every loader takes.
 */
public final class MicroGeometry {
    private MicroGeometry() {}

    /** The faces of a mesh as the set that the library draws. */
    public static QuadSet set(MicroMesh mesh) {
        return new QuadSet(quads(mesh));
    }

    public static List<QuadData> quads(MicroMesh mesh) {
        List<QuadData> out = new ArrayList<>(mesh.quads.size());
        for (MicroMesh.Quad quad : mesh.quads) add(quad, out);
        return out;
    }

    /** The face, and after it what the model of the material draws over that side (the green edge on the side of a grass block, coloured by the place). */
    public static void add(MicroMesh.Quad quad, List<QuadData> out) {
        QuadData base = quad(quad);
        out.add(base);
        if (quad.inner()) return;
        TextureAtlasSprite from = base.sprite();
        for (Sprites.Layer layer : Sprites.overlays(quad.material(), shown(quad))) {
            TextureAtlasSprite to = layer.sprite();
            float[] uv = new float[8];
            for (int i = 0; i < 4; i++) {
                uv[i * 2] = to.getU0() + (base.uv()[i * 2] - from.getU0()) / (from.getU1() - from.getU0()) * (to.getU1() - to.getU0());
                uv[i * 2 + 1] = to.getV0() + (base.uv()[i * 2 + 1] - from.getV0()) / (from.getV1() - from.getV0()) * (to.getV1() - to.getV0());
            }
            out.add(new QuadData(base.face(), base.pos(), uv, to, base.onBoundary(), base.block(), 0, layer.tint()));
        }
    }

    /** The side of the block of the material that the face shows (the part may lie turned). */
    private static Direction shown(MicroMesh.Quad quad) {
        if (quad.inner()) return Direction.DOWN;
        int[] perm = com.akicater.microblocks.Part.perm(quad.orient());
        int worldAxis = quad.face().getAxis().ordinal();
        int localAxis = 0;
        for (int i = 0; i < 3; i++) if (perm[i] == worldAxis) localAxis = i;
        return Direction.fromAxisAndDirection(Direction.Axis.values()[localAxis], quad.face().getAxisDirection());
    }

    public static QuadData quad(MicroMesh.Quad quad) {
        float x0 = quad.minX() / 16f, y0 = quad.minY() / 16f, z0 = quad.minZ() / 16f;
        float x1 = quad.maxX() / 16f, y1 = quad.maxY() / 16f, z1 = quad.maxZ() / 16f;
        float[] pos = switch (quad.face()) {
            case UP -> new float[]{x0, y1, z0, x0, y1, z1, x1, y1, z1, x1, y1, z0};
            case DOWN -> new float[]{x0, y0, z1, x0, y0, z0, x1, y0, z0, x1, y0, z1};
            case NORTH -> new float[]{x1, y1, z0, x1, y0, z0, x0, y0, z0, x0, y1, z0};
            case SOUTH -> new float[]{x0, y1, z1, x0, y0, z1, x1, y0, z1, x1, y1, z1};
            case WEST -> new float[]{x0, y1, z0, x0, y0, z0, x0, y0, z1, x0, y1, z1};
            default -> new float[]{x1, y1, z1, x1, y0, z1, x1, y0, z0, x1, y1, z0};
        };
        // the part may lie turned: its texture is that of the block it was cut from, so the face that is on the world's face D is the face of the block that
        // lies on that axis; the coordinates for the texture are taken in the frame of the block
        int[] perm = com.akicater.microblocks.Part.perm(quad.orient());
        int worldAxis = quad.face().getAxis().ordinal();
        int localAxis = 0;
        for (int i = 0; i < 3; i++) if (perm[i] == worldAxis) localAxis = i;
        Direction local = Direction.fromAxisAndDirection(Direction.Axis.values()[localAxis], quad.face().getAxisDirection());
        int[] wmin = {quad.minX(), quad.minY(), quad.minZ()}, wmax = {quad.maxX(), quad.maxY(), quad.maxZ()};
        int[] lmin = {wmin[perm[0]], wmin[perm[1]], wmin[perm[2]]}, lmax = {wmax[perm[0]], wmax[perm[1]], wmax[perm[2]]};
        // the inside of a block that was cut shows the underside of its texture (the dirt of grass, the planks of a bookshelf)
        Direction shown = quad.inner() ? Direction.DOWN : local;
        TextureAtlasSprite sprite = Sprites.spriteOf(quad.material(), shown);
        float[] uv = new float[8];
        // the grain of a plank runs along the long side of a thin piece (a post, a beam): the texture is turned when the face is much longer across than along
        boolean vertical = local == Direction.UP || local == Direction.DOWN;
        int du = local.getAxis() == Direction.Axis.Z ? lmax[0] - lmin[0] : vertical ? lmax[0] - lmin[0] : lmax[2] - lmin[2];
        int dv = vertical ? lmax[2] - lmin[2] : lmax[1] - lmin[1];
        boolean turn = dv > 2 * du && com.akicater.lib.Compat.blockId(quad.material()).endsWith("_planks");
        for (int i = 0; i < 4; i++) {
            float wx = pos[i * 3], wy = pos[i * 3 + 1], wz = pos[i * 3 + 2];
            float[] w = {wx + quad.tx() / 16f, wy + quad.ty() / 16f, wz + quad.tz() / 16f};
            float x = w[perm[0]], y = w[perm[1]], z = w[perm[2]];
            float u, v;
            switch (local) {
                case UP -> { u = x; v = z; }
                case DOWN -> { u = x; v = 1 - z; }
                case NORTH -> { u = 1 - x; v = 1 - y; }
                case SOUTH -> { u = x; v = 1 - y; }
                case WEST -> { u = z; v = 1 - y; }
                default -> { u = 1 - z; v = 1 - y; }
            }
            if (turn) { float swap = u; u = v; v = swap; }
            uv[i * 2] = sprite.getU0() + u * (sprite.getU1() - sprite.getU0());
            uv[i * 2 + 1] = sprite.getV0() + v * (sprite.getV1() - sprite.getV0());
        }
        // the top of grass, leaves: coloured by the place, like the block itself
        return new QuadData(quad.face(), pos, uv, sprite, quad.onBoundary(), quad.material(), 0, Sprites.tintOf(quad.material(), shown));
    }
}
