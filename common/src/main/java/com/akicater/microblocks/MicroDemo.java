package com.akicater.microblocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.StairBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.Shapes;

import java.util.List;
import java.util.Locale;

/**
 * Used by the autotest: puts ready-made buildings made of microblocks into a flat world (a cottage, a tower, a staircase, a pergola, a row of every
 * material, a table with a chair and a shelf, every shape in many sizes) and checks the rules on the way. Every builder returns null or what went wrong.
 */
public final class MicroDemo {
    private MicroDemo() {}

    public static final List<String> NAMES = List.of("shapes", "cottage", "tower", "stairs", "pergola", "palette", "furniture");
    private static BlockPos base;

    private static BlockPos originOf(String name) {
        return base.offset((NAMES.indexOf(name) - 2) * 18, 0, 12);
    }

    /** Places the building {@code name}; the first call fixes the base (the block the player stands on). Returns "ok" or the problem. */
    public static String run(ServerLevel level, BlockPos playerPos, String name) {
        try {
            if (base == null) base = playerPos;
            if (!NAMES.contains(name)) return "unknown building " + name;
            B b = new B(level, originOf(name));
            switch (name) {
                case "shapes" -> shapes(b);
                case "cottage" -> cottage(b);
                case "tower" -> tower(b);
                case "stairs" -> stairs(b);
                case "pergola" -> pergola(b);
                case "palette" -> palette(b);
                case "furniture" -> furniture(b);
            }
            if (b.error != null) return name + ": " + b.error;
            if (b.count < 5) return name + ": only " + b.count + " parts";
            // how much the merging of faces saves: the faces of the parts as they are (6 each) against the merged ones
            int quads = 0, naive = 0;
            for (BlockPos pos : b.touched) {
                if (level.getBlockEntity(pos) instanceof MicroBlockEntity be) {
                    quads += be.mesh().quads.size();
                    naive += be.parts().size() * 6;
                }
            }
            System.out.println("AUTOTEST micro " + name + ": " + b.count + " parts, " + quads + " faces in the mesh (" + naive + " without merging)");
            if (quads > naive) return name + ": the merged mesh has more faces than the parts";
            return "ok";
        } catch (Throwable t) {
            t.printStackTrace();
            return t.toString();
        }
    }

    /** A camera for a screenshot of a building: a command that teleports the (spectating) player there and turns it. */
    public static String viewCommand(String name) {
        BlockPos o = originOf(name);
        double[] v = switch (name) {
            case "shapes" -> new double[]{5.5, 3.5, -4.5, 5.5, 0.5, 3.0};
            case "cottage" -> new double[]{-6, 5.5, -8, 3.5, 2.5, 2.5};
            case "tower" -> new double[]{-5, 5, -7, 1.5, 3.5, 1.5};
            case "stairs" -> new double[]{-5, 4, -6, 3.5, 3, 1.5};
            case "pergola" -> new double[]{-5, 4, -6, 2.5, 1.5, 2.5};
            case "furniture" -> new double[]{3.0, 2.3, -1.4, 4.4, 0.2, 2.2};
            default -> new double[]{4.5, 4, -6, 4.5, 0.5, 1.5};   // palette
        };
        double cx = o.getX() + v[0], cy = o.getY() + v[1], cz = o.getZ() + v[2];
        double dx = o.getX() + v[3] - cx, dy = o.getY() + v[4] - cy, dz = o.getZ() + v[5] - cz;
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        return String.format(Locale.ROOT, "tp @p %.2f %.2f %.2f %.1f %.1f", cx, cy, cz, yaw, pitch);
    }

    // ---- the helper that puts parts in. Sizes in the helpers named face, post, beam and cube are in eighths of a block (as in the first buildings), box takes sixteenths.

    private static final class B {
        final ServerLevel level;
        final BlockPos origin;
        String error;
        int count;
        final java.util.Set<BlockPos> touched = new java.util.HashSet<>();

        B(ServerLevel level, BlockPos origin) {
            this.level = level;
            this.origin = origin;
        }

