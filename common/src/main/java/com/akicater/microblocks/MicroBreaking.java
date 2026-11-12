package com.akicater.microblocks;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.BlockEvent;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.Vec3;
import com.akicater.lib.Compat;
import net.minecraft.core.BlockPos;
import net.minecraft.world.level.block.state.BlockState;

import java.util.Optional;

/** Breaking a microblock block takes away the part the player looks at, one at a time. */
public final class MicroBreaking {
    private MicroBreaking() {}

    /** A cancelled break (some parts stay) leaves the client that predicted the break with an empty block: the data of the block is sent again at the end of the same tick, after the answer of the server. */
    private record Resync(net.minecraft.server.level.ServerPlayer player, BlockPos pos, int[] ticks) {}
    private static final java.util.List<Resync> RESYNC = new java.util.concurrent.CopyOnWriteArrayList<>();

    public static void register() {
        dev.architectury.event.events.common.TickEvent.SERVER_POST.register(server -> {
            for (Resync r : RESYNC) {
                if (--r.ticks()[0] > 0) continue;
                RESYNC.remove(r);
                // straight to the player who broke it, in the same tick, right behind the answer of the server: the client has the data when it puts the block back
                if (Compat.level(r.player()).getBlockEntity(r.pos()) instanceof MicroBlockEntity be && !be.isEmpty()) r.player().connection.send(be.getUpdatePacket());
            }
        });
        BlockEvent.BREAK.register((level, pos, state, player#if MC_VER < V26_2 , xp#endif) -> {
            long started = MicroTiming.start();
            try {
                return breakIt(level, pos, state, player);
            } finally {
                MicroTiming.end("the break of a block at " + pos, started);
            }
        });
    }

