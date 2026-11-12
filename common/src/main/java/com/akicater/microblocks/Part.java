package com.akicater.microblocks;

import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** One microblock inside a block space: a box on a grid of sixteenths of a block, made of a material block. */
public final class Part {
    public static final int GRID = 16;

    public final int minX, minY, minZ, maxX, maxY, maxZ;     // in sixteenths, 0..16, min < max
    public final Block material;
    /** How the texture of the material lies: 0 is as the block (also 1), 2..6 the other ways to put the three axes of the block on x, y, z (see {@link MicroItemData#PERMUTATIONS}); a log turned on its side shows its rings on the side. */
    public final int orient;
    /** The texture is taken at the coordinates of the block space plus this shift (in sixteenths of each axis): the pieces of one box that lies across block spaces show one continuous texture. */
    public final int tx, ty, tz;
    /** Set for a box that was put down across block spaces: all its pieces share it, and breaking one takes them all and gives the whole box back. */
    public final Group group;
    /** The faces that are the inside of the block it was cut from (bits of the numbers of the directions, in the axes of the block): they show the underside texture (the dirt of a grass block), not the side or the top. */
    public final int inner;
    /** Null for a whole box. Otherwise the cells of the box that the part really holds (bit x + 16 * (y + 16 * z) of 4096): a box put in over others fills only the empty cells, with holes, and is still one part. */
    public final long[] mask;
    /** For the parts of a prefab that was put down as one piece (see {@link MicroStatue}): which part of the prefab this is, so that it can be made whole again. */
    public final int sub;

    /**
     * {@code fill}: the pieces of a box that was put in the mode that fills only the empty cells; they are not one box (breaking one takes all of them, but each is given
     * back as what it is, so that nothing is made out of nothing).
     */
    public record Group(int id, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean fill, boolean statue, int ox, int oy, int oz) {
        public Group(int id, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
            this(id, minX, minY, minZ, maxX, maxY, maxZ, false, false);
        }

        public Group(int id, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean fill) {
            this(id, minX, minY, minZ, maxX, maxY, maxZ, fill, false);
        }

        public Group(int id, int minX, int minY, int minZ, int maxX, int maxY, int maxZ, boolean fill, boolean statue) {
            this(id, minX, minY, minZ, maxX, maxY, maxZ, fill, statue, Integer.MIN_VALUE, 0, 0);
        }

        /**
         * A statue knows where the block space of its prefab begins in the world ({@code ox, oy, oz}, in sixteenths): the prefab is made whole again from where its
         * pieces are, not from the shift of their textures. A statue of an older world does not know it.
         */
        public boolean hasOrigin() {
            return ox != Integer.MIN_VALUE;
        }

        /** The numbers of the group as they are saved: its box, and its origin when it knows one. */
        public int[] saved() {
            return hasOrigin() ? new int[]{minX, minY, minZ, maxX, maxY, maxZ, ox, oy, oz} : new int[]{minX, minY, minZ, maxX, maxY, maxZ};
        }

        public static Group loaded(int id, int[] n, boolean fill, boolean statue) {
            if (id == 0 || n == null || (n.length != 6 && n.length != 9)) return null;
            return n.length == 9 ? new Group(id, n[0], n[1], n[2], n[3], n[4], n[5], fill, statue, n[6], n[7], n[8]) : new Group(id, n[0], n[1], n[2], n[3], n[4], n[5], fill, statue);
        }
    }
    private AABB box;
    private VoxelShape shape;

    public Part(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Block material) {
        this(minX, minY, minZ, maxX, maxY, maxZ, material, 0);
    }

    public Part(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Block material, int orient) {
        this(minX, minY, minZ, maxX, maxY, maxZ, material, orient, 0, 0, 0);
    }

    public Part(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Block material, int orient, int tx, int ty, int tz) {
        this(minX, minY, minZ, maxX, maxY, maxZ, material, orient, tx, ty, tz, null);
    }

    public Part(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Block material, int orient, int tx, int ty, int tz, Group group) {
        this(minX, minY, minZ, maxX, maxY, maxZ, material, orient, tx, ty, tz, group, 0);
    }

