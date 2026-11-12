package com.akicater.microblocks.client;

import com.akicater.microblocks.MicroSettings;

import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;

/** The click of a chisel or a drill, on the client: the block, the face and the cell that is looked at are sent to the server, at most a few times a second while the button is held. */
public final class MicroToolClient {
    private MicroToolClient() {}

    private static long last;

    public static void use(BlockPos pos, Direction face) {
        Minecraft mc = Minecraft.getInstance();
        long now = System.currentTimeMillis();
        if (mc.player == null || now - last < 220) return;
        last = now;
        HitResult ray = mc.hitResult;
        int cx = 8, cy = 8, cz = 8;
        if (ray instanceof BlockHitResult hit && hit.getBlockPos().equals(pos)) {
            double x = hit.getLocation().x - pos.getX() - face.getStepX() * 0.001, y = hit.getLocation().y - pos.getY() - face.getStepY() * 0.001, z = hit.getLocation().z - pos.getZ() - face.getStepZ() * 0.001;
            cx = Math.max(0, Math.min(15, (int) Math.floor(x * 16)));
            cy = Math.max(0, Math.min(15, (int) Math.floor(y * 16)));
            cz = Math.max(0, Math.min(15, (int) Math.floor(z * 16)));
        }
        send(pos, face, cx, cy, cz);
    }

    /** The numbers that the server is told (also used by the test, with a cell of its own). */
    public static void send(BlockPos pos, Direction face, int cx, int cy, int cz) {
        MicroNet.send(MicroSettings.KIND_TOOL_X, pos.getX());
        MicroNet.send(MicroSettings.KIND_TOOL_Y, pos.getY());
        MicroNet.send(MicroSettings.KIND_TOOL_Z, pos.getZ());
        MicroNet.send(MicroSettings.KIND_TOOL_GO, face.get3DDataValue() | cx << 3 | cy << 7 | cz << 11);
    }
}
