package com.akicater.microblocks;

import com.akicater.lib.Compat;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.Vec3;

/**
 * Works out where a microblock goes when an item is used on a block, and puts it there. The same calculation gives the preview that is drawn
 * while the item is held, so what is outlined is what is placed.
 *
 * The box lies flush against the surface that was clicked (the face of a block, or a face of a part inside a block space). Along the surface it snaps like a
 * directional block: to one of the two edges or to the middle of the block space, whichever is nearest the click; when sneaking (precise) it follows the
 * click to the sixteenth.
 */
public final class MicroPlacement {
    private MicroPlacement() {}

    /** The block space the part goes into and the part itself. */
    public record Result(BlockPos pos, Part part, java.util.List<Result> more, net.minecraft.world.phys.AABB whole) {
        public Result(BlockPos pos, Part part) {
            this(pos, part, java.util.List.of(), null);
        }

        public Result(BlockPos pos, Part part, java.util.List<Result> more) {
            this(pos, part, more, null);
        }

        public Result withInner(int inner) {
            java.util.List<Result> all = new java.util.ArrayList<>();
            for (Result r : more) all.add(r.withInner(inner));
            return new Result(pos, part.withInner(inner), all, whole);
        }

        /**
         * The texture that the piece was cut with: {@code off} is where the texture starts on the axes of the world. In the usual way of placing the part starts where it is
         * put (so its texture is the one that lies there), in the free way the pieces of a box lie across block spaces and each already knows where the box begins.
         */
        public Result withOffset(int[] off, boolean free) {
            java.util.List<Result> moved = new java.util.ArrayList<>();
            for (Result r : more) moved.add(r.withOffset(off, free));
            Part p = part;
            return new Result(pos, free ? p.withShift(p.tx + off[0], p.ty + off[1], p.tz + off[2]) : p.withShift(off[0] - p.minX, off[1] - p.minY, off[2] - p.minZ), moved, whole);
        }

        /** The same with the texture of the material turned (for the part and for the pieces that lie in other block spaces). */
        public Result withOrient(int orient) {
            java.util.List<Result> turned = new java.util.ArrayList<>();
            for (Result r : more) turned.add(r.withOrient(orient));
            return new Result(pos, part.withOrient(orient), turned, whole);
        }
    }

    public static Result compute(Level level, BlockPos clicked, Direction face, Vec3 hit, Block material, int[] worldDims, boolean precise) {
        return compute(level, clicked, face, hit, material, worldDims, precise, false);
    }

    public static Result compute(Level level, BlockPos clicked, Direction face, Vec3 hit, Block material, int[] worldDims, boolean precise, boolean replace) {
        final int axis = face.getAxis().ordinal();
        final int step = face.getAxisDirection().getStep();
        // where the click is, in sixteenths of the clicked block space
        double[] local = {(hit.x - clicked.getX()) * 16, (hit.y - clicked.getY()) * 16, (hit.z - clicked.getZ()) * 16};
        int plane = (int) Math.round(local[axis]);
        boolean onBorder = step > 0 ? plane >= 16 : plane <= 0;

        BlockPos target;
        if (level.getBlockState(clicked).getBlock() instanceof MicroBlock && !onBorder) {
            target = clicked;                                      // a face of a part inside the space: stack on it
        } else {
            target = clicked.relative(face);                       // the surface of the block: the next space
            plane = step > 0 ? 0 : 16;
        }
        if (!level.isInWorldBounds(target)) return null;

        int[] min = new int[3], max = new int[3];
        if (step > 0) { min[axis] = plane; max[axis] = plane + worldDims[axis]; }
        else { max[axis] = plane; min[axis] = plane - worldDims[axis]; }
        if (min[axis] < 0 || max[axis] > 16) return null;          // does not fit into the space on that side

        for (int i = 0; i < 3; i++) {
            if (i == axis) continue;
            int d = worldDims[i];
            int lo;
            if (precise) {
                lo = (int) Math.round(local[i] - d / 2.0);
            } else {
                int[] options = {0, (16 - d) / 2, 16 - d};          // an edge, the middle, the other edge
                lo = options[0];
                double best = Double.MAX_VALUE;
                for (int option : options) {
                    double distance = Math.abs(option + d / 2.0 - local[i]);
                    if (distance < best - 1.0E-9) { best = distance; lo = option; }
                }
            }
            lo = Math.max(0, Math.min(16 - d, lo));
            min[i] = lo;
            max[i] = lo + d;
        }
        Part part = Part.of(min, max, material);

        BlockState state = level.getBlockState(target);
        if (state.getBlock() instanceof MicroBlock) {
            if (!(level.getBlockEntity(target) instanceof MicroBlockEntity be) || !be.canAdd(part, replace)) return null;
        } else if (!Compat.replaceable(state) && !(replace && MicroMaterials.isValid(state.getBlock()) && !state.hasBlockEntity())) {
            return null;
        }
        return new Result(target, part);
    }

