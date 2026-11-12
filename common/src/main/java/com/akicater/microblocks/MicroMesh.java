package com.akicater.microblocks;

import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.List;

/**
 * The visible faces of the parts of one block space, greedy-meshed. Every part is a box on a grid of 16 x 16 x 16 cells, so the whole block space is
 * a small voxel volume: a face between two cells of the same material, or between a cell and an opaque neighbour, is not made, and the faces that
 * remain are merged into the biggest rectangles (rows first, then rows of equal width) of one material. Coordinates are in sixteenths of a block.
 */
public final class MicroMesh {
    public static final MicroMesh EMPTY = new MicroMesh(List.of());

    /** A rectangle on a face of the block space: {@code min} and {@code max} are the corners in sixteenths; one axis is flat. */
    public record Quad(Direction face, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Block material, int orient, int tx, int ty, int tz, boolean onBoundary, boolean inner) {
        public int min(int axis) { return axis == 0 ? minX : axis == 1 ? minY : minZ; }
    }

    private record Cell(Block material, int orient, int tx, int ty, int tz, boolean hidden) {}

    public final List<Quad> quads;

    private MicroMesh(List<Quad> quads) {
        this.quads = quads;
    }

    private static boolean opaque(Block block) {
        BlockState state = block.defaultBlockState();
        return com.akicater.lib.Compat.solidRender(state);
    }

    public static MicroMesh build(List<Part> parts) {
        return build(parts, null);
    }

