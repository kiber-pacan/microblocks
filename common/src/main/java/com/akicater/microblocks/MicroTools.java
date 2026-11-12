package com.akicater.microblocks;

import com.akicater.lib.Compat;

import dev.architectury.event.EventResult;
import dev.architectury.event.events.common.InteractionEvent;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Working a block with a chisel or a drill (a left click). An ordinary block that can be cut becomes a block of microblocks first. The tool takes away the cells of its
 * shape from the parts it meets (a part with holes is still one part); what is taken away is given back: as the exact pieces cut out when the tool has Silk Touch,
 * as plain 1/16 cubes when it has not.
 */
public final class MicroTools {
    private MicroTools() {}

    public static void register() {
        InteractionEvent.LEFT_CLICK_BLOCK.register((player, hand, pos, face) -> {
            ItemStack stack = player.getItemInHand(hand);
            if (!(stack.getItem() instanceof MicroToolItem tool)) return #if MC_VER >= V1_21_3 && MC_VER < V26_2 net.minecraft.world.InteractionResult.PASS #else EventResult.pass() #endif;
            // the click is on the client: it says to the server what is looked at (the server would not hear of a click that was cancelled here), and nothing breaks
            if (Compat.level(player).isClientSide()) com.akicater.microblocks.client.MicroToolClient.use(pos, face);
            return #if MC_VER >= V1_21_3 && MC_VER < V26_2 net.minecraft.world.InteractionResult.FAIL #else EventResult.interruptFalse() #endif;      // the tool does not break the block the usual way
        });
    }

    /** Adds the cell (gx, gy, gz) of the world (in sixteenths) to the regions of the block spaces that it falls in. */
    private static void mark(Map<BlockPos, boolean[]> regions, int gx, int gy, int gz) {
        BlockPos pos = new BlockPos(Math.floorDiv(gx, 16), Math.floorDiv(gy, 16), Math.floorDiv(gz, 16));
        regions.computeIfAbsent(pos, k -> new boolean[4096])[Math.floorMod(gx, 16) + 16 * (Math.floorMod(gy, 16) + 16 * Math.floorMod(gz, 16))] = true;
    }

    /** The cells that the tool takes away, in the block spaces that they fall in (a tool reaches over the border of a block): {@code g} is the cell that is worked, in the world. */
    public static Map<BlockPos, boolean[]> region(MicroToolItem.Tool tool, int[] g, Direction face) {
        Map<BlockPos, boolean[]> regions = new HashMap<>();
        int n = face.getAxis().ordinal();
        int t1 = (n + 1) % 3, t2 = (n + 2) % 3;
        int inward = -face.getAxisDirection().getStep();
        switch (tool) {
            case FINE -> mark(regions, g[0], g[1], g[2]);
            case FLAT -> {
                for (int a = -1; a <= 2; a++) for (int b = -1; b <= 2; b++) {
                    int[] p = g.clone();
                    p[t1] += a; p[t2] += b;
                    mark(regions, p[0], p[1], p[2]);
                }
            }
            case GOUGE -> {
                for (int dx = -2; dx <= 2; dx++) for (int dy = -2; dy <= 2; dy++) for (int dz = -2; dz <= 2; dz++)
                    if (dx * dx + dy * dy + dz * dz <= 5) mark(regions, g[0] + dx, g[1] + dy, g[2] + dz);
            }
            case DRILL_THIN, DRILL_WIDE -> {
                int lo = tool == MicroToolItem.Tool.DRILL_THIN ? 0 : -1, hi = tool == MicroToolItem.Tool.DRILL_THIN ? 1 : 2;
                for (int depth = 0; depth < 16; depth++) for (int a = lo; a <= hi; a++) for (int b = lo; b <= hi; b++) {
                    int[] p = g.clone();
                    p[n] += depth * inward; p[t1] += a; p[t2] += b;
                    mark(regions, p[0], p[1], p[2]);
                }
            }
            default -> { }
        }
        return regions;
    }