    /**
     * Ctrl: no grid but the sixteenths. The box lies on the surface that was clicked (the face of a block or of a part) and follows the click to the sixteenth,
     * wherever that is, also across the borders of block spaces: it is cut into the pieces that lie in each space.
     */
    public static Result computeFree(Level level, BlockPos clicked, Direction face, Vec3 hit, Block material, int[] worldDims) {
        return computeFree(level, clicked, face, hit, material, worldDims, false);
    }

    public static Result computeFree(Level level, BlockPos clicked, Direction face, Vec3 hit, Block material, int[] worldDims, boolean replace) {
        final int axis = face.getAxis().ordinal();
        final int step = face.getAxisDirection().getStep();
        double[] at = {hit.x * 16, hit.y * 16, hit.z * 16};
        int[] lo = new int[3], hi = new int[3];
        for (int i = 0; i < 3; i++) {
            int d = worldDims[i];
            if (i == axis) {
                int plane = (int) Math.round(at[i]);
                if (step > 0) { lo[i] = plane; hi[i] = plane + d; } else { hi[i] = plane; lo[i] = plane - d; }
            } else {
                lo[i] = (int) Math.round(at[i] - d / 2.0);
                hi[i] = lo[i] + d;
            }
        }
        java.util.List<Result> pieces = new java.util.ArrayList<>();
        for (int bx = Math.floorDiv(lo[0], 16); bx <= Math.floorDiv(hi[0] - 1, 16); bx++) {
            for (int by = Math.floorDiv(lo[1], 16); by <= Math.floorDiv(hi[1] - 1, 16); by++) {
                for (int bz = Math.floorDiv(lo[2], 16); bz <= Math.floorDiv(hi[2] - 1, 16); bz++) {
                    int[] b = {bx, by, bz};
                    int[] min = new int[3], max = new int[3];
                    for (int i = 0; i < 3; i++) { min[i] = Math.max(lo[i], b[i] * 16) - b[i] * 16; max[i] = Math.min(hi[i], b[i] * 16 + 16) - b[i] * 16; }
                    BlockPos target = new BlockPos(bx, by, bz);
                    if (!level.isInWorldBounds(target)) return null;
                    Part part = Part.of(min, max, material).withShift(bx * 16 - lo[0], by * 16 - lo[1], bz * 16 - lo[2]);
                    BlockState state = level.getBlockState(target);
                    if (state.getBlock() instanceof MicroBlock) {
                        if (!(level.getBlockEntity(target) instanceof MicroBlockEntity be) || !be.canAdd(part, replace)) return null;
                    } else if (!Compat.replaceable(state) && !(replace && MicroMaterials.isValid(state.getBlock()) && !state.hasBlockEntity())) {
                        return null;
                    }
                    pieces.add(new Result(target, part));
                }
            }
        }
        if (pieces.isEmpty()) return null;
        if (pieces.size() > 1) {
            // the pieces know each other: breaking one takes the whole box
            Part.Group group = new Part.Group(level.getRandom().nextInt() | 1, lo[0], lo[1], lo[2], hi[0], hi[1], hi[2]);
            for (int i = 0; i < pieces.size(); i++) pieces.set(i, new Result(pieces.get(i).pos(), pieces.get(i).part().withGroup(group)));
        }
        Result main = pieces.get(0);
        return new Result(main.pos(), main.part(), new java.util.ArrayList<>(pieces.subList(1, pieces.size())),
                new net.minecraft.world.phys.AABB(lo[0] / 16.0, lo[1] / 16.0, lo[2] / 16.0, hi[0] / 16.0, hi[1] / 16.0, hi[2] / 16.0));
    }

