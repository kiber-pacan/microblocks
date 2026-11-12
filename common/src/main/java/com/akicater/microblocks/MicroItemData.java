package com.akicater.microblocks;

import com.akicater.lib.Compat;
import com.akicater.lib.Nbt;

import net.minecraft.core.Direction;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.Arrays;

/**
 * What a microblock item holds: the material block, the three sizes in sixteenths (a, b, c) and how they are laid on the axes.
 * Rotation 0 is automatic: the thinnest side goes against the surface that was clicked, the longest along the way the player looks (a plane lies flat,
 * a stick lies along the player's view); 1 to 6 are the six ways to lay a, b, c on x, y, z, chosen by using the item in the air.
 */
public final class MicroItemData {
    private MicroItemData() {}

    public static final int[][] PERMUTATIONS = {{0, 1, 2}, {0, 2, 1}, {1, 0, 2}, {1, 2, 0}, {2, 0, 1}, {2, 1, 0}};

    public static ItemStack make(Block material, int a, int b, int c) {
        return make(material, a, b, c, 0);
    }

    /** With a turn: 0 is automatic, 1 to 6 lay a, b, c on the axes of the world as {@link #PERMUTATIONS} say (and keep the texture with the box). */
    public static ItemStack make(Block material, int a, int b, int c, int rotation) {
        return make(material, a, b, c, rotation, null);
    }

    /**
     * {@code off}: where in the block it was cut from this piece starts (in sixteenths, along its a, b, c): its texture is that part of the texture of the block, so a block
     * with a texture that is not the same on both sides (a log, a furnace, a bookshelf) shows the right half on each piece. Null: the texture is that of the place where it is put.
     */
    public static ItemStack make(Block material, int a, int b, int c, int rotation, int[] off) {
        return make(material, a, b, c, rotation, off, 0);
    }

    /** {@code inner}: the faces that are the inside of the block (bits of the numbers of the directions, in the axes of the item); they show the underside texture. */
    public static ItemStack make(Block material, int a, int b, int c, int rotation, int[] off, int inner) {
        ItemStack stack = new ItemStack(Microblocks.MICRO.get());
        CompoundTag tag = new CompoundTag();
        tag.putString("material", Compat.blockId(material));
        tag.putInt("a", clamp(a));
        tag.putInt("b", clamp(b));
        tag.putInt("c", clamp(c));
        if (rotation > 0) tag.putInt("rot", rotation);
        if (off != null) tag.putIntArray("off", new int[]{off[0], off[1], off[2]});
        if (inner != 0) tag.putInt("inner", inner);
        Nbt.write(stack, tag);
        return stack;
    }

    /** The item of a part: its sizes as they are on the block (the way its texture lies), turned as the part is. */
    public static ItemStack make(Part part) {
        return make(part, part.tx + part.minX, part.ty + part.minY, part.tz + part.minZ);
    }

    /** The item of a part whose texture starts at ({@code ox}, {@code oy}, {@code oz}) of the block it was cut from (in the axes of the world). */
    public static ItemStack make(Part part, int ox, int oy, int oz) {
        int[] world = {ox, oy, oz};
        if (part.orient <= 0) return make(part.material, part.size(0), part.size(1), part.size(2), 0, world, part.inner);
        int[] perm = Part.perm(part.orient);
        return make(part.material, part.size(perm[0]), part.size(perm[1]), part.size(perm[2]), part.orient, new int[]{world[perm[0]], world[perm[1]], world[perm[2]]}, part.inner);
    }

    public static int inner(ItemStack stack) {
        return Nbt.integer(tag(stack), "inner", 0);
    }

