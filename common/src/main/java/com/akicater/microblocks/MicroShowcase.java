package com.akicater.microblocks;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;
import java.util.Locale;
import java.util.Random;

/**
 * Used by the autotest to make the pictures of the description: large buildings that show what the parts of 1/16 of a block can do (a half-timbered manor with a
 * fine slope of the roof, a humped bridge over a pond, a garden with a market stall, a castle gate). Coordinates of the helper {@code a} are in sixteenths of
 * a block from the origin of the building, a box that crosses the border of a block space is cut into the parts of the spaces it touches.
 */
public final class MicroShowcase {
    private MicroShowcase() {}

    public static final List<String> NAMES = List.of("manor", "bridge", "market", "castle");
    private static BlockPos base;

    private static BlockPos originOf(String name) {
        return base.offset(NAMES.indexOf(name) * 44 - 66, 0, 30);
    }

    public static String run(ServerLevel level, BlockPos playerPos, String name) {
        try {
            if (base == null) base = playerPos;
            if (!NAMES.contains(name)) return "unknown showcase " + name;
            S s = new S(level, originOf(name));
            switch (name) {
                case "manor" -> manor(s);
                case "bridge" -> bridge(s);
                case "market" -> market(s);
                case "castle" -> castle(s);
            }
            System.out.println("AUTOTEST showcase " + name + ": " + s.count + " parts (" + s.refused + " refused)");
            return s.count > 20 ? "ok" : name + ": only " + s.count + " parts";
        } catch (Throwable t) {
            t.printStackTrace();
            return t.toString();
        }
    }

    /** The camera of a picture of a building: {x, y, z of the camera, x, y, z of the point looked at}, in blocks from the origin. */
    private static double[] camera(String name) {
        return switch (name) {
            case "manor" -> new double[]{-2.5, 3.4, -8.5, 9.5, 4.6, 3.5};
            case "bridge" -> new double[]{5.0, 3.6, -5.0, 12.8, 1.4, 7.5};
            case "market" -> new double[]{2.2, 3.1, -0.6, 9.5, 1.5, 6.5};
            default -> new double[]{6, 3.8, -13, 6, 4.3, 0};
        };
    }

    public static String viewCommand(String name) {
        BlockPos o = originOf(name);
        double[] v = camera(name);
        double cx = o.getX() + v[0], cy = o.getY() + v[1], cz = o.getZ() + v[2];
        double dx = o.getX() + v[3] - cx, dy = o.getY() + v[4] - cy, dz = o.getZ() + v[5] - cz;
        double yaw = Math.toDegrees(Math.atan2(-dx, dz));
        double pitch = -Math.toDegrees(Math.atan2(dy, Math.hypot(dx, dz)));
        return String.format(Locale.ROOT, "tp @p %.2f %.2f %.2f %.1f %.1f", cx, cy, cz, yaw, pitch);
    }

    /** A coloured block by its name: the colours are not fields of the game on every version. */
    private static Block dye(String id) {
        Block block = com.akicater.lib.Compat.blockOf("minecraft:" + id);
        return block != null ? block : Blocks.STONE;
    }

    private static final class S {
        final ServerLevel level;
        final BlockPos origin;
        int count, refused;

        S(ServerLevel level, BlockPos origin) {
            this.level = level;
            this.origin = origin;
        }

