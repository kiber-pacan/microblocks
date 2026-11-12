package com.akicater.microblocks;

import net.minecraft.core.BlockPos;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.BlockGetter;
import net.minecraft.world.level.Level;

import java.util.ArrayList;

/**
 * What the middle click takes from a block of microblocks: the whole block as a ready prefab, and with Shift only the part that is looked at (a box that lies across
 * block spaces, whole). The player is the nearest one: the game does not say who clicked.
 */
public final class MicroPick {
    private MicroPick() {}

    public static ItemStack stack(BlockGetter getter, BlockPos pos) {
        if (!(getter instanceof Level level) || !(level.getBlockEntity(pos) instanceof MicroBlockEntity micro) || micro.isEmpty()) return ItemStack.EMPTY;
        Player player = level.getNearestPlayer(pos.getX() + 0.5, pos.getY() + 0.5, pos.getZ() + 0.5, 12.0, false);
        if (player == null) return ItemStack.EMPTY;
        if (player.isShiftKeyDown()) {
            Part part = MicroBreaking.hitPart(player, pos, micro);
            return part == null ? ItemStack.EMPTY : MicroBreaking.itemOf(level, part, pos);
        }
        ItemStack prefab = new ItemStack(Microblocks.PREFAB.get());
        PrefabData.write(prefab, new ArrayList<>(micro.parts()));
        return prefab;
    }
}
