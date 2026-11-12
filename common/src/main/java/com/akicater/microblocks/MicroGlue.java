package com.akicater.microblocks;

import com.akicater.lib.Compat;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.InteractionEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Glue: a slime ball joins parts into one prefab that stands in the world (a statue: it is pointed at, broken and taken as one piece, and comes back as a prefab item).
 * Two right clicks give two corners; every part that reaches into the box between them is joined, and a prefab that the box reaches into is joined whole. With Ctrl
 * the corners are whole blocks. An ordinary block that can be cut becomes a block of microblocks. Sneaking lets the first corner go.
 */
public final class MicroGlue {
    private MicroGlue() {}

    /** The most blocks along one axis that are joined at once. */
    public static final int MOST = 4;

    // what a handler of a right click answers: older versions of Architectury take the result of the game, newer ones its own
    #if MC_VER >= V26_2 || MC_VER <= V1_21_1
    private static final EventResult PASS = EventResult.pass(), TAKEN = EventResult.interruptTrue();
    #else
    private static final net.minecraft.world.InteractionResult PASS = net.minecraft.world.InteractionResult.PASS, TAKEN = net.minecraft.world.InteractionResult.SUCCESS;
    #endif

    /** A part that would be joined: the block space and the part ({@code plain}: an ordinary block, all of it). */
    public record Target(BlockPos pos, Part part, boolean plain) {}

    public static void register() {
        InteractionEvent.RIGHT_CLICK_BLOCK.register((player, hand, pos, face) -> {
            if (!player.getItemInHand(hand).is(Items.SLIME_BALL)) return PASS;
            Level level = Compat.level(player);
            if (!(level.getBlockState(pos).getBlock() instanceof MicroBlock) && !plain(level, pos)) return PASS;
            if (!(player instanceof ServerPlayer)) {
                com.akicater.microblocks.client.MicroPreviewCore.glueClicked(cell(player, pos, face), player.isShiftKeyDown());      // what the client shows; the server does the work
                return TAKEN;
            }
            click(player, hand, cell(player, pos, face));
            return TAKEN;
        });
    }

    /** An ordinary block that the glue (like a tool) may turn into a block of microblocks. */
    public static boolean plain(Level level, BlockPos pos) {
        BlockState state = level.getBlockState(pos);
        return !(state.getBlock() instanceof MicroBlock) && !Compat.replaceable(state) && MicroMaterials.isValid(state.getBlock()) && !state.hasBlockEntity();
    }

    /** The cell of the world (in sixteenths) that the player looks at on the block; the middle of the face when the view does not meet the block. */
    public static int[] cell(Player player, BlockPos pos, Direction face) {
        Vec3 from = player.getEyePosition(), to = from.add(player.getLookAngle().scale(8.0));
        BlockHitResult hit = Compat.level(player).clip(new ClipContext(from, to, ClipContext.Block.OUTLINE, ClipContext.Fluid.NONE, player));
        Vec3 at = hit.getType() == HitResult.Type.BLOCK && hit.getBlockPos().equals(pos)
                ? hit.getLocation().subtract(hit.getDirection().getStepX() * 0.001, hit.getDirection().getStepY() * 0.001, hit.getDirection().getStepZ() * 0.001)
                : new Vec3(pos.getX() + 0.5 + face.getStepX() * 0.49, pos.getY() + 0.5 + face.getStepY() * 0.49, pos.getZ() + 0.5 + face.getStepZ() * 0.49);
        int[] c = {(int) Math.floor(at.x * 16), (int) Math.floor(at.y * 16), (int) Math.floor(at.z * 16)};
        int[] base = {pos.getX() * 16, pos.getY() * 16, pos.getZ() * 16};
        for (int i = 0; i < 3; i++) c[i] = Math.max(base[i], Math.min(base[i] + 15, c[i]));
        return c;
    }