        /** A box from (x0, y0, z0) to (x1, y1, z1) in sixteenths of a block from the origin, cut into the block spaces it touches. */
        void a(int x0, int y0, int z0, int x1, int y1, int z1, Block m) {
            int ax = Math.min(x0, x1), bx = Math.max(x0, x1), ay = Math.min(y0, y1), by = Math.max(y0, y1), az = Math.min(z0, z1), bz = Math.max(z0, z1);
            if (ax == bx || ay == by || az == bz) return;
            for (int sx = Math.floorDiv(ax, 16); sx <= Math.floorDiv(bx - 1, 16); sx++) {
                for (int sy = Math.floorDiv(ay, 16); sy <= Math.floorDiv(by - 1, 16); sy++) {
                    for (int sz = Math.floorDiv(az, 16); sz <= Math.floorDiv(bz - 1, 16); sz++) {
                        BlockPos pos = origin.offset(sx, sy, sz);
                        if (!level.getBlockState(pos).is(Microblocks.BLOCK.get())) level.setBlock(pos, Microblocks.BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
                        int[] box = {Math.max(ax, sx * 16) - sx * 16, Math.max(ay, sy * 16) - sy * 16, Math.max(az, sz * 16) - sz * 16,
                                Math.min(bx, sx * 16 + 16) - sx * 16, Math.min(by, sy * 16 + 16) - sy * 16, Math.min(bz, sz * 16 + 16) - sz * 16};
                        if (!(level.getBlockEntity(pos) instanceof MicroBlockEntity be)) { refused++; continue; }
                        // what is already there wins: the new box is cut around it
                        java.util.List<int[]> boxes = new java.util.ArrayList<>(List.of(box));
                        for (Part other : List.copyOf(be.parts())) {
                            java.util.List<int[]> next = new java.util.ArrayList<>();
                            for (int[] b : boxes) minus(b, other, next);
                            boxes = next;
                        }
                        for (int[] b : boxes) {
                            if (be.add(new Part(b[0], b[1], b[2], b[3], b[4], b[5], m))) count++;
                            else refused++;          // the space is full
                        }
                    }
                }
            }
        }

        /** The parts of the box {@code b} that lie outside of {@code o}. */
        private static void minus(int[] b, Part o, java.util.List<int[]> out) {
            if (o.maxX <= b[0] || o.minX >= b[3] || o.maxY <= b[1] || o.minY >= b[4] || o.maxZ <= b[2] || o.minZ >= b[5]) { out.add(b); return; }
            int x0 = Math.max(b[0], o.minX), x1 = Math.min(b[3], o.maxX), y0 = Math.max(b[1], o.minY), y1 = Math.min(b[4], o.maxY);
            if (b[0] < x0) out.add(new int[]{b[0], b[1], b[2], x0, b[4], b[5]});
            if (x1 < b[3]) out.add(new int[]{x1, b[1], b[2], b[3], b[4], b[5]});
            if (b[2] < o.minZ) out.add(new int[]{x0, b[1], b[2], x1, b[4], o.minZ});
            if (o.maxZ < b[5]) out.add(new int[]{x0, b[1], o.maxZ, x1, b[4], b[5]});
            int z0 = Math.max(b[2], o.minZ), z1 = Math.min(b[5], o.maxZ);
            if (b[1] < y0) out.add(new int[]{x0, b[1], z0, x1, y0, z1});
            if (y1 < b[4]) out.add(new int[]{x0, y1, z0, x1, b[4], z1});
        }

        void set(int x, int y, int z, BlockState state) {
            level.setBlock(origin.offset(x, y, z), state, Block.UPDATE_ALL);
        }

        /** A wall along x (or z) from l0 to l1 and t0 to t1 across, y0 to y1 high, with holes {from, to, bottom, top} along it. */
        void wall(boolean alongX, int l0, int l1, int t0, int t1, int y0, int y1, Block m, int[]... holes) {
            java.util.Arrays.sort(holes, java.util.Comparator.comparingInt(h -> h[0]));
            int cursor = l0;
            for (int[] h : holes) {
                if (h[0] > cursor) piece(alongX, cursor, h[0], t0, t1, y0, y1, m);
                piece(alongX, h[0], h[1], t0, t1, y0, h[2], m);
                piece(alongX, h[0], h[1], t0, t1, h[3], y1, m);
                cursor = h[1];
            }
            if (cursor < l1) piece(alongX, cursor, l1, t0, t1, y0, y1, m);
        }

        private void piece(boolean alongX, int l0, int l1, int t0, int t1, int y0, int y1, Block m) {
            if (alongX) a(l0, y0, t0, l1, y1, t1, m); else a(t0, y0, l0, t1, y1, l1, m);
        }

        /** A window in a wall along x (or z) in the hole {l0..l1, y0..y1}: a frame in the hole, a pane, a ledge outside and, if asked, two shutters. */
        void window(boolean alongX, int l0, int l1, int t0, int t1, int y0, int y1, Block frame, boolean shutters) {
            int mid = (t0 + t1) / 2, lm = (l0 + l1) / 2;
            piece(alongX, l0, l0 + 2, t0, t1, y0, y1, frame);                                   // the jambs
            piece(alongX, l1 - 2, l1, t0, t1, y0, y1, frame);
            piece(alongX, l0, l1, t0, t1, y0, y0 + 2, frame);                                   // the sill and the head
            piece(alongX, l0, l1, t0, t1, y1 - 2, y1, frame);
            piece(alongX, l0 + 2, l1 - 2, mid - 1, mid + 1, y0 + 2, y1 - 2, Blocks.GLASS);      // the pane
            piece(alongX, lm - 1, lm + 1, t0 + 1, t1 - 1, y0 + 2, y1 - 2, frame);               // the bars
            piece(alongX, l0 + 2, l1 - 2, t0 + 1, t1 - 1, (y0 + y1) / 2 - 1, (y0 + y1) / 2 + 1, frame);
            piece(alongX, l0 - 2, l1 + 2, t0 - 3, t0, y0 - 2, y0 + 1, frame);                   // the ledge outside
            if (shutters) {
                Block shutter = dye("green_terracotta");
                piece(alongX, l0 - 8, l0 - 1, t0 - 2, t0, y0 - 2, y1 + 2, shutter);
                piece(alongX, l1 + 1, l1 + 8, t0 - 2, t0, y0 - 2, y1 + 2, shutter);
            }
        }
    }

