package com.akicater.microblocks.client;

import com.akicater.microblocks.MicroBlockEntity;
import com.akicater.microblocks.MicroBreaking;
import com.akicater.microblocks.Part;

import net.minecraft.client.Minecraft;
import net.minecraft.client.player.LocalPlayer;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.Level;

/** What the client does at once when the player breaks a part of a block with several (the server answers a moment later and its data replaces this). */
public final class MicroPredict {
    private MicroPredict() {}

    public static boolean breakPart(Level level, BlockPos pos) {
        if (!(level.getBlockEntity(pos) instanceof MicroBlockEntity micro) || micro.parts().size() < 2) return false;
        LocalPlayer player = Minecraft.getInstance().player;
        if (player == null) return false;
        Part part = MicroBreaking.hitPart(player, pos, micro);
        if (part == null || part.group != null) return false;
        micro.remove(part);
        return true;
    }
}
