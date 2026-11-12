package com.akicater.microblocks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.block.BaseEntityBlock;
import net.minecraft.world.level.block.RenderShape;
import net.minecraft.world.level.block.entity.BlockEntity;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.shapes.CollisionContext;
import net.minecraft.world.phys.shapes.Shapes;
import net.minecraft.world.phys.shapes.VoxelShape;

/** The block that holds the microblocks of one block space. It draws nothing itself: the renderer of its block entity does. */
public class MicroBlock extends BaseEntityBlock implements com.akicater.lib.BreakPrediction {
    #if MC_VER >= V1_20_4 && MC_VER < V26_3
    public static final com.mojang.serialization.MapCodec<MicroBlock> CODEC = simpleCodec(MicroBlock::new);

    @Override
    #if MC_VER >= V1_21 protected #else public #endif com.mojang.serialization.MapCodec<? extends BaseEntityBlock> codec() {
        return CODEC;
    }
    #endif

    /**
     * The break on the client before the server answers: of several parts only the one that is looked at goes (the game would take the whole block, with the data of
     * all the others, and show it back when the server answers). The last part, and the pieces of a box across block spaces, are left to the game.
     */
    @Override
    public boolean predictBreak(net.minecraft.world.level.Level level, BlockPos pos) {
        return com.akicater.microblocks.client.MicroPredict.breakPart(level, pos);      // client code: only touched on the client
    }

    /**
     * The game breaks a block with a sound and particles of the block itself: here that would be the holder (no texture, the whole block). The break of a microblock
     * is made in the break event (see {@link MicroBreaking}), so nothing is played here.
     */
    @Override
    #if MC_VER >= V1_20_4 public net.minecraft.world.level.block.state.BlockState #else public void #endif playerWillDestroy(net.minecraft.world.level.Level level, BlockPos pos, BlockState state, net.minecraft.world.entity.player.Player player) {
        #if MC_VER >= V1_20_4
        return state;
        #endif
    }

    /** The middle click takes the part that is looked at (as an item with its size, turn and texture), not the holder block. */
    #if MC_VER <= V1_20_1
    @Override
    public net.minecraft.world.item.ItemStack getCloneItemStack(net.minecraft.world.level.BlockGetter level, BlockPos pos, BlockState state) {
        return MicroPick.stack(level, pos);
    }
    #elif MC_VER <= V1_21_3
    @Override
    public net.minecraft.world.item.ItemStack getCloneItemStack(net.minecraft.world.level.LevelReader level, BlockPos pos, BlockState state) {
        return MicroPick.stack(level, pos);
    }
    #else
    @Override
    protected net.minecraft.world.item.ItemStack getCloneItemStack(net.minecraft.world.level.LevelReader level, BlockPos pos, BlockState state, boolean includeData) {
        return MicroPick.stack(level, pos);
    }
    #endif

    public MicroBlock(Properties properties) {
        super(properties);
    }

    @Override
    public BlockEntity newBlockEntity(BlockPos pos, BlockState state) {
        return new MicroBlockEntity(pos, state);
    }

    /** Set by a loader whose chunk meshes draw the parts through a model (Fabric); without it the renderer of the block entity does. */
    public static volatile boolean bakedModel;

    @Override
    #if MC_VER >= V1_21 protected #else public #endif RenderShape getRenderShape(BlockState state) {
        return bakedModel ? RenderShape.MODEL : RenderShape.INVISIBLE;
    }

    /** The neighbours' faces next to the block are always drawn: the parts do not fill the block space. */
    @Override
    #if MC_VER >= V1_21 protected #else public #endif VoxelShape getOcclusionShape(BlockState state#if MC_VER <= V1_21_1 , BlockGetter level, BlockPos pos#endif) {
        return Shapes.empty();
    }

    private static VoxelShape shapeOf(BlockGetter level, BlockPos pos) {
        return level.getBlockEntity(pos) instanceof MicroBlockEntity micro ? micro.shape() : Shapes.block();
    }

    @Override
    #if MC_VER >= V1_21 protected #else public #endif VoxelShape getShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return shapeOf(level, pos);
    }

    @Override
    #if MC_VER >= V1_21 protected #else public #endif VoxelShape getCollisionShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return level.getBlockEntity(pos) instanceof MicroBlockEntity micro ? micro.collisionShape() : Shapes.block();
    }

    @Override
    #if MC_VER >= V1_21 protected #else public #endif VoxelShape getVisualShape(BlockState state, BlockGetter level, BlockPos pos, CollisionContext context) {
        return Shapes.empty();
    }

    @Override
    #if MC_VER >= V1_21 protected #else public #endif boolean propagatesSkylightDown(BlockState state#if MC_VER <= V1_21_1 , BlockGetter level, BlockPos pos#endif) {
        return true;
    }

    @Override
    #if MC_VER >= V1_21 protected #else public #endif float getShadeBrightness(BlockState state, BlockGetter level, BlockPos pos) {
        return 1.0F;
    }
}
