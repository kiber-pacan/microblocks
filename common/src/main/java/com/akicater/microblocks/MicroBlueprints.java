package com.akicater.microblocks;

import com.akicater.lib.Compat;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.InteractionEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;

/** A right click with a sheet of paper on a block of microblocks writes down what is in it: the sheet becomes a blueprint. */
public final class MicroBlueprints {
    private MicroBlueprints() {}

    // what a handler of a right click answers: older versions of Architectury take the result of the game, newer ones its own
    #if MC_VER >= V26_2 || MC_VER <= V1_21_1
    private static final EventResult PASS = EventResult.pass(), TAKEN = EventResult.interruptTrue(), REFUSED = EventResult.interruptFalse();
    #else
    private static final net.minecraft.world.InteractionResult PASS = net.minecraft.world.InteractionResult.PASS, TAKEN = net.minecraft.world.InteractionResult.SUCCESS, REFUSED = net.minecraft.world.InteractionResult.FAIL;
    #endif

    public static void register() {
        InteractionEvent.RIGHT_CLICK_BLOCK.register((player, hand, pos, face) -> {
            if (!player.getItemInHand(hand).is(Items.PAPER)) return PASS;
            if (!(Compat.level(player).getBlockState(pos).getBlock() instanceof MicroBlock)) return PASS;
            if (!(player instanceof ServerPlayer)) return TAKEN;                              // the client lets the server do it
            return create(player, hand, pos) ? TAKEN : REFUSED;
        });
    }

    /** Makes a blueprint of the microblock block at pos from the paper in the hand (the paper is used up unless creative). */
    public static boolean create(Player player, InteractionHand hand, BlockPos pos) {
        if (!(Compat.level(player).getBlockEntity(pos) instanceof MicroBlockEntity be) || be.isEmpty()) return false;
        ItemStack blueprint = new ItemStack(Microblocks.BLUEPRINT.get());
        PrefabData.write(blueprint, be.parts());
        if (!player.getAbilities().instabuild) player.getItemInHand(hand).shrink(1);
        if (!player.getInventory().add(blueprint)) Block.popResource(Compat.level(player), player.blockPosition(), blueprint);
        Compat.overlay(player, com.akicater.lib.Compat.translatable("microblocks.blueprint.made", be.parts().size()));
        return true;
    }
}
