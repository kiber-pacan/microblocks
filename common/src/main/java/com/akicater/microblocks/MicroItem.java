package com.akicater.microblocks;

import com.akicater.lib.Compat;

import net.minecraft.network.chat.Component;
import net.minecraft.sounds.SoundSource;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.InteractionResult;
import net.minecraft.world.entity.player.Player;
import net.minecraft.world.item.Item;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.context.UseOnContext;
import net.minecraft.world.level.Level;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.SoundType;

/** A microblock: a box of any size from 1/16 to a whole block on each axis, of a material block. */
public class MicroItem extends Item {
    public MicroItem(Properties properties) {
        super(properties);
    }

    #if MC_VER < V1_19_4
    @Override
    public void fillItemCategory(net.minecraft.world.item.CreativeModeTab tab, net.minecraft.core.NonNullList<ItemStack> items) {
        if (this.#if MC_VER >= V1_19_2 allowedIn #else allowdedIn #endif(tab)) Microblocks.fillMicros(items::add);
    }
    #endif

    @Override
    public Component getName(ItemStack stack) {
        int[] dims = MicroItemData.dims(stack);
        return com.akicater.lib.Compat.translatable("item.microblocks.micro", MicroItemData.material(stack).getName(),
                com.akicater.lib.Compat.translatable("microblocks.kind." + MicroItemData.kind(dims)), MicroItemData.description(dims));
    }

    /** Where this item would go if it were used on this hit; null when nowhere. Used by the placing and by the preview. */
    public static MicroPlacement.Result target(Level level, Player player, ItemStack stack, net.minecraft.core.BlockPos clicked,
                                               net.minecraft.core.Direction face, net.minecraft.world.phys.Vec3 hit) {
        double[] local = {hit.x - clicked.getX(), hit.y - clicked.getY(), hit.z - clicked.getZ()};
        int[] world = MicroItemData.worldDims(MicroItemData.dims(stack), MicroItemData.rotation(stack), face, player.getDirection(), local, MicroSettings.of(player).rotMode);
        MicroSettings.State settings = MicroSettings.of(player);
        MicroPlacement.Result result = switch (settings.effective()) {
            case FREE -> MicroPlacement.computeFree(level, clicked, face, hit, MicroItemData.material(stack), world, settings.replace || settings.fill);
            case SNAP -> MicroPlacement.compute(level, clicked, face, hit, MicroItemData.material(stack), world, false, settings.replace || settings.fill);
            default -> MicroPlacement.compute(level, clicked, face, hit, MicroItemData.material(stack), world, true, settings.replace || settings.fill);
        };   // free to the sixteenth; sneaking snaps to the edges and the middle
        if (result == null) return null;
        int rotation = MicroItemData.rotation(stack);
        int orient = rotation > 0 ? rotation : MicroItemData.orientation(MicroItemData.dims(stack), world);
        result = result.withOrient(orient);
        int inner = MicroItemData.inner(stack);
        if (inner != 0) result = result.withInner(inner);
        int[] off = MicroItemData.offset(stack);
        return off == null ? result : result.withOffset(MicroItemData.worldOffset(off, orient), settings.effective() == MicroSettings.Mode.FREE);
    }

    @Override
    public InteractionResult useOn(UseOnContext context) {
        Level level = context.getLevel();
        Player player = context.getPlayer();
        if (player == null) return InteractionResult.FAIL;
        if (level.isClientSide()) return InteractionResult.SUCCESS;
        ItemStack stack = context.getItemInHand();
        Block material = MicroItemData.material(stack);
        if (!MicroMaterials.isValid(material) || !player.mayBuild()) return InteractionResult.FAIL;

        MicroPlacement.Result result = target(level, player, stack, context.getClickedPos(), context.getClickedFace(), context.getClickLocation());
        MicroSettings.State placing = MicroSettings.of(player);
        if (!MicroPlacement.place(level, result, placing.replace || placing.fill, placing.fill)) return InteractionResult.FAIL;

        SoundType sound = material.defaultBlockState().getSoundType();
        level.playSound(null, result.pos(), sound.getPlaceSound(), SoundSource.BLOCKS, (sound.getVolume() + 1.0F) / 2.0F, sound.getPitch() * 0.8F);
        if (!player.getAbilities().instabuild) stack.shrink(1);
        return #if MC_VER >= V1_21_3 InteractionResult.SUCCESS_SERVER #else InteractionResult.SUCCESS #endif;
    }

    /** Used in the air: turns the item to the next way of lying (automatic, then the six ways to put its three sizes on x, y, z). */
    @Override
    public #if MC_VER >= V1_21_3 InteractionResult #else net.minecraft.world.InteractionResultHolder<ItemStack> #endif use(Level level, Player player, InteractionHand hand) {
        ItemStack stack = player.getItemInHand(hand);
        int next = (MicroItemData.rotation(stack) + (player.isSecondaryUseActive() ? 6 : 1)) % 7;
        MicroItemData.setRotation(stack, next);
        if (!level.isClientSide()) {
            Component text;
            if (next == 0) {
                text = com.akicater.lib.Compat.translatable("microblocks.rotation.auto");
            } else {
                int[] world = MicroItemData.worldDims(MicroItemData.dims(stack), next, net.minecraft.core.Direction.UP, net.minecraft.core.Direction.NORTH);
                text = com.akicater.lib.Compat.translatable("microblocks.rotation.axes", world[0], world[1], world[2]);
            }
            Compat.overlay(player, text);
        }
        return #if MC_VER >= V1_21_3 level.isClientSide() ? InteractionResult.SUCCESS : InteractionResult.SUCCESS_SERVER #else net.minecraft.world.InteractionResultHolder.sidedSuccess(stack, level.isClientSide()) #endif;
    }
}
