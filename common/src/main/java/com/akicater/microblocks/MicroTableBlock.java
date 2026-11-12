package com.akicater.microblocks;

import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.SimpleMenuProvider;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;

/** The two tables: the iron one where microblocks are cut to measure, the blue one where prefabs are made from blueprints. */
public class MicroTableBlock extends Block {
    private final int kind;

    /** {@code kind}: {@link MicroTableMenu#MODE_CUT} for the table that cuts, {@link MicroTableMenu#MODE_PREFAB} for the table of blueprints. */
    public MicroTableBlock(Properties properties, int kind) {
        super(properties);
        this.kind = kind;
    }

    @Override
    #if MC_VER >= V1_21
    protected InteractionResult useWithoutItem(BlockState state, Level level, BlockPos pos, Player player, BlockHitResult hit) {
    #else
    public InteractionResult use(BlockState state, Level level, BlockPos pos, Player player, net.minecraft.world.InteractionHand hand, BlockHitResult hit) {
    #endif
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        player.openMenu(new SimpleMenuProvider((id, inventory, p) -> new MicroTableMenu(id, inventory, ContainerLevelAccess.create(level, pos), kind),
                com.akicater.lib.Compat.translatable(kind == MicroTableMenu.MODE_PREFAB ? "block.microblocks.blueprint_table" : "block.microblocks.table")));
        return #if MC_VER >= V1_21_3 InteractionResult.SUCCESS_SERVER #else InteractionResult.SUCCESS #endif;
    }
}