        /** A box in the block space (x, y, z) from (x0, y0, z0) to (x1, y1, z1), in sixteenths. */
        void box(int x, int y, int z, int x0, int y0, int z0, int x1, int y1, int z1, Block material) {
            if (error != null) return;
            BlockPos pos = origin.offset(x, y, z);
            touched.add(pos);
            if (!level.getBlockState(pos).is(Microblocks.BLOCK.get())) {
                level.setBlock(pos, Microblocks.BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
            }
            if (level.getBlockEntity(pos) instanceof MicroBlockEntity be && be.add(new Part(x0, y0, z0, x1, y1, z1, material))) count++;
            else error = "could not put the box " + x0 + "," + y0 + "," + z0 + "-" + x1 + "," + y1 + "," + z1 + " at " + x + "," + y + "," + z;
        }

        /** An ordinary block next to the microblocks, to compare the look and the light with. */
        void vanilla(int x, int y, int z, BlockState state) {
            level.setBlock(origin.offset(x, y, z), state, Block.UPDATE_ALL);
        }

        void face(int x, int y, int z, Direction side, int size, Block m) {
            int t = size * 2;
            int[] min = {0, 0, 0}, max = {16, 16, 16};
            int axis = side.getAxis().ordinal();
            if (side.getAxisDirection() == Direction.AxisDirection.POSITIVE) min[axis] = 16 - t; else max[axis] = t;
            box(x, y, z, min[0], min[1], min[2], max[0], max[1], max[2], m);
        }

        /** A vertical post at one of the four corners of the block space. */
        void post(int x, int y, int z, boolean xHigh, boolean zHigh, int size, Block m) {
            int t = size * 2;
            box(x, y, z, xHigh ? 16 - t : 0, 0, zHigh ? 16 - t : 0, xHigh ? 16 : t, 16, zHigh ? 16 : t, m);
        }

        /** A beam along x, at the top or the bottom, at the front (low z) or the back. */
        void beamX(int x, int y, int z, boolean yHigh, boolean zHigh, int size, Block m) {
            int t = size * 2;
            box(x, y, z, 0, yHigh ? 16 - t : 0, zHigh ? 16 - t : 0, 16, yHigh ? 16 : t, zHigh ? 16 : t, m);
        }

        /** A beam along z. */
        void beamZ(int x, int y, int z, boolean xHigh, boolean yHigh, int size, Block m) {
            int t = size * 2;
            box(x, y, z, xHigh ? 16 - t : 0, yHigh ? 16 - t : 0, 0, xHigh ? 16 : t, yHigh ? 16 : t, 16, m);
        }

        void cube(int x, int y, int z, boolean xHigh, boolean yHigh, boolean zHigh, int size, Block m) {
            int t = size * 2;
            box(x, y, z, xHigh ? 16 - t : 0, yHigh ? 16 - t : 0, zHigh ? 16 - t : 0, xHigh ? 16 : t, yHigh ? 16 : t, zHigh ? 16 : t, m);
        }
    }

    // ---- the buildings