    /** The box between two cells: the low corner and the high one (not held), in sixteenths; with {@code blocks} it is made of whole blocks. */
    public static int[][] box(int[] a, int[] b, boolean blocks) {
        int[] lo = new int[3], hi = new int[3];
        for (int i = 0; i < 3; i++) {
            lo[i] = Math.min(a[i], b[i]);
            hi[i] = Math.max(a[i], b[i]) + 1;
            if (blocks) { lo[i] = Math.floorDiv(lo[i], 16) * 16; hi[i] = Math.floorDiv(hi[i] - 1, 16) * 16 + 16; }
        }
        return new int[][]{lo, hi};
    }

    private static boolean reaches(Part p, int bx, int by, int bz, int[] lo, int[] hi) {
        int x0 = Math.max(p.minX, lo[0] - bx * 16), y0 = Math.max(p.minY, lo[1] - by * 16), z0 = Math.max(p.minZ, lo[2] - bz * 16);
        int x1 = Math.min(p.maxX, hi[0] - bx * 16), y1 = Math.min(p.maxY, hi[1] - by * 16), z1 = Math.min(p.maxZ, hi[2] - bz * 16);
        if (x0 >= x1 || y0 >= y1 || z0 >= z1) return false;
        if (p.mask == null) return true;
        for (int x = x0; x < x1; x++) for (int y = y0; y < y1; y++) for (int z = z0; z < z1; z++) if (p.has(x, y, z)) return true;
        return false;
    }

    /** What the box would join (nothing is changed): the parts that reach into it, and the whole of every prefab among them. Null when the box is too big. */
    public static List<Target> targets(Level level, int[] lo, int[] hi) {
        int[] b0 = {Math.floorDiv(lo[0], 16), Math.floorDiv(lo[1], 16), Math.floorDiv(lo[2], 16)}, b1 = {Math.floorDiv(hi[0] - 1, 16), Math.floorDiv(hi[1] - 1, 16), Math.floorDiv(hi[2] - 1, 16)};
        for (int i = 0; i < 3; i++) if (b1[i] - b0[i] >= MOST) return null;
        List<Target> out = new ArrayList<>();
        Map<Integer, Part.Group> statues = new HashMap<>();
        for (int bx = b0[0]; bx <= b1[0]; bx++) for (int by = b0[1]; by <= b1[1]; by++) for (int bz = b0[2]; bz <= b1[2]; bz++) {
            BlockPos pos = new BlockPos(bx, by, bz);
            if (!level.isInWorldBounds(pos) || !level.hasChunkAt(pos)) continue;
            if (level.getBlockEntity(pos) instanceof MicroBlockEntity be) {
                for (Part p : be.parts()) {
                    if (!reaches(p, bx, by, bz, lo, hi)) continue;
                    if (p.group != null && p.group.statue()) statues.put(p.group.id(), p.group);      // all of it, below
                    else out.add(new Target(pos, p, false));
                }
            } else if (plain(level, pos)) {
                out.add(new Target(pos, new Part(0, 0, 0, 16, 16, 16, level.getBlockState(pos).getBlock()), true));
            }
        }
        for (Part.Group g : statues.values()) {
            for (int bx = Math.floorDiv(g.minX(), 16); bx <= Math.floorDiv(g.maxX() - 1, 16); bx++) for (int by = Math.floorDiv(g.minY(), 16); by <= Math.floorDiv(g.maxY() - 1, 16); by++) for (int bz = Math.floorDiv(g.minZ(), 16); bz <= Math.floorDiv(g.maxZ() - 1, 16); bz++) {
                BlockPos pos = new BlockPos(bx, by, bz);
                if (!(level.getBlockEntity(pos) instanceof MicroBlockEntity be)) continue;
                for (Part p : be.parts()) if (p.group != null && p.group.id() == g.id()) out.add(new Target(pos, p, false));
            }
        }
        return out;
    }