    // ---- the manor: stone below, plaster and timber above, a steep roof of shingles that are 4/16 deep and 3/16 high

    private static void manor(S s) {
        Block cob = Blocks.COBBLESTONE, stone = Blocks.STONE_BRICKS, plaster = dye("white_terracotta"), dark = Blocks.DARK_OAK_PLANKS, wood = Blocks.SPRUCE_PLANKS;
        Block roof = Blocks.DEEPSLATE_TILES, ridge = Blocks.DEEPSLATE_BRICKS, brick = Blocks.BRICKS;
        final int W = 144, D = 112;
        // the foundation and the floor
        s.a(-4, 0, -4, W + 4, 12, D + 4, cob);
        s.a(-2, 12, -2, W + 2, 14, D + 2, stone);
        // the walls of the ground floor: thick stone
        int[] frontWin1 = {18, 42, 24, 44}, frontWin2 = {102, 126, 24, 44}, door = {60, 84, 14, 46};
        s.wall(true, 0, W, 0, 6, 14, 48, stone, frontWin1, frontWin2, door);
        s.wall(true, 0, W, D - 6, D, 14, 48, stone, new int[]{30, 54, 24, 44}, new int[]{90, 114, 24, 44});
        s.wall(false, 6, D - 6, 0, 6, 14, 48, stone, new int[]{40, 72, 24, 44});
        s.wall(false, 6, D - 6, W - 6, W, 14, 48, stone);
        s.window(true, 18, 42, 0, 6, 24, 44, dark, true);
        s.window(true, 102, 126, 0, 6, 24, 44, dark, true);
        s.window(true, 30, 54, D - 6, D, 24, 44, dark, false);
        s.window(true, 90, 114, D - 6, D, 24, 44, dark, false);
        s.window(false, 40, 72, 0, 6, 24, 44, dark, false);
        // the door: planks with an arch of stone over it
        s.a(62, 14, 2, 82, 44, 4, dark);
        s.a(60, 46, -1, 84, 50, 7, stone);
        s.a(58, 14, -1, 62, 50, 7, stone);
        s.a(82, 14, -1, 86, 50, 7, stone);
        s.a(70, 14, 1, 72, 44, 5, wood);
        s.a(76, 26, 0, 79, 30, 2, Blocks.GOLD_BLOCK);
        // the steps and the porch
        s.a(52, 0, -10, 92, 8, -4, stone);
        s.a(52, 0, -18, 92, 4, -10, stone);
        for (int px : new int[]{54, 86}) s.a(px, 4, -18, px + 4, 48, -14, dark);
        s.a(50, 48, -22, 96, 52, 0, wood);
        s.a(54, 52, -18, 92, 56, -4, roof);
        s.a(58, 56, -14, 88, 59, -8, roof);
        // the floor between, the upper walls: plaster in a frame of timber
        s.a(0, 48, 0, W, 52, D, wood);
        s.a(-3, 50, -3, W + 3, 53, D + 3, dark);
        int[][] upFront = {{14, 38, 60, 84}, {60, 84, 60, 84}, {106, 130, 60, 84}};
        s.wall(true, 0, W, 0, 4, 52, 92, plaster, upFront);
        s.wall(true, 0, W, D - 4, D, 52, 92, plaster, new int[]{30, 54, 60, 84}, new int[]{90, 114, 60, 84});
        s.wall(false, 4, D - 4, 0, 4, 52, 92, plaster, new int[]{36, 76, 60, 84});
        s.wall(false, 4, D - 4, W - 4, W, 52, 92, plaster);
        for (int[] w : upFront) s.window(true, w[0], w[1], 0, 4, 60, 84, dark, false);
        s.window(true, 30, 54, D - 4, D, 60, 84, dark, false);
        s.window(true, 90, 114, D - 4, D, 60, 84, dark, false);
        s.window(false, 36, 76, 0, 4, 60, 84, dark, false);
        // the timber: corner posts, studs and braces on the faces
        for (int cx : new int[]{-2, W - 6}) for (int cz : new int[]{-2, D - 6}) s.a(cx, 14, cz, cx + 8, 94, cz + 8, dark);
        for (int x : new int[]{46, 92, 140 - 8}) { s.a(x, 52, -2, x + 3, 90, 5, dark); s.a(x, 52, D - 5, x + 3, 90, D + 2, dark); }
        s.a(-2, 88, -2, W + 2, 94, 6, dark);
        s.a(-2, 88, D - 6, W + 2, 94, D + 2, dark);
        s.a(-2, 88, 4, 6, 94, D - 4, dark);
        s.a(W - 6, 88, 4, W + 2, 94, D - 4, dark);
        // the roof: the shingles climb 3/16 for every 4/16 and overhang the walls
        for (int i = 0; i < 20; i++) {
            int y = 92 + 3 * i;
            s.a(-10, y, -24 + 4 * i, W + 10, y + 4, -20 + 4 * i, roof);
            s.a(-10, y, D + 20 - 4 * i, W + 10, y + 4, D + 24 - 4 * i, roof);
        }
        s.a(-10, 151, 50, W + 10, 156, 62, ridge);
        s.a(-12, 92, -26, W + 12, 95, -22, ridge);
        s.a(-12, 92, D + 22, W + 12, 95, D + 26, ridge);
        // the gables are filled up to the roof, with a beam along each slope
        for (int k = 0; k < 18; k++) {
            int z0 = -12 + 4 * k;
            if (z0 >= 56) break;
            int zz0 = Math.max(0, z0), zz1 = Math.min(D, D - z0);
            for (int gx : new int[]{0, W - 4}) s.a(gx, 94 + 3 * k, zz0, gx + 4, 97 + 3 * k, zz1, plaster);
        }
        // the chimney on the east side
        s.a(W, 0, 36, W + 22, 12, 72, cob);
        s.a(W, 12, 40, W + 20, 156, 68, brick);
        s.a(W - 2, 156, 38, W + 22, 162, 70, stone);
        s.a(W + 3, 162, 46, W + 15, 164, 62, Blocks.BLACKSTONE);
        // the planters under the front windows, with flowers
        for (int[] w : new int[][]{{18, 42}, {102, 126}}) {
            s.a(w[0] - 2, 14, -8, w[1] + 2, 22, -2, dark);
            s.a(w[0], 22, -7, w[1], 25, -3, Blocks.MOSS_BLOCK);
            Block[] flowers = {dye("pink_concrete"), dye("red_concrete"), dye("yellow_concrete"), dye("magenta_concrete"), dye("orange_concrete"), dye("white_concrete")};
            int i = 0;
            for (int x = w[0] + 2; x < w[1] - 3; x += 5) { s.a(x, 25, -6, x + 3, 29, -3, flowers[i++ % flowers.length]); }
        }
        // a path of stones to the door, a low wall and lanterns
        Random r = new Random(7);
        for (int z = -26; z > -150; z -= 9) {
            for (int dx = 0; dx < 2; dx++) {
                int x = 58 + dx * 14 + r.nextInt(5) - 2;
                s.a(x, 0, z - r.nextInt(3), x + 12 + r.nextInt(3), 2, z + 6, r.nextBoolean() ? Blocks.STONE : Blocks.ANDESITE);
            }
        }
        // a lantern hangs on each porch post
        for (int px : new int[]{54, 86}) {
            s.a(px - 4, 36, -22, px, 40, -14, dark);
            s.a(px - 8, 24, -21, px - 2, 36, -15, Blocks.SHROOMLIGHT);
            s.a(px - 9, 36, -22, px - 1, 38, -14, dark);
        }
    }

