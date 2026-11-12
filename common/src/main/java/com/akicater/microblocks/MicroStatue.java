package com.akicater.microblocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * A prefab put down as one piece, like a glued statue: it is placed by the rules of any other microblock (free, snapping or inside a block space, anywhere to the
 * sixteenth, also across block spaces), and breaking any part of it takes all of it and gives the prefab back. Its parts that fall across the border of a block space
 * are cut there, and every piece knows which part of the prefab it is ({@link Part#sub}) and shares the group, so that the prefab can be made whole again.
 */
public final class MicroStatue {
    private MicroStatue() {}

    /** The pieces to put, each in the block space that it lies in, and the box that holds the whole statue. */
    public record Plan(List<MicroPlacement.Result> pieces, AABB whole) {}

    /** The box round the parts of a prefab: min x, y, z, max x, y, z (in sixteenths of its block space). */
    public static int[] bounds(List<Part> parts) {
        int[] b = {16, 16, 16, 0, 0, 0};
        for (Part p : parts) {
            b[0] = Math.min(b[0], p.minX); b[1] = Math.min(b[1], p.minY); b[2] = Math.min(b[2], p.minZ);
            b[3] = Math.max(b[3], p.maxX); b[4] = Math.max(b[4], p.maxY); b[5] = Math.max(b[5], p.maxZ);
        }
        return b;
    }

    /** Where the prefab would go for this click, in the placing mode of the player; null when nowhere. */
    public static Plan plan(Level level, BlockPos clicked, Direction face, Vec3 hit, List<Part> parts, MicroSettings.State state) {
        if (parts.isEmpty()) return null;
        int[] b = bounds(parts);
        int[] dims = {b[3] - b[0], b[4] - b[1], b[5] - b[2]};
        for (int d : dims) if (d < 1 || d > 16 * 8) return null;      // what an item says is not trusted: a prefab is a few blocks at most
        Block material = parts.get(0).material;
        // the position of the box that holds the statue, by the rules of the microblocks; the check for the parts that are there is made below, piece by piece
        MicroPlacement.Result at = switch (state.effective()) {
            case FREE -> MicroPlacement.computeFree(level, clicked, face, hit, material, dims, true);
            case SNAP -> MicroPlacement.compute(level, clicked, face, hit, material, dims, false, true);
            default -> MicroPlacement.compute(level, clicked, face, hit, material, dims, true, true);
        };
        if (at == null) return null;
        int[] lo = at.whole() != null
                ? new int[]{(int) Math.round(at.whole().minX * 16), (int) Math.round(at.whole().minY * 16), (int) Math.round(at.whole().minZ * 16)}
                : new int[]{at.pos().getX() * 16 + at.part().minX, at.pos().getY() * 16 + at.part().minY, at.pos().getZ() * 16 + at.part().minZ};
        int[] origin = {lo[0] - b[0], lo[1] - b[1], lo[2] - b[2]};      // where the block space of the prefab begins in the world
        Part.Group group = new Part.Group(level.getRandom().nextInt() | 1, lo[0], lo[1], lo[2], lo[0] + dims[0], lo[1] + dims[1], lo[2] + dims[2], false, true, origin[0], origin[1], origin[2]);
        boolean replace = state.replace;
        List<MicroPlacement.Result> pieces = new ArrayList<>();
        Map<BlockPos, Integer> added = new HashMap<>();
        for (int i = 0; i < parts.size(); i++) {
            Part p = parts.get(i);
            int[] g0 = {origin[0] + p.minX, origin[1] + p.minY, origin[2] + p.minZ}, g1 = {origin[0] + p.maxX, origin[1] + p.maxY, origin[2] + p.maxZ};
            for (int bx = Math.floorDiv(g0[0], 16); bx <= Math.floorDiv(g1[0] - 1, 16); bx++)
                for (int by = Math.floorDiv(g0[1], 16); by <= Math.floorDiv(g1[1] - 1, 16); by++)
                    for (int bz = Math.floorDiv(g0[2], 16); bz <= Math.floorDiv(g1[2] - 1, 16); bz++) {
                        int[] cell = {bx, by, bz};
                        int[] min = new int[3], max = new int[3];
                        for (int a = 0; a < 3; a++) { min[a] = Math.max(g0[a], cell[a] * 16) - cell[a] * 16; max[a] = Math.min(g1[a], cell[a] * 16 + 16) - cell[a] * 16; }
                        BlockPos pos = new BlockPos(bx, by, bz);
                        if (!level.isInWorldBounds(pos)) return null;
                        // the texture of the part stays where it was in the block space of the prefab
                        long[] mask = null;
                        if (p.mask != null) {
                            boolean[] cells = new boolean[4096];
                            for (int x = min[0]; x < max[0]; x++) for (int y = min[1]; y < max[1]; y++) for (int z = min[2]; z < max[2]; z++)
                                cells[x + 16 * (y + 16 * z)] = p.has(x + bx * 16 - origin[0], y + by * 16 - origin[1], z + bz * 16 - origin[2]);
                            mask = Part.maskOf(cells);
                        }
                        Part piece = new Part(min[0], min[1], min[2], max[0], max[1], max[2], p.material, p.orient, p.tx + bx * 16 - origin[0], p.ty + by * 16 - origin[1], p.tz + bz * 16 - origin[2], group, p.inner, mask, i + 1);
                        BlockState state1 = level.getBlockState(pos);
                        int count = added.merge(pos, 1, Integer::sum);
                        if (state1.getBlock() instanceof MicroBlock) {
                            if (!(level.getBlockEntity(pos) instanceof MicroBlockEntity be) || !be.canAdd(piece, replace) || be.parts().size() + count > MicroBlockEntity.MAX_PARTS) return null;
                        } else if (!com.akicater.lib.Compat.replaceable(state1) && !(replace && MicroMaterials.isValid(state1.getBlock()) && !state1.hasBlockEntity())) {
                            return null;
                        }
                        pieces.add(new MicroPlacement.Result(pos, piece));
                    }
        }
        return new Plan(pieces, new AABB(lo[0] / 16.0, lo[1] / 16.0, lo[2] / 16.0, (lo[0] + dims[0]) / 16.0, (lo[1] + dims[1]) / 16.0, (lo[2] + dims[2]) / 16.0));
    }

    /** Puts the pieces in (server side), all or none. */
    public static boolean place(Level level, Plan plan, boolean replace) {
        if (plan == null || plan.pieces().isEmpty()) return false;
        for (MicroPlacement.Result piece : plan.pieces()) {
            if (replace) MicroPlacement.convert(level, piece.pos());
            if (!(level.getBlockState(piece.pos()).getBlock() instanceof MicroBlock)) level.setBlock(piece.pos(), Microblocks.BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
            if (!(level.getBlockEntity(piece.pos()) instanceof MicroBlockEntity be) || !be.add(piece.part(), replace)) return false;
        }
        return true;
    }

    /** The parts of the prefab that a statue was made from, put together again from its pieces (in the block space of the prefab, as they were). */
    public static List<Part> rebuild(Level level, Part.Group g) {
        // the pieces of every part of the prefab, each with the corner of its block space in the world (in sixteenths)
        Map<Integer, List<Object[]>> bySub = new java.util.TreeMap<>();
        for (int bx = Math.floorDiv(g.minX(), 16); bx <= Math.floorDiv(g.maxX() - 1, 16); bx++)
            for (int by = Math.floorDiv(g.minY(), 16); by <= Math.floorDiv(g.maxY() - 1, 16); by++)
                for (int bz = Math.floorDiv(g.minZ(), 16); bz <= Math.floorDiv(g.maxZ() - 1, 16); bz++) {
                    if (!(level.getBlockEntity(new BlockPos(bx, by, bz)) instanceof MicroBlockEntity be)) continue;
                    for (Part p : be.parts()) {
                        if (p.group == null || p.group.id() != g.id()) continue;
                        // where the piece begins in the block space of the prefab, less where it begins in its own: by the origin that the statue knows, or (an older
                        // statue) by the shift of its texture
                        int[] d = g.hasOrigin() ? new int[]{bx * 16 - g.ox(), by * 16 - g.oy(), bz * 16 - g.oz()} : new int[]{p.tx, p.ty, p.tz};
                        bySub.computeIfAbsent(p.sub, k -> new ArrayList<>()).add(new Object[]{p, d});
                    }
                }
        List<Part> out = new ArrayList<>();
        for (List<Object[]> pieces : bySub.values()) {
            int[] b = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
            boolean masked = false;
            for (Object[] piece : pieces) {
                Part p = (Part) piece[0];
                int[] d = (int[]) piece[1];
                b[0] = Math.min(b[0], p.minX + d[0]); b[1] = Math.min(b[1], p.minY + d[1]); b[2] = Math.min(b[2], p.minZ + d[2]);
                b[3] = Math.max(b[3], p.maxX + d[0]); b[4] = Math.max(b[4], p.maxY + d[1]); b[5] = Math.max(b[5], p.maxZ + d[2]);
                masked |= p.mask != null;
            }
            Part first = (Part) pieces.get(0)[0];
            int[] fd = (int[]) pieces.get(0)[1];
            // the texture stays where it is on the pieces: what a piece shows is counted from its place and its shift
            int sx = first.tx - fd[0], sy = first.ty - fd[1], sz = first.tz - fd[2];
            if (!masked) {
                out.add(Part.prefab(b[0], b[1], b[2], b[3], b[4], b[5], first.material, first.orient, sx, sy, sz, first.inner, null));
                continue;
            }
            // a part with holes: its cells are kept. They are counted in one block space of the prefab, so the part must lie in one (a part of a prefab does)
            boolean oneSpace = Math.floorDiv(b[0], 16) == Math.floorDiv(b[3] - 1, 16) && Math.floorDiv(b[1], 16) == Math.floorDiv(b[4] - 1, 16) && Math.floorDiv(b[2], 16) == Math.floorDiv(b[5] - 1, 16);
            if (!oneSpace) {
                out.add(Part.prefab(b[0], b[1], b[2], b[3], b[4], b[5], first.material, first.orient, sx, sy, sz, first.inner, null));      // cannot be kept: made whole
                continue;
            }
            boolean[] cells = new boolean[4096];
            int count = 0;
            for (Object[] piece : pieces) {
                Part p = (Part) piece[0];
                int[] d = (int[]) piece[1];
                for (int x = p.minX; x < p.maxX; x++) for (int y = p.minY; y < p.maxY; y++) for (int z = p.minZ; z < p.maxZ; z++) {
                    if (!p.has(x, y, z)) continue;
                    int i = ((x + d[0]) & 15) + 16 * (((y + d[1]) & 15) + 16 * ((z + d[2]) & 15));
                    if (!cells[i]) { cells[i] = true; count++; }
                }
            }
            boolean whole = count == (b[3] - b[0]) * (b[4] - b[1]) * (b[5] - b[2]);
            out.add(Part.prefab(b[0], b[1], b[2], b[3], b[4], b[5], first.material, first.orient, sx, sy, sz, first.inner, whole ? null : Part.maskOf(cells)));
        }
        return out;
    }

    /** The prefab item for a statue. */
    public static ItemStack item(Level level, Part.Group g) {
        ItemStack stack = new ItemStack(Microblocks.PREFAB.get());
        PrefabData.write(stack, rebuild(level, g));
        return stack;
    }
}
