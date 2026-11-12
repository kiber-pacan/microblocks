package com.akicater.microblocks;

import com.akicater.lib.Compat;

import com.mojang.brigadier.arguments.IntegerArgumentType;
import dev.architectury.event.events.common.CommandRegistrationEvent;
import net.minecraft.commands.CommandSourceStack;
import net.minecraft.commands.Commands;
import net.minecraft.commands.SharedSuggestionProvider;
#if MC_VER >= V1_21_11
#if MC_VER >= V1_21_11
import net.minecraft.commands.arguments.IdentifierArgument;
#else
#endif
#else
import net.minecraft.commands.arguments.ResourceLocationArgument;
#endif
import net.minecraft.network.chat.Component;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;


/** /microblock give <block> <a> <b> <c> [count]  (the three sizes in sixteenths of a block): a microblock for testing and for creative worlds. */
public final class MicroCommand {
    private MicroCommand() {}

    public static void register() {
        CommandRegistrationEvent.EVENT.register(#if MC_VER >= V1_19_2 (dispatcher, registry, selection) #else (dispatcher, selection) #endif -> dispatcher.register(
                Commands.literal("microblock").requires(#if MC_VER >= V1_21_11 Commands.hasPermission(Commands.LEVEL_GAMEMASTERS) #else source -> source.hasPermission(2) #endif)
                        .then(Commands.literal("give")
                                .then(Commands.argument("block", #if MC_VER >= V1_21_11 IdentifierArgument #else ResourceLocationArgument #endif.id())
                                        .suggests((context, builder) -> SharedSuggestionProvider.suggest(
                                                MicroMaterials.samples().stream().map(Compat::blockId), builder))
                                        .then(Commands.argument("a", IntegerArgumentType.integer(1, 16))
                                                .then(Commands.argument("b", IntegerArgumentType.integer(1, 16))
                                                        .then(Commands.argument("c", IntegerArgumentType.integer(1, 16))
                                                                .executes(context -> give(context.getSource(), #if MC_VER >= V1_21_11 IdentifierArgument #else ResourceLocationArgument #endif.getId(context, "block").toString(),
                                                                        IntegerArgumentType.getInteger(context, "a"), IntegerArgumentType.getInteger(context, "b"),
                                                                        IntegerArgumentType.getInteger(context, "c"), 1))
                                                                .then(Commands.argument("count", IntegerArgumentType.integer(1, 64))
                                                                        .executes(context -> give(context.getSource(), #if MC_VER >= V1_21_11 IdentifierArgument #else ResourceLocationArgument #endif.getId(context, "block").toString(),
                                                                                IntegerArgumentType.getInteger(context, "a"), IntegerArgumentType.getInteger(context, "b"),
                                                                                IntegerArgumentType.getInteger(context, "c"), IntegerArgumentType.getInteger(context, "count")))))))))));
    }

    private static int give(CommandSourceStack source, String blockId, int a, int b, int c, int count) {
        ServerPlayer player = source.getEntity() instanceof ServerPlayer p ? p : null;
        Block block = Compat.blockOf(blockId);
        if (player == null || block == null || !MicroMaterials.isValid(block)) {
            source.sendFailure(com.akicater.lib.Compat.literal("Not a block that can be cut: " + blockId));
            return 0;
        }
        ItemStack stack = MicroItemData.make(block, a, b, c);
        stack.setCount(count);
        if (!player.getInventory().add(stack)) Block.popResource(Compat.level(player), player.blockPosition(), stack);
        return count;
    }
}