    // ---- the humped bridge over a pond

    private static void bridge(S s) {
        Block stone = Blocks.STONE_BRICKS, cobble = Blocks.COBBLESTONE, moss = Blocks.MOSSY_STONE_BRICKS, dark = Blocks.DARK_OAK_PLANKS;
        // the pond: water two blocks deep, with a rim of the dirt that was there
        for (int x = -2; x <= 26; x++) for (int z = 2; z <= 14; z++) {
            double dx = (x - 12) / 14.0, dz = (z - 8) / 6.5;
            if (dx * dx + dz * dz <= 1) { s.set(x, -1, z, Blocks.WATER.defaultBlockState()); s.set(x, -2, z, Blocks.WATER.defaultBlockState()); }
        }
        final int zc = 8 * 16;                              // the bridge runs along z, across the pond
        final int x0 = 11 * 16, x1 = x0 + 52;
        Random r = new Random(3);
        for (int z = 16; z < 240; z += 4) {
            double u = (z + 2 - zc) / 112.0;
            double hump = 34 * (1 - u * u);
            int top = (int) (6 + hump);
            // the arch: nothing under it where the water is
            double v = (z + 2 - zc) / 62.0;
            int arch = Math.abs(v) < 1 ? (int) (46 * Math.sqrt(1 - v * v)) : 0;
            Block body = r.nextInt(6) == 0 ? moss : stone;
            s.a(x0, arch, z, x1, top, z + 4, body);
            if (arch > 0) s.a(x0, arch, z, x1, arch + 3, z + 4, Math.abs(v) < 0.08 ? cobble : stone);   // the arch ring
            s.a(x0 - 2, top, z, x1 + 2, top + 3, z + 4, cobble);        // the deck
            // the parapet: a wall with a cap
            for (int px : new int[]{x0 - 2, x1 - 4}) {
                s.a(px, top + 3, z, px + 6, top + 15, z + 4, stone);
                s.a(px - 2, top + 15, z, px + 8, top + 18, z + 4, cobble);
            }
        }
        // the abutments at the banks, with a post and a lantern on each corner
        for (int zz : new int[]{16, 232}) for (int px : new int[]{x0 - 6, x1 - 4}) {
            int top = (int) (6 + 34 * (1 - Math.pow((zz - zc) / 112.0, 2)));
            s.a(px, 0, zz - 2, px + 10, top + 30, zz + 8, stone);
            s.a(px - 2, top + 30, zz - 4, px + 12, top + 34, zz + 10, cobble);
            s.a(px + 1, top + 34, zz, px + 9, top + 38, zz + 6, dark);
            s.a(px + 2, top + 38, zz + 0, px + 8, top + 50, zz + 6, Blocks.SHROOMLIGHT);
            s.a(px + 1, top + 50, zz - 1, px + 9, top + 53, zz + 7, dark);
        }
        // reeds along the banks, a bench, stepping stones
        for (int i = 0; i < 26; i++) {
            double ang = r.nextDouble() * Math.PI * 2;
            int bx = (int) ((12 + 14.3 * Math.cos(ang)) * 16), bz = (int) ((8 + 6.8 * Math.sin(ang)) * 16);
            int h = 18 + r.nextInt(18);
            s.a(bx, 0, bz, bx + 2, h, bz + 2, dye("green_concrete"));
            if (r.nextBoolean()) s.a(bx - 1, h, bz - 1, bx + 3, h + 7, bz + 3, dye("brown_concrete"));
        }
        // a bench on the bank in front of the bridge, facing the water
        int bx = 19 * 16, bz = -2 * 16;
        s.a(bx, 10, bz, bx + 44, 13, bz + 14, dark);
        for (int lx : new int[]{bx + 3, bx + 38}) s.a(lx, 0, bz + 2, lx + 3, 10, bz + 12, dark);
        s.a(bx, 13, bz, bx + 44, 32, bz + 3, dark);
        s.a(bx + 1, 16, bz + 3, bx + 3, 28, bz + 5, dark);
        s.a(bx + 41, 16, bz + 3, bx + 43, 28, bz + 5, dark);
        for (int i = 0; i < 5; i++) s.a(17 * 16 + i * 14, -3, 8 * 16 + (i % 2) * 18, 17 * 16 + i * 14 + 12, -1, 8 * 16 + (i % 2) * 18 + 12, cobble);
    }

