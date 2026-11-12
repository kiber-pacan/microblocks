package com.akicater.microblocks.client;

import com.akicater.lib.client.ClientModels;
import com.akicater.lib.client.QuadData;
import com.akicater.lib.client.QuadSet;
import com.akicater.lib.client.Sprites;
import com.akicater.microblocks.MicroBlockEntity;
import com.akicater.microblocks.MicroMesh;
import com.akicater.microblocks.MicroMaterials;
import com.akicater.microblocks.Part;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.state.BlockState;
#if MC_VER >= V26_1
import net.minecraft.client.renderer.block.BlockAndTintGetter;
#else
import net.minecraft.world.level.BlockAndTintGetter;
#endif

import java.util.ArrayList;
import java.util.List;

/**
 * The faces of the holder block: the greedy mesh of its parts, kept in the block entity until the parts change (or the sprites are reloaded or a neighbour changes).
 * A face on the border of the block space that lies against parts of a neighbouring block of microblocks (all of it, with the same material or an opaque one) is left out:
 * it would be inside.
 */
public final class MicroBlockQuads implements ClientModels.BlockQuads {
    private record Cached(int generation, int neighbours, QuadSet set) {}

    @Override
    public QuadSet quads(BlockAndTintGetter level, BlockPos pos, BlockState state) {
        long started = com.akicater.microblocks.MicroTiming.start();
        try {
            return make(level, pos);
        } finally {
            com.akicater.microblocks.MicroTiming.end("the faces of a block at " + pos, started);
        }
    }

    private QuadSet make(BlockAndTintGetter level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof MicroBlockEntity be) || be.isEmpty()) return null;
        int generation = Sprites.generation();
        // what the neighbours hold: when it changes the faces on the borders are made again
        int signature = 1;
        MicroBlockEntity[] around = new MicroBlockEntity[6];
        for (Direction d : Direction.values()) {
            if (level.getBlockEntity(pos.relative(d)) instanceof MicroBlockEntity n) {
                around[d.ordinal()] = n;
                signature = signature * 31 + System.identityHashCode(n.parts());
            } else {
                signature = signature * 31 + d.ordinal();
            }
        }
        if (be.renderCache instanceof Cached cached && cached.generation() == generation && cached.neighbours() == signature) return cached.set();
        List<QuadData> out = new ArrayList<>();
        @SuppressWarnings("unchecked") List<MicroMesh.Quad>[] border = new List[6];
        for (MicroMesh.Quad quad : be.mesh().quads) {
            int f = quad.face().ordinal();
            if (quad.onBoundary() && around[f] != null) {      // against a neighbour of microblocks: cut by what the neighbour covers, below
                if (border[f] == null) border[f] = new ArrayList<>();
                border[f].add(quad);
                continue;
            }
            MicroGeometry.add(quad, out);
        }
        for (int f = 0; f < 6; f++) if (border[f] != null) for (MicroMesh.Quad quad : cut(border[f], around[f])) MicroGeometry.add(quad, out);
        QuadSet set = new QuadSet(out);
        be.renderCache = new Cached(generation, signature, set);
        return set;
    }

    /**
     * The faces of one border of the block space, without the cells that the neighbour hides (the same material, or an opaque one), merged again into as few
     * rectangles as the partition finds. Both blocks would draw the whole border otherwise, one face on top of the other, wherever one cell of it is open.
     */
    private static List<MicroMesh.Quad> cut(List<MicroMesh.Quad> quads, MicroBlockEntity neighbour) {
        Direction face = quads.get(0).face();
        int axis = face.getAxis().ordinal();
        int a = (axis + 1) % 3, b = (axis + 2) % 3;
        int plane = face.getAxisDirection().getStep() > 0 ? 0 : 15;      // the cells of the neighbour that touch this border
        // what the neighbour shows in every cell of that layer: the part that comes later wins, as in the mesh
        net.minecraft.world.level.block.Block[] top = new net.minecraft.world.level.block.Block[256];
        boolean any = false;
        int[] c = new int[3];
        c[axis] = plane;
        for (Part p : neighbour.parts()) {
            if (p.min(axis) > plane || p.max(axis) <= plane) continue;
            for (int i = p.min(a); i < p.max(a); i++) for (int j = p.min(b); j < p.max(b); j++) {
                c[a] = i; c[b] = j;
                if (p.mask == null || p.has(c[0], c[1], c[2])) { top[i * 16 + j] = p.material; any = true; }
            }
        }
        if (!any) return quads;
        List<MicroMesh.Quad> kinds = new ArrayList<>(2);      // faces that may merge: one material, turn, shift and side of the texture
        int[] mask = new int[256];
        boolean hid = false;
        for (MicroMesh.Quad q : quads) {
            int id = 0;
            for (int k = 0; k < kinds.size() && id == 0; k++) {
                MicroMesh.Quad o = kinds.get(k);
                if (o.material() == q.material() && o.orient() == q.orient() && o.tx() == q.tx() && o.ty() == q.ty() && o.tz() == q.tz() && o.inner() == q.inner()) id = k + 1;
            }
            if (id == 0) { kinds.add(q); id = kinds.size(); }
            int[] min = {q.minX(), q.minY(), q.minZ()}, max = {q.maxX(), q.maxY(), q.maxZ()};
            for (int i = min[a]; i < max[a]; i++) for (int j = min[b]; j < max[b]; j++) {
                net.minecraft.world.level.block.Block over = top[i * 16 + j];
                if (over != null && (over == q.material() || com.akicater.lib.Compat.solidRender(over.defaultBlockState()))) hid = true;
                else mask[i * 16 + j] = id;
            }
        }
        if (!hid) return quads;
        List<MicroMesh.Quad> out = new ArrayList<>();
        int at = quads.get(0).min(axis);
        for (com.akicater.microblocks.MicroPartition.Rect r : com.akicater.microblocks.MicroPartition.best(mask)) {
            MicroMesh.Quad k = kinds.get(r.v() - 1);
            int[] min = new int[3], max = new int[3];
            min[axis] = at; max[axis] = at;
            min[a] = r.a(); max[a] = r.a() + r.h();
            min[b] = r.b(); max[b] = r.b() + r.w();
            out.add(new MicroMesh.Quad(face, min[0], min[1], min[2], max[0], max[1], max[2], k.material(), k.orient(), k.tx(), k.ty(), k.tz(), true, k.inner()));
        }
        return out;
    }
}