    /**
     * What a volume of a material is given back as: planes of 16 x 16 x 1, sticks of 16 x 1 x 1 and cubes of 1/16, in stacks of 64: a few stacks instead of one item for every
     * box that a battered block is made of (a block that was worked a lot would drop hundreds of items, and every one of them is an entity that is drawn).
     */
    public static java.util.List<ItemStack> pile(Block material, int cells) {
        java.util.List<ItemStack> out = new java.util.ArrayList<>();
        int[][] kinds = {{16, 16, 1, 256}, {16, 1, 1, 16}, {1, 1, 1, 1}};
        for (int[] kind : kinds) {
            int count = cells / kind[3];
            cells -= count * kind[3];
            while (count > 0) {
                ItemStack stack = make(material, kind[0], kind[1], kind[2]);
                stack.setCount(Math.min(64, count));
                count -= stack.getCount();
                out.add(stack);
            }
        }
        return out;
    }

    /** Where in its block this piece starts (along a, b, c), null for an item that was not cut from a block. */
    public static int[] offset(ItemStack stack) {
        int[] off = Nbt.ints(tag(stack), "off");
        return off != null && off.length == 3 ? off : null;
    }

    /** The offset of the texture on the axes of the world, for the turn the part will have (0 is the first of the ways to lay the sizes). */
    public static int[] worldOffset(int[] off, int orient) {
        int[] perm = Part.perm(orient);
        int[] world = new int[3];
        for (int i = 0; i < 3; i++) world[perm[i]] = off[i];
        return world;
    }

    /** Which of the turns (1 to 6) lays the sizes {@code dims} on the sizes {@code world}; 1 when that is not unique or not possible. */
    public static int orientation(int[] dims, int[] world) {
        for (int i = 0; i < 6; i++) {
            int[] perm = PERMUTATIONS[i];
            if (world[perm[0]] == dims[0] && world[perm[1]] == dims[1] && world[perm[2]] == dims[2]) return i + 1;
        }
        return 1;
    }

    private static int clamp(int v) {
        return Math.max(1, Math.min(16, v));
    }

    private static CompoundTag tag(ItemStack stack) {
        return Nbt.read(stack);
    }

    public static Block material(ItemStack stack) {
        Block block = Compat.blockOf(Nbt.str(tag(stack), "material"));
        return block == null ? Blocks.STONE : block;
    }

    public static int[] dims(ItemStack stack) {
        CompoundTag t = tag(stack);
        return new int[]{clamp(Nbt.integer(t, "a", 16)), clamp(Nbt.integer(t, "b", 16)), clamp(Nbt.integer(t, "c", 4))};
    }

    public static int rotation(ItemStack stack) {
        return Math.max(0, Math.min(6, Nbt.integer(tag(stack), "rot", 0)));
    }

    public static void setRotation(ItemStack stack, int rotation) {
        CompoundTag t = tag(stack);
        t.putInt("rot", rotation);
        Nbt.write(stack, t);
    }

    public static int[] worldDims(int[] dims, int rotation, Direction normal, Direction look) {
        return worldDims(dims, rotation, normal, look, null);
    }

    /**
     * The sizes along x, y and z when the item is put against {@code normal} by a player looking along {@code look}; {@code hit} is where the surface was
     * clicked (0..1 on each axis of the block space), null if not known. Automatic orientation (rotation 0): the thin side goes against the surface; the long
     * side runs along the nearest edge of the face when the click is near one (like a directional block that turns to the wall), otherwise along the way the player
     * looks on a floor or ceiling and horizontally on a wall.
     */
    public static int[] worldDims(int[] dims, int rotation, Direction normal, Direction look, double[] hit) {
        return worldDims(dims, rotation, normal, look, hit, 0);
    }

    /** The kinds of turn for a box whose turn was not chosen: 0 automatic (see below), 1 as it was cut (the sizes on x, y, z), 2 upright (the longest side up), 3 flat (the thinnest side up). */
    public static final int ROTATION_MODES = 4;