    // ---- a garden with a market stall, a well, barrels and flower beds

    private static void market(S s) {
        Block dark = Blocks.DARK_OAK_PLANKS, wood = Blocks.SPRUCE_PLANKS, oak = Blocks.OAK_PLANKS, stone = Blocks.STONE_BRICKS, cobble = Blocks.COBBLESTONE;
        Block red = dye("red_wool"), white = dye("white_wool");
        Random r = new Random(11);
        // the plaza: tiles of 8/16 laid in a chequer, a border of cobblestone
        for (int x = 0; x < 20 * 16; x += 16) for (int z = 0; z < 11 * 16; z += 16) {
            boolean border = x == 0 || z == 0 || x == 19 * 16 || z == 10 * 16;
            Block m = border ? cobble : ((x / 16 + z / 16) % 2 == 0 ? stone : Blocks.ANDESITE);
            s.a(x, 0, z, x + 16, 2, z + 16, m);
        }
        // the market stall: a counter at the front, a back wall with shelves, posts, a striped awning that slopes down to the front
        int sx = 9 * 16, sz = 5 * 16 + 8;
        s.a(sx, 2, sz, sx + 96, 6, sz + 22, dark);
        s.a(sx + 2, 6, sz + 2, sx + 94, 34, sz + 20, wood);
        s.a(sx - 2, 34, sz - 2, sx + 98, 38, sz + 24, oak);
        s.a(sx, 2, sz + 44, sx + 96, 70, sz + 48, wood);                              // the back wall
        for (int y : new int[]{26, 46}) s.a(sx + 4, y, sz + 36, sx + 92, y + 3, sz + 44, dark);   // two shelves
        for (int px : new int[]{sx, sx + 92}) { s.a(px, 2, sz + 44, px + 4, 96, sz + 48, dark); s.a(px, 2, sz - 2, px + 4, 78, sz + 2, dark); }
        for (int i = 0; i < 12; i++) {                                                // the awning, 12 stripes of 8/16; each step is 8 deep and 3 lower
            Block m = i % 2 == 0 ? red : white;
            for (int st = 0; st < 8; st++) {
                int y = 96 - st * 3, z1 = sz + 48 - st * 8;
                s.a(sx - 2 + i * 8, y, z1 - 8, sx + 6 + i * 8, y + 3, z1, m);
            }
            s.a(sx - 2 + i * 8, 70, sz - 16, sx + 6 + i * 8, 76, sz - 14, m);        // the valance that hangs over the front
        }
        // goods: trays of fruit on the counter, jars on the shelves
        Block[] fruit = {dye("red_concrete"), dye("orange_concrete"), dye("lime_concrete"), dye("yellow_concrete"), dye("purple_concrete")};
        for (int t = 0; t < 4; t++) {
            int tx = sx + 6 + t * 22;
            s.a(tx, 38, sz + 2, tx + 18, 41, sz + 20, dark);
            for (int fx = 0; fx < 3; fx++) for (int fz = 0; fz < 3; fz++) s.a(tx + 2 + fx * 5, 41, sz + 4 + fz * 5, tx + 6 + fx * 5, 45, sz + 8 + fz * 5, fruit[(t + fx + fz) % fruit.length]);
        }
        for (int j = 0; j < 8; j++) {
            s.a(sx + 8 + j * 10, 29, sz + 38, sx + 14 + j * 10, 38, sz + 43, fruit[j % fruit.length]);
            s.a(sx + 8 + j * 10, 49, sz + 38, sx + 14 + j * 10, 56, sz + 43, fruit[(j + 2) % fruit.length]);
        }
        // barrels and crates beside the stall
        for (int i = 0; i < 2; i++) {
            int bx = 7 * 16 + 2, bz = 6 * 16 + i * 18;
            s.a(bx, 2, bz, bx + 14, 24, bz + 14, wood);
            s.a(bx - 1, 7, bz - 1, bx + 15, 9, bz + 15, dark);
            s.a(bx - 1, 17, bz - 1, bx + 15, 19, bz + 15, dark);
            s.a(bx + 1, 24, bz + 1, bx + 13, 25, bz + 13, oak);
        }
        for (int i = 0; i < 3; i++) {
            int cx = 16 * 16 + 4, cz = 6 * 16 + i * 16;
            s.a(cx, 2, cz, cx + 14, 16, cz + 14, oak);
            for (int px : new int[]{cx, cx + 12}) for (int pz : new int[]{cz, cz + 12}) s.a(px, 2, pz, px + 2, 16, pz + 2, dark);
            s.a(cx + 2, 16, cz + 2, cx + 12, 19, cz + 12, fruit[i]);
        }
        // the well: a ring of stone, water, two posts, a roof of four layers, a crank with a rope and a bucket
        int wx = 3 * 16, wz = 5 * 16;
        s.a(wx, 2, wz, wx + 48, 22, wz + 8, stone); s.a(wx, 2, wz + 40, wx + 48, 22, wz + 48, stone);
        s.a(wx, 2, wz + 8, wx + 8, 22, wz + 40, stone); s.a(wx + 40, 2, wz + 8, wx + 48, 22, wz + 40, stone);
        s.a(wx - 2, 22, wz - 2, wx + 50, 25, wz + 10, cobble); s.a(wx - 2, 22, wz + 38, wx + 50, 25, wz + 50, cobble);
        s.a(wx - 2, 22, wz + 10, wx + 10, 25, wz + 38, cobble); s.a(wx + 38, 22, wz + 10, wx + 50, 25, wz + 38, cobble);
        s.a(wx + 8, 4, wz + 8, wx + 40, 15, wz + 40, Blocks.BLUE_ICE);
        for (int px : new int[]{wx, wx + 44}) s.a(px, 25, wz + 22, px + 4, 84, wz + 26, dark);
        for (int i = 0; i < 4; i++) s.a(wx - 6 + i * 3, 84 + i * 4, wz + 12 + i * 3, wx + 54 - i * 3, 88 + i * 4, wz + 36 - i * 3, Blocks.DEEPSLATE_TILES);
        s.a(wx + 4, 66, wz + 23, wx + 44, 69, wz + 25, oak);
        s.a(wx + 22, 42, wz + 23, wx + 24, 66, wz + 25, dye("green_concrete"));
        s.a(wx + 17, 32, wz + 18, wx + 29, 42, wz + 30, wood);
        // flower beds along the back: a border, soil, stems and flowers
        Block[] bloom = {dye("pink_concrete"), dye("red_concrete"), dye("yellow_concrete"), dye("magenta_concrete"), dye("orange_concrete"), dye("light_blue_concrete"), dye("white_concrete")};
        for (int bed : new int[]{1, 7, 13}) {
            int bx = bed * 16, bz = 11 * 16 + 4;
            s.a(bx, 0, bz, bx + 80, 6, bz + 5, oak); s.a(bx, 0, bz + 35, bx + 80, 6, bz + 40, oak);
            s.a(bx, 0, bz + 5, bx + 5, 6, bz + 35, oak); s.a(bx + 75, 0, bz + 5, bx + 80, 6, bz + 35, oak);
            s.a(bx + 5, 0, bz + 5, bx + 75, 4, bz + 35, Blocks.COARSE_DIRT);
            for (int fx = 0; fx < 7; fx++) for (int fz = 0; fz < 3; fz++) {
                int x = bx + 9 + fx * 9, z = bz + 8 + fz * 9, h = 6 + (fx * 3 + fz * 5) % 6;
                s.a(x + 1, 4, z + 1, x + 3, 4 + h, z + 3, dye("green_concrete"));
                s.a(x - 1, 4 + h, z - 1, x + 5, 8 + h, z + 5, bloom[(fx + fz * 2) % bloom.length]);
            }
        }
        // a fence behind the beds and lamp posts at the corners of the plaza
        for (int x = 0; x < 20 * 16; x += 16) {
            s.a(x, 0, 12 * 16 + 12, x + 4, 26, 12 * 16 + 16, dark);
            s.a(x + 4, 7, 12 * 16 + 13, x + 16, 10, 12 * 16 + 15, wood);
            s.a(x + 4, 17, 12 * 16 + 13, x + 16, 20, 12 * 16 + 15, wood);
        }
        for (int x : new int[]{16, 18 * 16}) {
            int lz = 9 * 16 + 4;
            s.a(x, 2, lz, x + 4, 60, lz + 4, dark);
            s.a(x - 2, 60, lz - 2, x + 6, 62, lz + 6, stone);
            s.a(x - 1, 62, lz - 1, x + 5, 74, lz + 5, Blocks.SHROOMLIGHT);
            s.a(x - 2, 74, lz - 2, x + 6, 77, lz + 6, dark);
        }
    }