    public Part(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Block material, int orient, int tx, int ty, int tz, Group group, int inner) {
        this(minX, minY, minZ, maxX, maxY, maxZ, material, orient, tx, ty, tz, group, inner, null);
    }

    public Part(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Block material, int orient, int tx, int ty, int tz, Group group, int inner, long[] mask) {
        this(minX, minY, minZ, maxX, maxY, maxZ, material, orient, tx, ty, tz, group, inner, mask, 0);
    }

    public Part(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Block material, int orient, int tx, int ty, int tz, Group group, int inner, long[] mask, int sub) {
        this(minX, minY, minZ, maxX, maxY, maxZ, material, orient, tx, ty, tz, group, inner, mask, sub, false);
    }

    /**
     * A part of a prefab: in the block space of the prefab, which may be bigger than a block (parts joined with glue over several blocks), so it may lie anywhere
     * (but in one block space of the prefab when it has holes). Not for a block of the world: a prefab is cut into parts of blocks when it is put down.
     */
    public static Part prefab(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Block material, int orient, int tx, int ty, int tz, int inner, long[] mask) {
        return new Part(minX, minY, minZ, maxX, maxY, maxZ, material, orient, tx, ty, tz, null, inner, mask, 0, true);
    }

    private Part(int minX, int minY, int minZ, int maxX, int maxY, int maxZ, Block material, int orient, int tx, int ty, int tz, Group group, int inner, long[] mask, int sub, boolean anywhere) {
        int most = anywhere ? 16 * 64 : GRID, least = anywhere ? -16 * 64 : 0;
        this.minX = clamp(Math.min(minX, maxX - 1), least, most - 1);
        this.minY = clamp(Math.min(minY, maxY - 1), least, most - 1);
        this.minZ = clamp(Math.min(minZ, maxZ - 1), least, most - 1);
        this.maxX = clamp(Math.max(maxX, this.minX + 1), least + 1, most);
        this.maxY = clamp(Math.max(maxY, this.minY + 1), least + 1, most);
        this.maxZ = clamp(Math.max(maxZ, this.minZ + 1), least + 1, most);
        this.material = material;
        this.orient = orient < 0 || orient > 6 ? 0 : orient;
        this.tx = tx; this.ty = ty; this.tz = tz;
        this.group = group;
        this.inner = inner;
        this.mask = mask;
        this.sub = sub;
    }

    public Part withOrient(int orient) {
        return new Part(minX, minY, minZ, maxX, maxY, maxZ, material, orient, tx, ty, tz, group, inner, mask, sub);
    }

    public Part withGroup(Group group) {
        return new Part(minX, minY, minZ, maxX, maxY, maxZ, material, orient, tx, ty, tz, group, inner, mask, sub);
    }

    public Part withInner(int inner) {
        return new Part(minX, minY, minZ, maxX, maxY, maxZ, material, orient, tx, ty, tz, group, inner, mask, sub);
    }

    public Part withSub(int sub) {
        return new Part(minX, minY, minZ, maxX, maxY, maxZ, material, orient, tx, ty, tz, group, inner, mask, sub);
    }

    /** A box that lies across block spaces (one box, drawn and given back as one). */
    public boolean inBoxGroup() {
        return group != null && !group.fill() && !group.statue();
    }

    public Part withMask(long[] mask) {
        return new Part(minX, minY, minZ, maxX, maxY, maxZ, material, orient, tx, ty, tz, group, inner, mask, sub);
    }

    /** Whether the part holds the cell (x, y, z) of the block space. */
    public boolean has(int x, int y, int z) {
        if (x < minX || x >= maxX || y < minY || y >= maxY || z < minZ || z >= maxZ) return false;
        if (mask == null) return true;
        // a part of a prefab that is bigger than a block lies in one block space of the prefab, anywhere: its cells are counted from the corner of that space
        int bit = (x & 15) + 16 * ((y & 15) + 16 * (z & 15));
        return (mask[bit >> 6] >>> (bit & 63) & 1L) != 0;
    }