    public static int[] worldDims(int[] dims, int rotation, Direction normal, Direction look, double[] hit, int mode) {
        int[] world = new int[3];
        if (rotation == 0 && mode == 1) return dims.clone();
        if (rotation == 0 && (mode == 2 || mode == 3)) {
            int[] sorted = dims.clone();
            Arrays.sort(sorted);                                   // thin, middle, long
            int up = mode == 2 ? sorted[2] : sorted[0];
            int towards = mode == 2 ? sorted[0] : sorted[2];       // upright: the thin side faces the player; flat: the long side runs along the view
            int rest = sorted[1];
            int lookAxis = look.getAxis().isHorizontal() ? look.getAxis().ordinal() : 0;
            int other = lookAxis == 0 ? 2 : 0;
            world[1] = up;
            world[lookAxis] = towards;
            world[other] = rest;
            return world;
        }
        if (rotation > 0) {
            int[] perm = PERMUTATIONS[rotation - 1];
            for (int i = 0; i < 3; i++) world[perm[i]] = dims[i];
            return world;
        }
        int[] sorted = dims.clone();
        Arrays.sort(sorted);                                       // thin, middle, long
        int n = normal.getAxis().ordinal();
        int u1 = n == 0 ? 1 : 0, u2 = n == 2 ? 1 : 2;              // the two axes along the surface
        int lookAxis = look.getAxis().isHorizontal() ? look.getAxis().ordinal() : 0;
        int u, v;                                                  // u is the axis the long side takes
        if (n == 1) {                                              // on a floor or a ceiling: along the view
            u = lookAxis;
            v = lookAxis == 0 ? 2 : 0;
        } else {                                                   // on a wall: along the wall, horizontally
            u = n == 0 ? 2 : 0;
            v = 1;
        }
        if (hit != null && sorted[2] > sorted[1]) {
            double e1 = Math.min(hit[u1], 1 - hit[u1]), e2 = Math.min(hit[u2], 1 - hit[u2]);
            if (Math.min(e1, e2) < 0.25) {                         // near an edge: parallel to it
                u = e1 < e2 ? u2 : u1;
                v = u == u1 ? u2 : u1;
            }
        }
        world[n] = sorted[0];
        world[u] = sorted[2];
        world[v] = sorted[1];
        return world;
    }

    public static String kind(int[] dims) {
        int[] s = dims.clone();
        Arrays.sort(s);
        if (s[0] == s[2]) return s[0] == 16 ? "block" : "cube";
        if (s[2] == 16 && s[0] == s[1]) return "stick";
        if (s[1] == s[2] && s[2] == 16) return "plane";
        return "box";
    }

    /** The thickness of a standard shape as a fraction ("1/4"), or the three sizes ("6x3x12") of a box cut to measure. */
    public static Component description(int[] dims) {
        String kind = kind(dims);
        int[] s = dims.clone();
        Arrays.sort(s);
        if (kind.equals("block")) return com.akicater.lib.Compat.empty();
        if (!kind.equals("box") && fraction(s[0]) != null) return com.akicater.lib.Compat.literal(fraction(s[0]));
        return com.akicater.lib.Compat.literal(dims[0] + "\u00d7" + dims[1] + "\u00d7" + dims[2]);
    }

    /** The sizes of the standard shapes: 1/16, 1/8, 1/4 and 1/2 of a block. */
    public static final int[] STANDARD_SIZES = {1, 2, 4, 8};

    private static String fraction(int sixteenths) {
        return switch (sixteenths) {
            case 1 -> "1/16";
            case 2 -> "1/8";
            case 4 -> "1/4";
            case 8 -> "1/2";
            default -> null;
        };
    }

    /** The sizes (a, b, c) of a standard shape of a thickness: a plane is 16 x 16 x t, a stick 16 x t x t, a cube t x t x t. */
    public static int[] standard(String kind, int thickness) {
        return switch (kind) {
            case "plane" -> new int[]{16, 16, thickness};
            case "stick" -> new int[]{16, thickness, thickness};
            default -> new int[]{thickness, thickness, thickness};
        };
    }
}