    /** A use of the tool as the client reported it (the spot is the middle of the cell that it looked at). */
    public static void work(ServerPlayer player, ItemStack stack, MicroToolItem.Tool tool, BlockPos pos, Direction face, Vec3 at) {
        Level level = Compat.level(player);
        if (!player.mayBuild() || !level.mayInteract(player, pos)) return;
        carve(player, stack, tool, pos, face, at);
    }

    /** The work on the spot {@code at} of the face {@code face} of the block {@code pos}: it takes away the cells of its shape from every block space that it reaches. */
    public static void carve(ServerPlayer player, ItemStack stack, MicroToolItem.Tool tool, BlockPos pos, Direction face, Vec3 at) {
        Level level = Compat.level(player);
        // the cell that is looked at
        Vec3 inside = at.subtract(pos.getX(), pos.getY(), pos.getZ()).subtract(face.getStepX() * 0.001, face.getStepY() * 0.001, face.getStepZ() * 0.001);
        int[] c = {clamp((int) Math.floor(inside.x * 16)), clamp((int) Math.floor(inside.y * 16)), clamp((int) Math.floor(inside.z * 16))};
        int[] g = {pos.getX() * 16 + c[0], pos.getY() * 16 + c[1], pos.getZ() * 16 + c[2]};

        Map<BlockPos, boolean[]> regions;
        if (tool == MicroToolItem.Tool.SPLITTER) {
            // the whole part that is looked at, in this block space
            MicroPlacement.convert(level, pos);
            if (!(level.getBlockEntity(pos) instanceof MicroBlockEntity be)) return;
            Part top = null;
            for (Part p : be.parts()) if (p.has(c[0], c[1], c[2])) top = p;
            if (top == null) return;
            boolean[] cells = new boolean[4096];
            for (int x = top.minX; x < top.maxX; x++) for (int y = top.minY; y < top.maxY; y++) for (int z = top.minZ; z < top.maxZ; z++) if (top.has(x, y, z)) cells[x + 16 * (y + 16 * z)] = true;
            regions = new HashMap<>();
            regions.put(pos, cells);
        } else {
            regions = region(tool, g, face);
        }
        boolean first = true, worked = false;
        for (Map.Entry<BlockPos, boolean[]> e : regions.entrySet()) {
            BlockPos at2 = e.getKey();
            if (!level.isInWorldBounds(at2) || !level.hasChunkAt(at2) || !level.mayInteract(player, at2)) continue;
            if (applyRegion(level, player, stack, at2, e.getValue(), first)) { worked = true; first = false; }
        }
        if (worked && !player.getAbilities().instabuild) wear(player, stack);
    }