    private static EventResult breakIt(net.minecraft.world.level.Level level, BlockPos pos, BlockState state, Player player) {
        {
            if (!(state.getBlock() instanceof MicroBlock) || !(level.getBlockEntity(pos) instanceof MicroBlockEntity micro)) return EventResult.pass();
            Part part = hitPart(player, pos, micro);
            if (part == null) return EventResult.pass();

            // the break is made here whole (the game would play the sound of the holder block and break the full block into particles): the sound is that of the
            // material of the part, the particles fly out of the bounds of the part, and the holder block goes only with its last part
            if (part.group != null) {
                breakGroup(level, player, part, pos);
            } else {
                effects(level, pos, part, true);
                if (!player.getAbilities().instabuild) giveBack(level, pos, part);
                micro.remove(part);
            }
            if (level.getBlockEntity(pos) instanceof MicroBlockEntity left && !left.isEmpty()) {
                resync(player, pos);
            } else if (level.getBlockState(pos).getBlock() instanceof MicroBlock) {
                level.setBlock(pos, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            }
            return EventResult.interruptFalse();
        }
    }

    /** The sound of a part breaking (that of its material) and the particles of its material, thrown from inside its box (the server sends them to everyone near). */
    public static void effects(net.minecraft.world.level.Level level, BlockPos pos, Part part, boolean sound) {
        effects(level, pos, part, sound, 48);
    }

    public static void effects(net.minecraft.world.level.Level level, BlockPos pos, Part part, boolean sound, int most) {
        if (!(level instanceof net.minecraft.server.level.ServerLevel server)) return;
        net.minecraft.world.level.block.state.BlockState material = part.material.defaultBlockState();
        if (sound) {
            net.minecraft.world.level.block.SoundType type = material.getSoundType();
            server.playSound(null, pos, type.getBreakSound(), net.minecraft.sounds.SoundSource.BLOCKS, (type.getVolume() + 1.0F) / 2.0F, type.getPitch() * 0.8F);
        }
        java.util.Random random = java.util.concurrent.ThreadLocalRandom.current();
        net.minecraft.core.particles.BlockParticleOption option = new net.minecraft.core.particles.BlockParticleOption(net.minecraft.core.particles.ParticleTypes.BLOCK, material);
        double cx = pos.getX() + (part.minX + part.maxX) / 32.0, cy = pos.getY() + (part.minY + part.maxY) / 32.0, cz = pos.getZ() + (part.minZ + part.maxZ) / 32.0;
        int count = Math.max(2, Math.min(most, part.volume() / 24));
        for (int i = 0; i < count; i++) {
            double px = part.minX + random.nextDouble() * (part.maxX - part.minX), py = part.minY + random.nextDouble() * (part.maxY - part.minY), pz = part.minZ + random.nextDouble() * (part.maxZ - part.minZ);
            for (int tries = 0; part.mask != null && !part.has((int) px, (int) py, (int) pz) && tries < 24; tries++) {      // a part with holes: from a cell that it holds
                px = part.minX + random.nextDouble() * (part.maxX - part.minX); py = part.minY + random.nextDouble() * (part.maxY - part.minY); pz = part.minZ + random.nextDouble() * (part.maxZ - part.minZ);
            }
            double x = pos.getX() + px / 16.0, y = pos.getY() + py / 16.0, z = pos.getZ() + pz / 16.0;
            double dx = x - cx, dy = y - cy, dz = z - cz, length = Math.max(0.05, Math.sqrt(dx * dx + dy * dy + dz * dz));
            // out from the middle of the part, a little up; count 0 means that the three numbers are the velocity
            server.sendParticles(option, x, y, z, 0, dx / length * 0.6, dy / length * 0.6 + 0.2, dz / length * 0.6, 0.25);
        }
    }

    /** The player of the break event is a server player on some versions and a plain player on others: asked as an object, so that it compiles on both. */
    private static void resync(Object player, BlockPos pos) {
        if (player instanceof net.minecraft.server.level.ServerPlayer sp) RESYNC.add(new Resync(sp, pos, new int[]{1}));
    }

    /** A box that lies across block spaces: every piece of it goes, and one item (the whole box) is given back. */
    private static void breakGroup(net.minecraft.world.level.Level level, Player player, Part part, BlockPos at) {
        Part.Group g = part.group;
        ItemStack statue = g.statue() ? MicroStatue.item(level, g) : ItemStack.EMPTY;      // made before the pieces go
        for (int bx = Math.floorDiv(g.minX(), 16); bx <= Math.floorDiv(g.maxX() - 1, 16); bx++) {
            for (int by = Math.floorDiv(g.minY(), 16); by <= Math.floorDiv(g.maxY() - 1, 16); by++) {
                for (int bz = Math.floorDiv(g.minZ(), 16); bz <= Math.floorDiv(g.maxZ() - 1, 16); bz++) {
                    BlockPos p = new BlockPos(bx, by, bz);
                    if (!(level.getBlockEntity(p) instanceof MicroBlockEntity be)) continue;
                    for (Part other : new java.util.ArrayList<>(be.parts())) if (other.group != null && other.group.id() == g.id()) {
                        effects(level, p, other, other == part, g.statue() ? 6 : 48);
                        if (g.fill() && !player.getAbilities().instabuild) giveBack(level, p, other);
                        be.remove(other);
                    }
                    if (be.isEmpty() && level.getBlockState(p).getBlock() instanceof MicroBlock) level.setBlock(p, net.minecraft.world.level.block.Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
                }
            }
        }
        if (g.statue()) {
            if (!player.getAbilities().instabuild) Block.popResource(level, player.blockPosition(), statue);      // the prefab, whole
            return;
        }
        if (g.fill()) return;                      // the pieces were given back one by one above
        if (!player.getAbilities().instabuild) {
            int[] world = {g.maxX() - g.minX(), g.maxY() - g.minY(), g.maxZ() - g.minZ()};
            int orient = part.orient > 0 ? part.orient : 1;
            int[] perm = Part.perm(orient);
            // where the texture of the whole box starts: the shift of this piece, from the corner of the box (in the space of this piece)
            int[] start = {part.tx + g.minX() - at.getX() * 16, part.ty + g.minY() - at.getY() * 16, part.tz + g.minZ() - at.getZ() * 16};
            ItemStack drop = MicroItemData.make(part.material, world[perm[0]], world[perm[1]], world[perm[2]], orient, new int[]{start[perm[0]], start[perm[1]], start[perm[2]]}, part.inner);
            Block.popResource(level, player.blockPosition(), drop);
        }
    }

    /** A part given back: the part itself when it is a whole box, a few stacks of planes, sticks and cubes of its volume when it has holes (not an item for every box of it). */
    public static void giveBack(net.minecraft.world.level.Level level, BlockPos pos, Part part) {
        if (part.mask == null) {
            Block.popResource(level, pos, MicroItemData.make(part));
            return;
        }
        for (ItemStack stack : MicroItemData.pile(part.material, part.volume())) Block.popResource(level, pos, stack);
    }

    /** Where the ray first meets a cell that the part holds, starting at the point where it enters the box of the part (empty if it leaves the box first). */
    private static Optional<Vec3> marched(Part part, Vec3 entry, Vec3 from, Vec3 to) {
        Vec3 dir = to.subtract(from).normalize();
        for (double t = 0; t < 2.0; t += 1.0 / 64) {
            Vec3 p = entry.add(dir.scale(t + 1.0E-4));
            int x = (int) Math.floor(p.x * 16), y = (int) Math.floor(p.y * 16), z = (int) Math.floor(p.z * 16);
            if (x < part.minX || x >= part.maxX || y < part.minY || y >= part.maxY || z < part.minZ || z >= part.maxZ) return Optional.empty();
            if (part.has(x, y, z)) return Optional.of(p);
        }
        return Optional.empty();
    }

    /** The item of a part: the whole box for a piece of a box that lies across block spaces, the part itself for any other. */
    public static ItemStack itemOf(net.minecraft.world.level.Level level, Part part, BlockPos at) {
        Part.Group g = part.group;
        if (g != null && g.statue()) return MicroStatue.item(level, g);
        if (g == null || g.fill()) return MicroItemData.make(part);
        int[] world = {g.maxX() - g.minX(), g.maxY() - g.minY(), g.maxZ() - g.minZ()};
        int orient = part.orient > 0 ? part.orient : 1;
        int[] perm = Part.perm(orient);
        int[] start = {part.tx + g.minX() - at.getX() * 16, part.ty + g.minY() - at.getY() * 16, part.tz + g.minZ() - at.getZ() * 16};
        return MicroItemData.make(part.material, world[perm[0]], world[perm[1]], world[perm[2]], orient, new int[]{start[perm[0]], start[perm[1]], start[perm[2]]}, part.inner);
    }

    /** The part that the player's view ray enters first. */
    public static Part hitPart(Player player, BlockPos pos, MicroBlockEntity micro) {
        Vec3 from = player.getEyePosition();
        Vec3 to = from.add(player.getLookAngle().scale(8.0)).subtract(pos.getX(), pos.getY(), pos.getZ());
        from = from.subtract(pos.getX(), pos.getY(), pos.getZ());
        Part best = null;
        double bestDistance = Double.MAX_VALUE;
        for (Part part : micro.parts()) {
            Optional<Vec3> hit = part.box().clip(from, to);
            if (hit.isPresent() && part.mask != null) hit = marched(part, hit.get(), from, to);      // a part with holes: the ray goes through them
            if (hit.isPresent()) {
                double distance = hit.get().distanceToSqr(from);
                if (distance <= bestDistance + 1.0E-6) {
                    bestDistance = distance;
                    best = part;
                }
            }
        }
        if (best == null && !micro.parts().isEmpty()) best = micro.parts().get(micro.parts().size() - 1);
        return best;
    }
}