    private static void shapes(B b) {
        List<Block> materials = MicroMaterials.samples();
        // a row of planes from 1/16 to 8/16 thick, a row of sticks from 1/16 to 8/16 wide, a row of cubes, a row of boxes of odd sizes
        for (int i = 0; i < 8; i++) {
            Block m = materials.get(i % materials.size());
            b.box(i, 0, 0, 0, 0, 0, 16, i + 1, 16, m);                                              // planes
            b.box(i, 0, 2, 6 - i / 2, 0, 0, 7 + i / 2 + (i % 2), i + 1, 16, materials.get(8 + i % 8));   // sticks, getting wider and taller
            b.box(i, 0, 4, 2, 0, 2, 2 + i + 1, i + 1, 2 + i + 1, materials.get(2 + i % 6));        // cubes
            b.box(i, 0, 6, 1, 0, 1, 1 + 1 + i, 2 + (i * 3) % 9 + 1, 15, materials.get(10 + i % 8)); // odd boxes
        }
        // ordinary blocks of the same materials in a row behind
        for (int i = 0; i < 8; i++) b.vanilla(i, 0, 8, i % 2 == 0 ? Blocks.STONE_BRICK_SLAB.defaultBlockState() : Blocks.STONE_BRICKS.defaultBlockState());
        // several parts of different materials in one block space, and the rules
        b.box(10, 0, 0, 0, 0, 0, 3, 16, 16, Blocks.BRICKS);
        b.box(10, 0, 0, 13, 0, 13, 16, 16, 16, Blocks.OAK_PLANKS);
        b.box(10, 0, 0, 13, 0, 0, 16, 5, 3, Blocks.GOLD_BLOCK);
        if (b.error != null) return;
        MicroBlockEntity be = (MicroBlockEntity) b.level.getBlockEntity(b.origin.offset(10, 0, 0));
        if (be == null || be.parts().size() != 3) { b.error = "multi block has " + (be == null ? "no block entity" : be.parts().size() + " parts"); return; }
        if (be.add(new Part(2, 0, 0, 5, 16, 16, Blocks.STONE))) { b.error = "an overlapping part was accepted"; return; }
        if (!be.add(new Part(3, 0, 0, 13, 2, 16, Blocks.STONE))) { b.error = "a part that only touches was refused"; return; }
        if (be.shape().isEmpty() || be.shape() == Shapes.block()) { b.error = "the shape of a microblock is empty or a full block"; return; }
        be.remove(be.parts().get(be.parts().size() - 1));
        if (be.parts().size() != 3) b.error = "removing a part left " + be.parts().size();
    }