    /**
     * The mode that fills: the part goes only into the cells that are empty, whatever is there stays. What is left of the part is cut into boxes (as few as the
     * greedy way finds), each of them a part of its own with everything that the part had.
     */
    public static java.util.List<Part> fillClip(java.util.List<Part> existing, Part part) {
        if (existing.isEmpty()) return java.util.List.of(part);
        boolean[] free = new boolean[16 * 16 * 16];
        int x0 = 16, y0 = 16, z0 = 16, x1 = 0, y1 = 0, z1 = 0, count = 0;
        for (int x = part.minX; x < part.maxX; x++) for (int y = part.minY; y < part.maxY; y++) for (int z = part.minZ; z < part.maxZ; z++) {
            if (!part.has(x, y, z)) continue;
            boolean taken = false;
            for (Part other : existing) if (other.has(x, y, z)) { taken = true; break; }
            if (taken) continue;
            free[x + 16 * (y + 16 * z)] = true;
            count++;
            x0 = Math.min(x0, x); y0 = Math.min(y0, y); z0 = Math.min(z0, z); x1 = Math.max(x1, x + 1); y1 = Math.max(y1, y + 1); z1 = Math.max(z1, z + 1);
        }
        if (count == 0) return java.util.List.of();
        // one part: the box round the free cells, with the cells that it holds (no mask when there are no holes)
        boolean whole = count == (x1 - x0) * (y1 - y0) * (z1 - z0);
        return java.util.List.of(new Part(x0, y0, z0, x1, y1, z1, part.material, part.orient, part.tx, part.ty, part.tz, part.group, part.inner, whole ? null : Part.maskOf(free)));
    }

    /** A part with holes cut into whole boxes (as few as the greedy way finds), each with everything that the part had: what is given back when it is broken. */
    public static java.util.List<Part> boxesOf(Part part) {
        if (part.mask == null) return java.util.List.of(part);
        boolean[] free = new boolean[16 * 16 * 16];
        for (int x = part.minX; x < part.maxX; x++) for (int y = part.minY; y < part.maxY; y++) for (int z = part.minZ; z < part.maxZ; z++) free[x + 16 * (y + 16 * z)] = part.has(x, y, z);
        java.util.List<Part> out = new java.util.ArrayList<>();
        for (int z = part.minZ; z < part.maxZ; z++) for (int y = part.minY; y < part.maxY; y++) for (int x = part.minX; x < part.maxX; x++) {
            if (!free[x + 16 * (y + 16 * z)]) continue;
            int ex = x + 1;
            while (ex < part.maxX && free[ex + 16 * (y + 16 * z)]) ex++;
            int ey = y + 1;
            grow:
            while (ey < part.maxY) {
                for (int i = x; i < ex; i++) if (!free[i + 16 * (ey + 16 * z)]) break grow;
                ey++;
            }
            int ez = z + 1;
            grow2:
            while (ez < part.maxZ) {
                for (int j = y; j < ey; j++) for (int i = x; i < ex; i++) if (!free[i + 16 * (j + 16 * ez)]) break grow2;
                ez++;
            }
            for (int k = z; k < ez; k++) for (int j = y; j < ey; j++) for (int i = x; i < ex; i++) free[i + 16 * (j + 16 * k)] = false;
            out.add(new Part(x, y, z, ex, ey, ez, part.material, part.orient, part.tx, part.ty, part.tz, null, part.inner));
        }
        return out;
    }

