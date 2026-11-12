package com.akicater.microblocks.client;

import com.akicater.lib.Compat;
import com.akicater.lib.client.ItemView;
import com.akicater.lib.client.QuadSet;
import com.akicater.lib.client.Sprites;
import com.akicater.microblocks.MicroItemData;
import com.akicater.microblocks.MicroMesh;
import com.akicater.microblocks.Part;
import com.akicater.microblocks.PrefabData;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** What the item of a microblock or a prefab shows in the hand, in a slot and on the ground: the parts, held like a block (the prefab in blue). */
public final class MicroItemView {
    private MicroItemView() {}

    private static final Map<String, QuadSet> SETS = new ConcurrentHashMap<>();
    private static int generation = -1;

    /** The parts to draw (empty for nothing) and a key that changes when they do. */
    private record Parts(List<Part> parts, String key) {}

    private static Parts parts(ItemStack stack, boolean prefab) {
        if (prefab) {
            List<Part> parts = PrefabData.read(stack);          // the whole block space as it will stand, every part of its own material
            StringBuilder key = new StringBuilder("prefab");
            for (Part part : parts) key.append(part.minX).append(',').append(part.minY).append(',').append(part.minZ).append(',').append(part.maxX).append(',').append(part.maxY).append(',').append(part.maxZ).append('o').append(part.orient).append(Compat.blockId(part.material)).append(part.mask == null ? 0 : java.util.Arrays.hashCode(part.mask)).append('t').append(part.tx).append(',').append(part.ty).append(',').append(part.tz);
            return new Parts(parts, key.toString());
        }
        int[] dims = MicroItemData.dims(stack);
        Block material = MicroItemData.material(stack);
        // the box as it will lie on a floor: the thin side up, the long side along x (the turn that was chosen in the air shows as it is)
        net.minecraft.client.player.LocalPlayer me = net.minecraft.client.Minecraft.getInstance().player;
        int mode = me == null ? 0 : com.akicater.microblocks.MicroSettings.of(me).rotMode;
        int[] size = MicroItemData.worldDims(dims, MicroItemData.rotation(stack), Direction.UP, Direction.EAST, null, mode);
        int x0 = (16 - size[0]) / 2, y0 = (16 - size[1]) / 2, z0 = (16 - size[2]) / 2;      // in the middle of the block frame
        int rotation = MicroItemData.rotation(stack);
        int orient = rotation > 0 ? rotation : MicroItemData.orientation(dims, size);
        int[] off = MicroItemData.offset(stack);
        int[] w = off == null ? null : MicroItemData.worldOffset(off, orient);
        Part part = new Part(x0, y0, z0, x0 + size[0], y0 + size[1], z0 + size[2], material, orient);
        part = part.withInner(MicroItemData.inner(stack));
        if (w != null) part = part.withShift(w[0] - x0, w[1] - y0, w[2] - z0);      // the piece shows the part of the texture that it was cut from
        return new Parts(List.of(part), size[0] + "x" + size[1] + "x" + size[2] + "o" + orient + (w == null ? "" : "t" + w[0] + "," + w[1] + "," + w[2]) + "i" + part.inner + Compat.blockId(material));
    }

    /** The view of a stack of a microblock (or of a prefab), null when there is nothing to show. */
    public static synchronized ItemView of(ItemStack stack, boolean prefab) {
        Parts parts = parts(stack, prefab);
        if (parts.parts().isEmpty()) return null;
        int now = Sprites.generation();
        if (now != generation) {
            SETS.clear();               // the sprites were reloaded: the faces are made again
            generation = now;
        }
        QuadSet set = SETS.computeIfAbsent(parts.key(), key -> prefab ? prefabSet(parts.parts()) : MicroGeometry.set(MicroMesh.build(parts.parts())));
        return new ItemView(set, parts.key(), prefab ? MicroPreviewCore.PREFAB_TINT : 0, parts.parts().get(0).material);
    }

    /**
     * The faces of a prefab. One that fits a block space is drawn as it is. A bigger one (parts joined with glue over several blocks) is meshed block space by block
     * space (every part lies in one) and made small enough to fit the frame of an item.
     */
    private static QuadSet prefabSet(List<Part> parts) {
        int[] b = com.akicater.microblocks.MicroStatue.bounds(parts);
        boolean min = false;
        for (Part p : parts) min |= p.minX < 0 || p.minY < 0 || p.minZ < 0;
        if (!min && b[3] <= 16 && b[4] <= 16 && b[5] <= 16) return MicroGeometry.set(MicroMesh.build(parts));
        int[] lo = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE}, hi = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        Map<Long, List<Part>> spaces = new java.util.LinkedHashMap<>();
        for (Part p : parts) {
            for (int i = 0; i < 3; i++) { lo[i] = Math.min(lo[i], p.min(i)); hi[i] = Math.max(hi[i], p.max(i)); }
            int cx = Math.floorDiv(p.minX, 16), cy = Math.floorDiv(p.minY, 16), cz = Math.floorDiv(p.minZ, 16);
            if (Math.floorDiv(p.maxX - 1, 16) != cx || Math.floorDiv(p.maxY - 1, 16) != cy || Math.floorDiv(p.maxZ - 1, 16) != cz) continue;      // lies across spaces: cannot be meshed
            // the same part counted from the corner of its space; what its texture shows stays (the place and the shift together)
            Part local = new Part(p.minX - cx * 16, p.minY - cy * 16, p.minZ - cz * 16, p.maxX - cx * 16, p.maxY - cy * 16, p.maxZ - cz * 16, p.material, p.orient, p.tx + cx * 16, p.ty + cy * 16, p.tz + cz * 16, null, p.inner, p.mask);
            spaces.computeIfAbsent(net.minecraft.core.BlockPos.asLong(cx, cy, cz), k -> new java.util.ArrayList<>()).add(local);
        }
        float size = Math.max(16, Math.max(hi[0] - lo[0], Math.max(hi[1] - lo[1], hi[2] - lo[2]))) / 16f;
        float[] from = {(lo[0] + hi[0]) / 32f, (lo[1] + hi[1]) / 32f, (lo[2] + hi[2]) / 32f};      // the middle of the prefab, in blocks
        List<com.akicater.lib.client.QuadData> out = new java.util.ArrayList<>();
        for (Map.Entry<Long, List<Part>> e : spaces.entrySet()) {
            int cx = net.minecraft.core.BlockPos.getX(e.getKey()), cy = net.minecraft.core.BlockPos.getY(e.getKey()), cz = net.minecraft.core.BlockPos.getZ(e.getKey());
            for (com.akicater.lib.client.QuadData q : MicroGeometry.quads(MicroMesh.build(e.getValue()))) {
                float[] pos = new float[12];
                for (int i = 0; i < 4; i++) {
                    pos[i * 3] = (q.pos()[i * 3] + cx - from[0]) / size + 0.5f;
                    pos[i * 3 + 1] = (q.pos()[i * 3 + 1] + cy - from[1]) / size + 0.5f;
                    pos[i * 3 + 2] = (q.pos()[i * 3 + 2] + cz - from[2]) / size + 0.5f;
                }
                out.add(new com.akicater.lib.client.QuadData(q.face(), pos, q.uv(), q.sprite(), false, q.block(), q.shade(), q.tint()));
            }
        }
        return new QuadSet(out);
    }
}