    // ---- a castle gate: towers with corbels and pointed roofs, a wall with a walk and merlons, a round arch with a portcullis

    private static void castle(S s) {
        Block cobble = Blocks.COBBLESTONE, stone = Blocks.STONE_BRICKS, mossy = Blocks.MOSSY_STONE_BRICKS, slab = Blocks.DEEPSLATE_TILES, dark = Blocks.DARK_OAK_PLANKS;
        Random r = new Random(5);
        final int len = 12 * 16, gate0 = 5 * 16 + 4, gate1 = 7 * 16 + 12;
        // the wall: a battered base, the body, a walk and merlons on the outer side; the gate is a round arch cut through it
        for (int x = -80; x < len + 80; x += 4) {
            int top = 84;
            boolean inGate = x >= gate0 && x < gate1;
            double v = (x + 2 - (gate0 + gate1) / 2.0) / ((gate1 - gate0) / 2.0);
            int arch = inGate ? 38 + (int) (Math.sqrt(Math.max(0, 1 - v * v)) * 26) : 0;
            Block body = r.nextInt(7) == 0 ? mossy : stone;
            if (!inGate) {
                s.a(x, 0, -3, x + 4, 14, 27, cobble);
                s.a(x, 14, 0, x + 4, top, 24, body);
            } else {
                s.a(x, arch + 8, 0, x + 4, top, 24, body);       // over the arch only
            }
            s.a(x, top, -2, x + 4, top + 4, 26, cobble);                         // the walk
            if (((x / 4) % 6) < 3) s.a(x, top + 4, -2, x + 4, top + 22, 5, stone);       // merlons on the outer edge
            else s.a(x, top + 4, -2, x + 4, top + 7, 5, stone);
            s.a(x, top + 4, 22, x + 4, top + 12, 26, stone);                     // the low parapet inside
        }
        // the arch ring and the filling over the gate
        for (int x = gate0 - 8; x < gate1 + 8; x += 4) {
            double v = (x + 2 - (gate0 + gate1) / 2.0) / ((gate1 - gate0) / 2.0);
            if (Math.abs(v) < 1) {
                int arch = 38 + (int) (Math.sqrt(1 - v * v) * 26);
                s.a(x, arch, -4, x + 4, arch + 8, 28, cobble);
            } else if (Math.abs(v) < 1.35) s.a(x, 0, -4, x + 4, 100, 28, cobble);
        }
        // the portcullis: bars of iron, half raised, in the gate
        for (int x = gate0 + 2; x < gate1 - 2; x += 8) s.a(x, 30, 9, x + 2, 90, 11, Blocks.IRON_BLOCK);
        for (int y = 34; y < 90; y += 14) s.a(gate0 + 2, y, 9, gate1 - 2, y + 2, 11, Blocks.IRON_BLOCK);
        // the towers on both sides
        for (int[] t : new int[][]{{-96, 0}, {len + 16, 0}}) towerOf(s, t[0], t[1] - 20, 96, cobble, stone, mossy, slab, dark);
        // the banners over the gate and the torches beside it
        for (int[] b : new int[][]{{gate0 - 22, 0}, {gate1 + 6, 1}}) {
            s.a(b[0], 36, -5, b[0] + 16, 66, -4, b[1] == 0 ? dye("red_wool") : dye("blue_wool"));
            s.a(b[0] + 4, 30, -5, b[0] + 12, 36, -4, b[1] == 0 ? dye("red_wool") : dye("blue_wool"));
            s.a(b[0] - 1, 66, -6, b[0] + 17, 69, -4, Blocks.GOLD_BLOCK);
            s.a(b[0] + 2, 69, -6, b[0] + 14, 71, -4, dark);
        }
        for (int tx : new int[]{gate0 - 14, gate1 + 8}) {
            s.a(tx, 40, -6, tx + 4, 54, -2, dark);
            s.a(tx - 1, 54, -7, tx + 5, 60, -1, Blocks.SHROOMLIGHT);
        }
        // a cobbled road to the gate
        for (int z = -4; z > -230; z -= 8) for (int x = gate0 - 8; x < gate1 + 8; x += 8) {
            if (r.nextInt(5) == 0) continue;
            s.a(x, 0, z - 8, x + 8, 1 + r.nextInt(2), z, r.nextBoolean() ? Blocks.COBBLESTONE : Blocks.ANDESITE);
        }
    }