    /** The parts that are in a block space: those of a block of microblocks, or the whole block as one part for an ordinary block that can be cut (it becomes a block of microblocks when something is put in). */
    public static java.util.List<Part> existingParts(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof MicroBlock) return level.getBlockEntity(pos) instanceof MicroBlockEntity be ? be.parts() : java.util.List.of();
        if (!Compat.replaceable(state) && MicroMaterials.isValid(state.getBlock()) && !state.hasBlockEntity()) return java.util.List.of(new Part(0, 0, 0, 16, 16, 16, state.getBlock()));
        return java.util.List.of();
    }

    /** An ordinary block that can be cut becomes a block of microblocks holding the whole block as one part; false if there is nothing to turn. */
    public static boolean convert(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof MicroBlock || Compat.replaceable(state) || !MicroMaterials.isValid(state.getBlock()) || state.hasBlockEntity()) return false;
        Block material = state.getBlock();
        level.setBlock(pos, Microblocks.BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
        return level.getBlockEntity(pos) instanceof MicroBlockEntity be && be.add(new Part(0, 0, 0, 16, 16, 16, material), true);
    }

    /** Every piece that a result puts (the main one and the others, across block spaces), each already cut to the empty cells when {@code fill} is on. */
    public static java.util.List<Result> pieces(Level level, Result result, boolean fill) {
        java.util.List<Result> all = new java.util.ArrayList<>();
        all.add(new Result(result.pos(), result.part()));
        all.addAll(result.more());
        if (!fill) return all;
        java.util.List<Result> cut = new java.util.ArrayList<>();
        for (Result r : all) {
            for (Part p : fillClip(existingParts(level, r.pos()), r.part())) cut.add(new Result(r.pos(), p));
        }
        return cut;
    }

    /** Puts the part in (server side). */
    public static boolean place(Level level, Result result) {
        return place(level, result, false);
    }

    public static boolean place(Level level, Result result, boolean replace) {
        return place(level, result, replace, false);
    }

    public static boolean place(Level level, Result result, boolean replace, boolean fill) {
        if (result == null) return false;
        if (fill) {
            java.util.List<Result> original = new java.util.ArrayList<>();
            original.add(result);
            original.addAll(result.more());
            java.util.List<Result> pieces = pieces(level, result, true);
            // if the cells that were taken cut the box into other pieces, they belong together: breaking one takes all of them (and each is given back as what it is)
            boolean cut = pieces.size() != original.size();
            for (int i = 0; !cut && i < pieces.size(); i++) cut = pieces.get(i).part().volume() != original.get(i).part().volume();
            if (cut) {
                int x0 = Integer.MAX_VALUE, y0 = Integer.MAX_VALUE, z0 = Integer.MAX_VALUE, x1 = Integer.MIN_VALUE, y1 = Integer.MIN_VALUE, z1 = Integer.MIN_VALUE;
                for (Result r : original) {
                    x0 = Math.min(x0, r.pos().getX() * 16 + r.part().minX); y0 = Math.min(y0, r.pos().getY() * 16 + r.part().minY); z0 = Math.min(z0, r.pos().getZ() * 16 + r.part().minZ);
                    x1 = Math.max(x1, r.pos().getX() * 16 + r.part().maxX); y1 = Math.max(y1, r.pos().getY() * 16 + r.part().maxY); z1 = Math.max(z1, r.pos().getZ() * 16 + r.part().maxZ);
                }
                Part.Group group = new Part.Group(level.getRandom().nextInt() | 1, x0, y0, z0, x1, y1, z1, true);
                for (int i = 0; i < pieces.size(); i++) pieces.set(i, new Result(pieces.get(i).pos(), pieces.get(i).part().withGroup(group)));
            }
            boolean any = false;
            for (Result piece : pieces) {
                convert(level, piece.pos());
                if (!(level.getBlockState(piece.pos()).getBlock() instanceof MicroBlock)) level.setBlock(piece.pos(), Microblocks.BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
                if (level.getBlockEntity(piece.pos()) instanceof MicroBlockEntity be && be.add(piece.part(), true)) any = true;
            }
            return any;
        }
        if (!result.more().isEmpty()) {                            // the pieces of a box that lies across block spaces: all of them or none
            for (Result piece : result.more()) {
                BlockState state = level.getBlockState(piece.pos());
                if (state.getBlock() instanceof MicroBlock ? !(level.getBlockEntity(piece.pos()) instanceof MicroBlockEntity be && be.canAdd(piece.part(), replace)) : !Compat.replaceable(state) && !(replace && MicroMaterials.isValid(state.getBlock()))) return false;
            }
            boolean all = placeOne(level, new Result(result.pos(), result.part()), replace);
            for (Result piece : result.more()) all &= placeOne(level, piece, replace);
            return all;
        }
        return placeOne(level, result, replace);
    }

    private static boolean placeOne(Level level, Result result, boolean replace) {
        if (replace) convert(level, result.pos());
        BlockState state = level.getBlockState(result.pos());
        if (!(state.getBlock() instanceof MicroBlock)) {
            level.setBlock(result.pos(), Microblocks.BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
        }
        if (level.getBlockEntity(result.pos()) instanceof MicroBlockEntity be && be.add(result.part(), replace)) return true;
        if (!(state.getBlock() instanceof MicroBlock)) level.removeBlock(result.pos(), false);
        return false;
    }
}
