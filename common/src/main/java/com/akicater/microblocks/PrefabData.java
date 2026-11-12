package com.akicater.microblocks;

import com.akicater.lib.Compat;
import com.akicater.lib.Nbt;

import net.minecraft.core.BlockPos;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.nbt.ListTag;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** The parts of one block space saved in an item: the blueprint (paper) and the prefab made from it carry the same list. */
public final class PrefabData {
    private PrefabData() {}

    /** The volume of a whole block, in cubic sixteenths. */
    public static final int BLOCK_VOLUME = 16 * 16 * 16;

    private static int shift(CompoundTag entry, int axis) {
        int[] shift = Nbt.ints(entry, "shift");
        return shift != null && shift.length == 3 ? shift[axis] : 0;
    }

    public static List<Part> read(ItemStack stack) {
        List<Part> parts = new ArrayList<>();
        ListTag list = Nbt.compounds(Nbt.read(stack), "parts");
        for (int i = 0; i < list.size() && parts.size() < MicroBlockEntity.MAX_PARTS; i++) {
            CompoundTag entry = Nbt.compound(list, i);
            Block material = Compat.blockOf(Nbt.str(entry, "material"));
            int[] box = Nbt.ints(entry, "box");
            if (material != null && box != null && box.length == 6) parts.add(Part.prefab(box[0], box[1], box[2], box[3], box[4], box[5], material, Nbt.integer(entry, "orient", 0), shift(entry, 0), shift(entry, 1), shift(entry, 2), Nbt.integer(entry, "inner", 0), Part.unpack(Nbt.ints(entry, "mask"))));
        }
        return parts;
    }

    public static void write(ItemStack stack, List<Part> parts) {
        CompoundTag tag = new CompoundTag();
        ListTag list = new ListTag();
        for (Part part : parts) {
            CompoundTag entry = new CompoundTag();
            entry.putIntArray("box", new int[]{part.minX, part.minY, part.minZ, part.maxX, part.maxY, part.maxZ});
            entry.putString("material", Compat.blockId(part.material));
            entry.putInt("orient", part.orient);
            if (part.inner != 0) entry.putInt("inner", part.inner);
            if (part.mask != null) entry.putIntArray("mask", Part.pack(part.mask));
            if (part.tx != 0 || part.ty != 0 || part.tz != 0) entry.putIntArray("shift", new int[]{part.tx, part.ty, part.tz});      // the part of the texture that the part shows
            list.add(entry);
        }
        tag.put("parts", list);
        Nbt.write(stack, tag);
    }

    /** How much of each material the parts hold, in cubic sixteenths. */
    public static Map<Block, Integer> volumes(List<Part> parts) {
        Map<Block, Integer> volumes = new LinkedHashMap<>();
        for (Part part : parts) volumes.merge(part.material, part.volume(), Integer::sum);
        return volumes;
    }

    /** The volume of an ingredient (a whole block or a microblock) and its material; 0 for anything else. */
    public static int volumeOf(ItemStack stack) {
        if (stack.isEmpty()) return 0;
        if (stack.getItem() instanceof MicroItem) {
            int[] d = MicroItemData.dims(stack);
            return d[0] * d[1] * d[2];
        }
        if (stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem && MicroMaterials.isValid(blockItem.getBlock())) return BLOCK_VOLUME;
        return 0;
    }

    public static Block materialOf(ItemStack stack) {
        if (stack.getItem() instanceof MicroItem) return MicroItemData.material(stack);
        if (stack.getItem() instanceof net.minecraft.world.item.BlockItem blockItem) return blockItem.getBlock();
        return null;
    }

    /** Can the parts go into this block space (empty, or with microblocks that they do not overlap)? */
    public static boolean fits(Level level, BlockPos pos, List<Part> parts) {
        if (parts.isEmpty() || !level.isInWorldBounds(pos)) return false;
        BlockState state = level.getBlockState(pos);
        if (state.getBlock() instanceof MicroBlock) {
            if (!(level.getBlockEntity(pos) instanceof MicroBlockEntity be)) return false;
            if (be.parts().size() + parts.size() > MicroBlockEntity.MAX_PARTS) return false;
            for (Part part : parts) if (!be.canAdd(part)) return false;
            return true;
        }
        return Compat.replaceable(state);
    }

    /** Puts all the parts in, or none. */
    public static boolean place(Level level, BlockPos pos, List<Part> parts) {
        if (!fits(level, pos, parts)) return false;
        if (!(level.getBlockState(pos).getBlock() instanceof MicroBlock)) level.setBlock(pos, Microblocks.BLOCK.get().defaultBlockState(), Block.UPDATE_ALL);
        if (!(level.getBlockEntity(pos) instanceof MicroBlockEntity be)) return false;
        for (Part part : parts) be.add(part);
        return true;
    }
}