    /** A mask from the cells that are held. */
    public static long[] maskOf(boolean[] cells) {
        long[] mask = new long[64];
        for (int i = 0; i < 4096; i++) if (cells[i]) mask[i >> 6] |= 1L << (i & 63);
        return mask;
    }

    /** The mask as numbers for the data (128 of 32 bits), and back. */
    public static int[] pack(long[] mask) {
        int[] out = new int[128];
        for (int i = 0; i < 64; i++) { out[2 * i] = (int) mask[i]; out[2 * i + 1] = (int) (mask[i] >>> 32); }
        return out;
    }

    public static long[] unpack(int[] ints) {
        if (ints == null || ints.length != 128) return null;
        long[] out = new long[64];
        for (int i = 0; i < 64; i++) out[i] = (ints[2 * i] & 0xFFFFFFFFL) | ((long) ints[2 * i + 1] << 32);
        return out;
    }

    public Part withShift(int tx, int ty, int tz) {
        return new Part(minX, minY, minZ, maxX, maxY, maxZ, material, orient, tx, ty, tz, group, inner, mask, sub);
    }

    /** The axis of the part that the axis {@code i} of the block lies on (identity for no turn). */
    public static int[] perm(int orient) {
        return orient <= 0 ? MicroItemData.PERMUTATIONS[0] : MicroItemData.PERMUTATIONS[orient - 1];
    }

    private static int clamp(int v, int lo, int hi) {
        return Math.max(lo, Math.min(hi, v));
    }

    public static Part of(int[] min, int[] max, Block material) {
        return new Part(min[0], min[1], min[2], max[0], max[1], max[2], material);
    }

    public static Part of(int[] min, int[] max, Block material, int orient) {
        return new Part(min[0], min[1], min[2], max[0], max[1], max[2], material, orient);
    }

    public int min(int axis) { return axis == 0 ? minX : axis == 1 ? minY : minZ; }
    public int max(int axis) { return axis == 0 ? maxX : axis == 1 ? maxY : maxZ; }
    public int size(int axis) { return max(axis) - min(axis); }

    /** The same part in every way (the parts are compared by what they hold, not by identity). */
    public boolean sameAs(Part o) {
        return minX == o.minX && minY == o.minY && minZ == o.minZ && maxX == o.maxX && maxY == o.maxY && maxZ == o.maxZ && material == o.material && orient == o.orient
                && tx == o.tx && ty == o.ty && tz == o.tz && inner == o.inner && java.util.Objects.equals(group, o.group) && java.util.Arrays.equals(mask, o.mask) && sub == o.sub;
    }

    public AABB box() {
        if (box == null) box = new AABB(minX / 16.0, minY / 16.0, minZ / 16.0, maxX / 16.0, maxY / 16.0, maxZ / 16.0);
        return box;
    }

    public VoxelShape shape() {
        if (shape == null) {
            if (mask == null) {
                shape = Shapes.create(box());
            } else {
                // a part with holes is solid only where it holds cells: the outline and the ray (and the tools) follow it
                VoxelShape grid = GridShape.of(java.util.List.of(this));
                shape = grid != null ? grid : Shapes.create(box());
            }
        }
        return shape;
    }

    /** The volume in cubic sixteenths (a whole block is 4096). */
    public int volume() {
        if (mask == null) return size(0) * size(1) * size(2);
        int count = 0;
        for (long word : mask) count += Long.bitCount(word);
        return count;
    }

    /** Two parts of the same block space may not share volume (touching faces are fine). */
    public boolean overlaps(Part other) {
        if (!(minX < other.maxX && maxX > other.minX && minY < other.maxY && maxY > other.minY && minZ < other.maxZ && maxZ > other.minZ)) return false;
        if (mask == null && other.mask == null) return true;
        for (int x = Math.max(minX, other.minX); x < Math.min(maxX, other.maxX); x++)
            for (int y = Math.max(minY, other.minY); y < Math.min(maxY, other.maxY); y++)
                for (int z = Math.max(minZ, other.minZ); z < Math.min(maxZ, other.maxZ); z++) if (has(x, y, z) && other.has(x, y, z)) return true;
        return false;
    }
}