    /**
     * With the position of the block space: a box that lies across block spaces ({@link Part#group}) is drawn as one piece of faces, by the space that holds its
     * lowest corner (the faces reach out of that space); the other spaces only keep its volume, so that the faces next to it are cut, and draw nothing of it.
     */
    public static MicroMesh build(List<Part> parts, net.minecraft.core.BlockPos pos) {
        if (parts.isEmpty()) return EMPTY;
        if (parts.size() == 1 && parts.get(0).mask == null && (pos == null || !parts.get(0).inBoxGroup())) return single(parts.get(0));      // the most common block: no grid at all
        // every kind of cell (material, turn, shift, hidden) gets a small number: the faces are compared as numbers, not as objects
        final List<Cell> kinds = new ArrayList<>(4);
        final boolean[] opaque = new boolean[parts.size() + 1];
        final int[] cells = new int[16 * 16 * 16];
        final int[] inners = new int[16 * 16 * 16];
        final int[] owner = new int[16 * 16 * 16];      // 1 + the number of the part that holds the cell, to know a hole in a part from the open air round it      // the inside faces of the part that owns the cell (see Part#inner)
        for (int partIndex = 0; partIndex < parts.size(); partIndex++) {
            Part part = parts.get(partIndex);
            Cell cell = new Cell(part.material, part.orient == 1 ? 0 : part.orient, part.tx, part.ty, part.tz, pos != null && part.inBoxGroup());
            int id = kinds.indexOf(cell) + 1;
            if (id == 0) {
                kinds.add(cell);
                id = kinds.size();
                opaque[id] = opaque(cell.material());
            }
            for (int z = part.minZ; z < part.maxZ; z++) for (int y = part.minY; y < part.maxY; y++) {
                int row = 16 * (y + 16 * z);
                for (int x = part.minX; x < part.maxX; x++) if (part.mask == null || part.has(x, y, z)) { cells[row + x] = id; inners[row + x] = part.inner; owner[row + x] = partIndex + 1; }      // the part that comes later wins
            }
        }
        final boolean[] hidden = new boolean[kinds.size() + 1];
        for (int i = 0; i < kinds.size(); i++) hidden[i + 1] = kinds.get(i).hidden();

        List<Quad> out = new ArrayList<>();
        final int[] mask = new int[256];
        final int[] c = new int[3];
        for (Direction face : Direction.values()) {
            final int axis = face.getAxis().ordinal();
            final int step = face.getAxisDirection().getStep();
            final int a = (axis + 1) % 3, b = (axis + 2) % 3;     // the two axes of the face: a is the row, b the column
            // for every kind of cell: which bit of the inside mask of its part is this face (the part may lie turned)
            final int[] innerBit = new int[kinds.size() + 1];
            for (int i = 0; i < kinds.size(); i++) {
                int[] perm = Part.perm(kinds.get(i).orient());
                int local = 0;
                for (int j = 0; j < 3; j++) if (perm[j] == axis) local = j;
                innerBit[i + 1] = Direction.fromAxisAndDirection(Direction.Axis.values()[local], face.getAxisDirection()).ordinal();
            }
            for (int k = 0; k < 16; k++) {
                // the faces that are visible on this slice
                boolean any = false;
                for (int ia = 0; ia < 16; ia++) {
                    for (int ib = 0; ib < 16; ib++) {
                        c[axis] = k; c[a] = ia; c[b] = ib;
                        int m = cells[index(c[0], c[1], c[2])];
                        int visible = 0;
                        if (m != 0 && !hidden[m]) {
                            int cellIndex = index(c[0], c[1], c[2]), cellY = c[1];
                            int flag = inners[cellIndex] >> innerBit[m] & 1;
                            int k2 = k + step;
                            if (k2 < 0 || k2 > 15) {
                                visible = m << 1 | flag;
                            } else {
                                c[axis] = k2;
                                int n = cells[index(c[0], c[1], c[2])];
                                if (n == 0) {
                                    // a hole inside the box of the part (a chisel or a drill made it): its walls are the inside of the block, the underside of its texture (the dirt of a grass
                                    // block); only the top layer of cells keeps the side of the block (the edge of the grass)
                                    Part own = parts.get(owner[cellIndex] - 1);
                                    boolean inBox = c[0] >= own.minX && c[0] < own.maxX && c[1] >= own.minY && c[1] < own.maxY && c[2] >= own.minZ && c[2] < own.maxZ;
                                    boolean topEdge = axis != 1 && cellY == 15 && own.orient <= 1;
                                    if (inBox && !topEdge) flag = 1;
                                }
                                visible = n != 0 && (n == m || opaque[n]) ? 0 : m << 1 | flag;
                            }
                        }
                        mask[ia * 16 + ib] = visible;
                        any |= visible != 0;
                    }
                }
                if (!any) continue;
                // merge the faces into as few rectangles as the partition finds
                for (MicroPartition.Rect r : MicroPartition.best(mask)) {
                    int[] min = new int[3], max = new int[3];
                    int plane = step > 0 ? k + 1 : k;
                    min[axis] = plane; max[axis] = plane;
                    min[a] = r.a(); max[a] = r.a() + r.h();
                    min[b] = r.b(); max[b] = r.b() + r.w();
                    boolean boundary = plane == 0 || plane == 16;
                    Cell kind = kinds.get((r.v() >> 1) - 1);
                    out.add(new Quad(face, min[0], min[1], min[2], max[0], max[1], max[2], kind.material(), kind.orient(), kind.tx(), kind.ty(), kind.tz(), boundary, (r.v() & 1) != 0));
                }
            }
        }
        if (pos != null) {
            java.util.Set<Integer> done = new java.util.HashSet<>();
            for (Part part : parts) {
                Part.Group g = part.group;
                if (!part.inBoxGroup() || !done.add(g.id())) continue;
                if (Math.floorDiv(g.minX(), 16) != pos.getX() || Math.floorDiv(g.minY(), 16) != pos.getY() || Math.floorDiv(g.minZ(), 16) != pos.getZ()) continue;
                int ox = pos.getX() * 16, oy = pos.getY() * 16, oz = pos.getZ() * 16;
                int x0 = g.minX() - ox, y0 = g.minY() - oy, z0 = g.minZ() - oz, x1 = g.maxX() - ox, y1 = g.maxY() - oy, z1 = g.maxZ() - oz;
                int o = part.orient == 1 ? 0 : part.orient;
                for (Direction face : Direction.values()) {
                    int px = face.getStepX() > 0 ? x1 : x0, py = face.getStepY() > 0 ? y1 : y0, pz = face.getStepZ() > 0 ? z1 : z0;
                    out.add(new Quad(face, face.getAxis() == Direction.Axis.X ? px : x0, face.getAxis() == Direction.Axis.Y ? py : y0, face.getAxis() == Direction.Axis.Z ? pz : z0,
                            face.getAxis() == Direction.Axis.X ? px : x1, face.getAxis() == Direction.Axis.Y ? py : y1, face.getAxis() == Direction.Axis.Z ? pz : z1,
                            part.material, o, part.tx, part.ty, part.tz, false, false));
                }
            }
        }
        return new MicroMesh(List.copyOf(out));
    }

    /** One part: its six faces, as they are. */
    private static MicroMesh single(Part p) {
        int o = p.orient == 1 ? 0 : p.orient;
        int[] perm = Part.perm(o);
        List<Quad> out = new ArrayList<>(6);
        for (Direction face : Direction.values()) {
            int axis = face.getAxis().ordinal();
            int local = 0;
            for (int j = 0; j < 3; j++) if (perm[j] == axis) local = j;
            boolean inner = (p.inner >> Direction.fromAxisAndDirection(Direction.Axis.values()[local], face.getAxisDirection()).ordinal() & 1) != 0;
            int plane = face.getAxisDirection().getStep() > 0 ? p.max(axis) : p.min(axis);
            int[] min = {p.minX, p.minY, p.minZ}, max = {p.maxX, p.maxY, p.maxZ};
            min[axis] = plane;
            max[axis] = plane;
            out.add(new Quad(face, min[0], min[1], min[2], max[0], max[1], max[2], p.material, o, p.tx, p.ty, p.tz, plane == 0 || plane == 16, inner));
        }
        return new MicroMesh(List.copyOf(out));
    }

    private static int index(int x, int y, int z) {
        return x + 16 * (y + 16 * z);
    }
}
