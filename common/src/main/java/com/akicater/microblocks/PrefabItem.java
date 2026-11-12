package com.akicater.microblocks;

import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.TooltipFlag;
#if MC_VER >= V1_21_5
import net.minecraft.world.item.component.TooltipDisplay;
#endif
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

/** The paper ("blueprint", made with a right click on a block of microblocks) and the prefab made from it on the table: the parts of one block space. */
public class PrefabItem extends Item {
    public final boolean prefab;

    public PrefabItem(boolean prefab, Properties properties) {
        super(properties);
        this.prefab = prefab;
    }

    @Override
    public Component getName(ItemStack stack) {
        return com.akicater.lib.Compat.translatable(prefab ? "item.microblocks.prefab.named" : "item.microblocks.blueprint.named", PrefabData.read(stack).size());
    }

    @Override
    public void appendHoverText(ItemStack stack, #if MC_VER >= V1_21 TooltipContext context #else Level context #endif, #if MC_VER >= V1_21_5 TooltipDisplay display, Consumer<Component> #else List<Component> #endif lines, TooltipFlag flag) {
        for (Map.Entry<Block, Integer> entry : PrefabData.volumes(PrefabData.read(stack)).entrySet()) {
            double blocks = entry.getValue() / (double) PrefabData.BLOCK_VOLUME;
            lines.#if MC_VER >= V1_21_5 accept #else add #endif(com.akicater.lib.Compat.literal(" ").append(entry.getKey().getName()).append(com.akicater.lib.Compat.literal(String.format(": %.3f", blocks)).append(com.akicater.lib.Compat.translatable("microblocks.blocks"))));
        }
    }

    /** The block space a prefab goes into when used on this face: the one next to it. */
    public static BlockPos target(BlockPos clicked, Direction face) {
        return clicked.relative(face);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        if (!prefab) return InteractionResult.PASS;                  // the paper is only used on the table
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.FAIL;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        if (!player.mayBuild()) return InteractionResult.FAIL;
        List<Part> parts = PrefabData.read(context.getItemInHand());
        // one piece, by the rules of the microblocks (free, snapping, inside a block space; across block spaces)
        MicroSettings.State settings = MicroSettings.of(player);
        MicroStatue.Plan plan = MicroStatue.plan(level, context.getClickedPos(), context.getClickedFace(), context.getClickLocation(), parts, settings);
        if (!MicroStatue.place(level, plan, settings.replace)) return InteractionResult.FAIL;
        if (!parts.isEmpty()) {
            var sound = parts.get(0).material.defaultBlockState().getSoundType();
            level.playSound(null, plan.pieces().get(0).pos(), sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
        }
        if (!player.getAbilities().instabuild) context.getItemInHand().shrink(1);
        return #if MC_VER >= V1_21_3 InteractionResult.SUCCESS_SERVER #else InteractionResult.SUCCESS #endif;
    }
}