    private static void cottage(B b) {
        Block wall = Blocks.STONE_BRICKS, frame = Blocks.OAK_PLANKS, floor = Blocks.SPRUCE_PLANKS, roof = Blocks.DARK_OAK_PLANKS;
        final int w = 7, d = 6;
        for (int x = 0; x < w; x++) {
            for (int z = 0; z < d; z++) {
                boolean edgeX = x == 0 || x == w - 1, edgeZ = z == 0 || z == d - 1;
                if (!edgeX && !edgeZ) {
                    b.face(x, 0, z, Direction.DOWN, 2, floor);                          // the floor
                    continue;
                }
                for (int y = 0; y < 3; y++) {
                    if (z == 0 && x == 3 && y < 2) continue;                              // the door
                    Block m = (z == 0 && y == 1 && (x == 1 || x == 5)) ? Blocks.GLASS : wall;   // windows
                    Direction side = z == 0 ? Direction.NORTH : z == d - 1 ? Direction.SOUTH : x == 0 ? Direction.WEST : Direction.EAST;
                    b.face(x, y, z, side, 4, m);
                    if (edgeX && edgeZ) b.post(x, y, z, x == w - 1, z == 0, 4, frame);   // the corner post fills the L
                }
            }
        }
        // ordinary blocks next to the cottage: the same walls, stairs and slabs to compare with
        for (int y = 0; y < 3; y++) { b.vanilla(-4, y, 0, Blocks.STONE_BRICKS.defaultBlockState()); b.vanilla(-3, y, 0, Blocks.STONE_BRICKS.defaultBlockState()); }
        b.vanilla(-4, 3, 0, Blocks.DARK_OAK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
        b.vanilla(-3, 3, 0, Blocks.DARK_OAK_SLAB.defaultBlockState());
        b.vanilla(-4, 0, 2, Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
        b.vanilla(-3, 0, 2, Blocks.STONE_BRICK_SLAB.defaultBlockState());
        // a stepped roof: every level is a lower half (slab at the bottom) and an upper half that is one block narrower, so it rises in half steps
        for (int level = 0; level < 3; level++) {
            for (int x = -1; x <= w; x++) {
                for (int z = level - 1; z <= d - level; z++) {
                    b.face(x, 3 + level, z, Direction.DOWN, 4, roof);
                    if (z >= level && z <= d - level - 1) b.face(x, 3 + level, z, Direction.UP, 4, roof);
                }
            }
        }
    }

    private static void tower(B b) {
        Block wall = Blocks.COBBLESTONE, trim = Blocks.STONE_BRICKS;
        for (int y = 0; y < 6; y++) {
            for (int x = 0; x < 3; x++) {
                for (int z = 0; z < 3; z++) {
                    if (x == 1 && z == 1) continue;
                    boolean edgeX = x != 1, edgeZ = z != 1;
                    Direction side = z == 0 ? Direction.NORTH : z == 2 ? Direction.SOUTH : x == 0 ? Direction.WEST : Direction.EAST;
                    if (y < 2 && x == 1 && z == 0) continue;                              // a doorway
                    b.face(x, y, z, side, 4, y % 2 == 0 ? wall : trim);
                    if (edgeX && edgeZ) b.post(x, y, z, x == 2, z == 0, 4, trim);
                }
            }
        }
        // an ordinary cobblestone tower piece next to it
        for (int y = 0; y < 4; y++) b.vanilla(-3, y, 1, Blocks.COBBLESTONE.defaultBlockState());
        b.vanilla(-3, 4, 1, Blocks.COBBLESTONE_SLAB.defaultBlockState());
        // the battlements: a cube on every corner and a strip between them
        for (int x = 0; x < 3; x++) {
            for (int z = 0; z < 3; z++) {
                if (x == 1 && z == 1) continue;
                boolean edgeX = x != 1, edgeZ = z != 1;
                if (edgeX && edgeZ) b.cube(x, 6, z, x == 2, false, z == 2, 4, trim);
                else if (z == 0 || z == 2) b.beamX(x, 6, z, false, z == 2, 2, trim);
                else b.beamZ(x, 6, z, x == 2, false, 2, trim);
            }
        }
    }

    private static void stairs(B b) {
        Block step = Blocks.STONE_BRICKS, rail = Blocks.OAK_PLANKS;
        // an ordinary staircase beside it, made of the stairs of the game
        for (int i = 0; i < 8; i++) {
            for (int z = 5; z < 8; z++) b.vanilla(i, i, z, Blocks.STONE_BRICK_STAIRS.defaultBlockState().setValue(StairBlock.FACING, Direction.EAST));
            for (int j = 0; j < i; j++) for (int z = 5; z < 8; z++) b.vanilla(i, j, z, Blocks.STONE_BRICKS.defaultBlockState());
        }
        for (int i = 0; i < 8; i++) {
            for (int z = 0; z < 3; z++) {
                b.face(i, i, z, Direction.DOWN, 4, step);
                if (i > 0) { b.face(i, i - 1, z, Direction.DOWN, 4, step); b.face(i, i - 1, z, Direction.UP, 4, step); }   // a thick body under each step
            }
            b.beamX(i, i, 0, true, false, 1, rail);                                      // a thin railing along each side
            b.beamX(i, i, 2, true, true, 1, rail);
        }
    }

    private static void pergola(B b) {
        Block post = Blocks.OAK_PLANKS, beam = Blocks.SPRUCE_PLANKS;
        for (int y = 0; y < 2; y++) {
            for (int cx : new int[]{0, 4}) for (int cz : new int[]{0, 4}) b.post(cx, y, cz, cx == 4, cz == 4, 3, post);
        }
        for (int x = 0; x < 5; x++) {
            b.beamX(x, 2, 0, true, false, 2, beam);
            b.beamX(x, 2, 4, true, true, 2, beam);
        }
        for (int z = 1; z < 4; z++) {
            b.beamZ(0, 2, z, false, true, 2, beam);
            b.beamZ(4, 2, z, true, true, 2, beam);
        }
        for (int x = 1; x < 4; x++) for (int z = 1; z < 4; z++) b.beamX(x, 2, z, true, false, 1, beam);   // the thin rafters on top
        for (int x = 1; x < 4; x++) for (int z = 1; z < 4; z++) b.face(x, 0, z, Direction.DOWN, 1, Blocks.COBBLESTONE);  // a stone floor
        // ordinary fences and slabs beside it
        for (int y = 0; y < 3; y++) { b.vanilla(7, y, 0, Blocks.OAK_FENCE.defaultBlockState()); b.vanilla(7, y, 4, Blocks.OAK_FENCE.defaultBlockState()); }
        for (int z = 0; z <= 4; z++) b.vanilla(7, 3, z, Blocks.SPRUCE_SLAB.defaultBlockState());
        for (int x = 1; x < 4; x++) for (int z = 1; z < 4; z++) b.vanilla(x + 8, 0, z, Blocks.COBBLESTONE_SLAB.defaultBlockState());
    }

    private static void palette(B b) {
        List<Block> materials = MicroMaterials.samples();
        for (int i = 0; i < materials.size(); i++) {
            int x = i % 10, z = (i / 10) * 3;
            b.face(x, 0, z, Direction.DOWN, 4, materials.get(i));
            b.cube(x, 0, z, false, true, false, 2, materials.get(i));
            b.cube(x, 0, z, true, true, true, 2, materials.get((i + 5) % materials.size()));
            b.vanilla(x, 0, z + 1, materials.get(i).defaultBlockState());          // the same block, whole, behind it
        }
    }

    /** A table with a chair, a shelf with books and a lamp: boxes of sizes that no slab or stair has (2/16 legs, 12/16 seats, books 2/16 thick ...). */
    private static void furniture(B b) {
        Block wood = Blocks.SPRUCE_PLANKS, dark = Blocks.DARK_OAK_PLANKS, light = Blocks.BIRCH_PLANKS;
        // the table: a top 2/16 thick over two blocks each way, four legs 2/16 wide
        for (int x = 1; x <= 2; x++) {
            for (int z = 1; z <= 2; z++) {
                b.box(x, 0, z, 0, 14, 0, 16, 16, 16, wood);
                int lx = x == 1 ? 1 : 13, lz = z == 1 ? 1 : 13;
                b.box(x, 0, z, lx, 0, lz, lx + 2, 14, lz + 2, dark);
            }
        }
        // a chair: seat 12 x 2 x 12, four legs 2 x 8 x 2, a back 12 x 14 x 2 (it reaches into the block above)
        b.box(4, 0, 1, 2, 8, 2, 14, 10, 14, light);
        for (int lx : new int[]{2, 12}) for (int lz : new int[]{2, 12}) b.box(4, 0, 1, lx, 0, lz, lx + 2, 8, lz + 2, dark);
        b.box(4, 0, 1, 2, 10, 12, 14, 16, 14, light);
        b.box(4, 1, 1, 2, 0, 12, 14, 8, 14, light);
        // a shelf: two sides, a back and three boards, with books of different sizes and a lamp on the table
        b.box(7, 0, 1, 0, 0, 0, 2, 16, 16, wood);
        b.box(7, 0, 1, 14, 0, 0, 16, 16, 16, wood);
        b.box(7, 0, 1, 2, 0, 14, 14, 16, 16, wood);
        for (int y : new int[]{0, 7, 14}) b.box(7, 0, 1, 2, y, 0, 14, y + 2, 14, wood);
        Block[] bookMaterials = {Blocks.BRICKS, Blocks.EMERALD_BLOCK, Blocks.GOLD_BLOCK, Blocks.DIAMOND_BLOCK, Blocks.TERRACOTTA, Blocks.COAL_BLOCK};
        int x = 3;
        for (int i = 0; i < 4; i++) {                                 // books on the lowest board: thin, tall, deep
            int thick = 2 + (i % 2), height = 2 + i;     // between the board at 2 and the next at 7
            b.box(7, 0, 1, x, 2, 3, x + thick, 2 + height, 12, bookMaterials[i]);
            x += thick;
        }
        x = 3;
        for (int i = 0; i < 3; i++) {                                 // and on the middle one
            int thick = 2 + i;
            b.box(7, 0, 1, x, 9, 4, x + thick, 9 + 3 + i, 12, bookMaterials[2 + i]);
            x += thick + 1;
        }
        // the table of the mod, between the chair and the shelf
        b.level.setBlock(b.origin.offset(5, 0, 1), Microblocks.TABLE.get().defaultBlockState(), Block.UPDATE_ALL);
        // a lamp on the table: a pole 2 wide and a shade 6 x 5 x 6 of glass
        b.box(1, 1, 1, 14, 0, 14, 16, 8, 16, dark);
        b.box(1, 1, 1, 10, 8, 10, 16, 13, 16, Blocks.GLASS);
    }
}
