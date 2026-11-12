package com.akicater.microblocks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.EmptyBlockGetter;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.level.block.state.BlockState;

import java.util.List;

public final class MicroMaterials {
    private MicroMaterials() {}

    /** A block that can be cut: a full cube without a block entity. */
    public static boolean isValid(Block block) {
        if (block == Blocks.AIR || block instanceof MicroBlock) return false;
        BlockState state = block.defaultBlockState();
        if (state.hasBlockEntity()) return false;
        return Block.isShapeFullBlock(state.getShape(EmptyBlockGetter.INSTANCE, BlockPos.ZERO));
    }

    /** The blocks of the creative tab and of the command suggestions. */
    public static List<Block> samples() {
        return List.of(Blocks.STONE, Blocks.COBBLESTONE, Blocks.STONE_BRICKS, Blocks.SMOOTH_STONE, Blocks.DEEPSLATE_BRICKS,
                Blocks.OAK_PLANKS, Blocks.SPRUCE_PLANKS, Blocks.BIRCH_PLANKS, Blocks.DARK_OAK_PLANKS, Blocks.BRICKS,
                Blocks.SANDSTONE, Blocks.QUARTZ_BLOCK, Blocks.IRON_BLOCK, Blocks.GOLD_BLOCK, Blocks.DIAMOND_BLOCK,
                Blocks.COAL_BLOCK, Blocks.EMERALD_BLOCK, Blocks.GLASS, Blocks.OBSIDIAN, Blocks.TERRACOTTA);
    }
}