    /** A right click with the glue, on the server: the first one keeps the corner, the second one joins. */
    private static void click(Player player, InteractionHand hand, int[] cell) {
        MicroSettings.State state = MicroSettings.of(player);
        Level level = Compat.level(player);
        if (player.isShiftKeyDown()) {
            state.glueA = null;
            Compat.overlay(player, Compat.translatable("microblocks.glue.cancelled"));
            return;
        }
        int[] a = state.glueA;
        if (a == null) {
            state.glueA = cell;
            Compat.overlay(player, Compat.translatable("microblocks.glue.first"));
            return;
        }
        state.glueA = null;
        if (!player.mayBuild()) return;
        int[][] box = box(a, cell, state.ctrl);
        int joined = join(level, player, box[0], box[1]);
        if (joined == -1) Compat.overlay(player, Compat.translatable("microblocks.glue.too_big", MOST));
        else if (joined == -2) Compat.overlay(player, Compat.translatable("microblocks.glue.too_many", MicroBlockEntity.MAX_PARTS));
        else if (joined == 0) Compat.overlay(player, Compat.translatable("microblocks.glue.nothing"));
        else {
            Compat.overlay(player, Compat.translatable("microblocks.glue.joined", joined));
            level.playSound(null, player.blockPosition(), net.minecraft.sounds.SoundEvents.SLIME_BLOCK_PLACE, net.minecraft.sounds.SoundSource.BLOCKS, 0.8F, 1.0F);
            if (!player.getAbilities().instabuild) player.getItemInHand(hand).shrink(1);
        }
    }

    /** Joins what the box reaches into one statue; the number of parts joined, 0 for nothing there, -1 for a box that is too big, -2 for too many parts. */
    public static int join(Level level, Player player, int[] lo, int[] hi) {
        List<Target> targets = targets(level, lo, hi);
        if (targets == null) return -1;
        if (targets.isEmpty()) return 0;
        if (targets.size() > MicroBlockEntity.MAX_PARTS) return -2;      // a prefab item holds no more
        for (Target t : targets) if (!level.mayInteract(player, t.pos())) return 0;
        int[] min = {Integer.MAX_VALUE, Integer.MAX_VALUE, Integer.MAX_VALUE}, max = {Integer.MIN_VALUE, Integer.MIN_VALUE, Integer.MIN_VALUE};
        for (Target t : targets) {
            Part p = t.part();
            int[] w = {t.pos().getX() * 16, t.pos().getY() * 16, t.pos().getZ() * 16};
            for (int i = 0; i < 3; i++) { min[i] = Math.min(min[i], w[i] + p.min(i)); max[i] = Math.max(max[i], w[i] + p.max(i)); }
        }
        // the block space of the prefab begins at the corner of a block: every part lies in one block space of it, as it lies in one of the world
        Part.Group group = new Part.Group(level.getRandom().nextInt() | 1, min[0], min[1], min[2], max[0], max[1], max[2], false, true,
                Math.floorDiv(min[0], 16) * 16, Math.floorDiv(min[1], 16) * 16, Math.floorDiv(min[2], 16) * 16);
        Map<BlockPos, List<Target>> byBlock = new java.util.LinkedHashMap<>();
        for (Target t : targets) byBlock.computeIfAbsent(t.pos(), k -> new ArrayList<>()).add(t);
        int sub = 0;
        for (Map.Entry<BlockPos, List<Target>> e : byBlock.entrySet()) {
            boolean plain = e.getValue().get(0).plain();
            if (plain) MicroPlacement.convert(level, e.getKey());
            if (!(level.getBlockEntity(e.getKey()) instanceof MicroBlockEntity be)) continue;
            List<Part> next = new ArrayList<>();
            for (Part p : be.parts()) {
                boolean chosen = plain;
                for (Target t : e.getValue()) chosen |= t.part() == p;
                next.add(chosen ? p.withGroup(group).withSub(++sub) : p);
            }
            be.setParts(next);
        }
        return sub;
    }
}