    /** Takes the cells of the region out of the parts of one block space; false if there was nothing there to take. */
    private static boolean applyRegion(Level level, ServerPlayer player, ItemStack stack, BlockPos pos, boolean[] region, boolean sound) {
        MicroPlacement.convert(level, pos);                                       // an ordinary block that can be cut
        if (!(level.getBlockEntity(pos) instanceof MicroBlockEntity be) || be.isEmpty()) return false;

        // a box across block spaces, a fill or a statue is not one piece any more once it is worked: its pieces are let go
        List<Part> next = new ArrayList<>();
        Map<Block, boolean[]> taken = new HashMap<>();
        for (Part p : be.parts()) {
            boolean any = false;
            boolean[] keep = new boolean[4096];
            int kept = 0, x0 = 16, y0 = 16, z0 = 16, x1 = 0, y1 = 0, z1 = 0;
            for (int x = p.minX; x < p.maxX; x++) for (int y = p.minY; y < p.maxY; y++) for (int z = p.minZ; z < p.maxZ; z++) {
                if (!p.has(x, y, z)) continue;
                int i = x + 16 * (y + 16 * z);
                if (region[i]) {
                    any = true;
                    taken.computeIfAbsent(p.material, k -> new boolean[4096])[i] = true;
                } else {
                    keep[i] = true;
                    kept++;
                    x0 = Math.min(x0, x); y0 = Math.min(y0, y); z0 = Math.min(z0, z); x1 = Math.max(x1, x + 1); y1 = Math.max(y1, y + 1); z1 = Math.max(z1, z + 1);
                }
            }
            if (!any) { next.add(p); continue; }
            if (kept == 0) continue;
            boolean whole = kept == (x1 - x0) * (y1 - y0) * (z1 - z0);
            next.add(new Part(x0, y0, z0, x1, y1, z1, p.material, p.orient, p.tx, p.ty, p.tz, null, p.inner, whole ? null : Part.maskOf(keep), 0));
        }
        if (taken.isEmpty()) {
            if (be.parts().isEmpty()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
            return false;
        }
        be.setParts(next);
        if (next.isEmpty()) level.setBlock(pos, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        // sound and particles of what was taken, then what is given back
        boolean silk = hasSilkTouch(level, stack);
        boolean soundNow = sound;
        for (Map.Entry<Block, boolean[]> e : taken.entrySet()) {
            boolean[] cells = e.getValue();
            int count = 0, x0 = 16, y0 = 16, z0 = 16, x1 = 0, y1 = 0, z1 = 0;
            for (int i = 0; i < 4096; i++) if (cells[i]) {
                count++;
                int x = i & 15, y = i >> 4 & 15, z = i >> 8;
                x0 = Math.min(x0, x); y0 = Math.min(y0, y); z0 = Math.min(z0, z); x1 = Math.max(x1, x + 1); y1 = Math.max(y1, y + 1); z1 = Math.max(z1, z + 1);
            }
            boolean whole = count == (x1 - x0) * (y1 - y0) * (z1 - z0);
            Part removed = new Part(x0, y0, z0, x1, y1, z1, e.getKey(), 0, 0, 0, 0, null, 0, whole ? null : Part.maskOf(cells));
            MicroBreaking.effects(level, pos, removed, soundNow, 12);
            soundNow = false;
            if (player.getAbilities().instabuild) continue;
            if (silk) {
                java.util.List<Part> boxes = MicroPlacement.boxesOf(removed);
                if (boxes.size() <= 6) for (Part box : boxes) Block.popResource(level, pos, MicroItemData.make(box));      // the exact piece that was cut out
                else for (ItemStack pile : MicroItemData.pile(e.getKey(), count)) Block.popResource(level, pos, pile);      // too many boxes to give them one by one
            } else {
                int left = count;
                while (left > 0) {
                    ItemStack cubes = MicroItemData.make(e.getKey(), 1, 1, 1);
                    cubes.setCount(Math.min(64, left));
                    left -= cubes.getCount();
                    Block.popResource(level, pos, cubes);                                                     // plain 1/16 cubes
                }
            }
        }
        return true;
    }

    private static int clamp(int v) {
        return Math.max(0, Math.min(15, v));
    }

    /** Whether the tool has Silk Touch (the way to ask changed with 1.21 and with 1.21.2). */
    public static boolean hasSilkTouch(Level level, ItemStack stack) {
        #if MC_VER >= V1_21_3
        var enchantments = level.registryAccess().lookupOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
        return net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(enchantments.getOrThrow(net.minecraft.world.item.enchantment.Enchantments.SILK_TOUCH), stack) > 0;
        #elif MC_VER >= V1_21
        var enchantments = level.registryAccess().registryOrThrow(net.minecraft.core.registries.Registries.ENCHANTMENT);
        return net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(enchantments.getHolderOrThrow(net.minecraft.world.item.enchantment.Enchantments.SILK_TOUCH), stack) > 0;
        #else
        return net.minecraft.world.item.enchantment.EnchantmentHelper.getItemEnchantmentLevel(net.minecraft.world.item.enchantment.Enchantments.SILK_TOUCH, stack) > 0;
        #endif
    }

    /** One point of wear for a use. */
    private static void wear(ServerPlayer player, ItemStack stack) {
        #if MC_VER >= V1_21
        stack.hurtAndBreak(1, player, net.minecraft.world.entity.EquipmentSlot.MAINHAND);
        #else
        stack.hurtAndBreak(1, player, p -> p.broadcastBreakEvent(net.minecraft.world.InteractionHand.MAIN_HAND));
        #endif
    }
}