    /** A square tower of {@code size}/16 blocks: the walls, arrow slits, a corbelled top with merlons and a pointed roof, a banner. */
    private static void towerOf(S s, int x, int z, int size, Block cobble, Block stone, Block mossy, Block roof, Block dark) {
        int h = 150;
        s.a(x - 6, 0, z - 6, x + size + 6, 20, z + size + 6, cobble);
        s.a(x, 20, z, x + size, 70, z + size, stone);
        s.a(x, 70, z, x + size, h, z + size, cobble);
        s.a(x - 1, 70, z - 1, x + size + 1, 74, z + size + 1, stone);                   // a band
        for (int i = 0; i < 3; i++) {                                                  // the corbels: each ring steps out by 2/16
            s.a(x - 2 * (i + 1), h + i * 4, z - 2 * (i + 1), x + size + 2 * (i + 1), h + 4 + i * 4, z + size + 2 * (i + 1), stone);
        }
        int top = h + 12, lo = -6, hi = size + 6;
        for (int i = 0; i < size + 12; i += 12) {                                      // merlons on the four sides
            if ((i / 12) % 2 == 0) {
                s.a(x + lo + i, top, z + lo, x + lo + i + 12, top + 18, z + lo + 4, stone);
                s.a(x + lo + i, top, z + hi - 4, x + lo + i + 12, top + 18, z + hi, stone);
                s.a(x + lo, top, z + lo + i, x + lo + 4, top + 18, z + lo + i + 12, stone);
                s.a(x + hi - 4, top, z + lo + i, x + hi, top + 18, z + lo + i + 12, stone);
            }
        }
        s.a(x + lo, top, z + lo, x + hi, top + 4, z + hi, cobble);
        for (int i = 0; i < 9; i++) {                                                  // the roof: a pyramid of plates that get smaller
            int in = 4 + i * 5;
            if (x + lo + in >= x + hi - in) break;
            s.a(x + lo + in, top + 4 + i * 6, z + lo + in, x + hi - in, top + 10 + i * 6, z + hi - in, roof);
        }
        s.a(x + size / 2 - 1, top + 58, z + size / 2 - 1, x + size / 2 + 1, top + 110, z + size / 2 + 1, dark);          // the flagpole
        s.a(x + size / 2 + 1, top + 90, z + size / 2 - 1, x + size / 2 + 26, top + 106, z + size / 2 + 0, dye("red_wool"));
        for (int sx : new int[]{size / 4 - 1, size * 3 / 4 - 1}) {                     // arrow slits on the front and the back
            s.a(x + sx, 90, z - 1, x + sx + 3, 116, z, Blocks.BLACKSTONE);
            s.a(x + sx, 40, z - 1, x + sx + 3, 62, z, Blocks.BLACKSTONE);
        }
        for (int sz : new int[]{size / 4 - 1, size * 3 / 4 - 1}) {
            s.a(x - 1, 90, z + sz, x, 116, z + sz + 3, Blocks.BLACKSTONE);
            s.a(x + size, 90, z + sz, x + size + 1, 116, z + sz + 3, Blocks.BLACKSTONE);
        }
    }
}
