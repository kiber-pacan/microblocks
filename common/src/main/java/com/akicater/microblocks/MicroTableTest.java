package com.akicater.microblocks;

import com.akicater.lib.Compat;

import net.minecraft.core.BlockPos;
import net.minecraft.server.level.ServerLevel;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.player.Inventory;
import net.minecraft.world.inventory.ContainerLevelAccess;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.item.Items;
import net.minecraft.world.level.block.Block;
import net.minecraft.world.level.block.Blocks;

import java.util.ArrayList;
import java.util.List;

/** Used by the autotest: the cutting on the table, the blueprint, the count of prefabs, what is used up and what is given back, the placing of a prefab. */
public final class MicroTableTest {
    private MicroTableTest() {}

    private static String dims(ItemStack stack) {
        if (stack.isEmpty()) return "empty";
        int[] d = MicroItemData.dims(stack);
        return stack.getCount() + "x " + Compat.blockId(MicroItemData.material(stack)) + " " + d[0] + "x" + d[1] + "x" + d[2];
    }

    private static boolean is(ItemStack stack, Block material, int a, int b, int c) {
        if (!(stack.getItem() instanceof MicroItem)) return false;
        int[] d = MicroItemData.dims(stack);
        return MicroItemData.material(stack) == material && d[0] == a && d[1] == b && d[2] == c;
    }

    private static void press(MicroTableMenu menu, ServerPlayer player, int id, int times) {
        for (int i = 0; i < times; i++) menu.clickMenuButton(player, id);
    }

    private static void takeOutputs(MicroTableMenu menu) {
        menu.table().setItem(MicroTableMenu.OUT1, ItemStack.EMPTY);
        menu.table().setItem(MicroTableMenu.OUT2, ItemStack.EMPTY);
    }

    public static String run(ServerLevel level, BlockPos near) {
        List<String> errors = new ArrayList<>();
        if (level.players().isEmpty()) return "no player";
        ServerPlayer player = level.players().get(0);
        Inventory inventory = player.getInventory();
        inventory.clearContent();
        MicroTableMenu menu = new MicroTableMenu(90, inventory, ContainerLevelAccess.NULL);
        var table = menu.table();

        // ---- cutting: a block along x in the middle, then along z at 4/16
        table.setItem(MicroTableMenu.IN, new ItemStack(Blocks.STONE.asItem(), 2));
        press(menu, player, MicroTableMenu.BTN_AXIS, 1);
        if (menu.pos() != 8) errors.add("the cut did not start in the middle: " + menu.pos());
        press(menu, player, MicroTableMenu.BTN_DO_CUT, 1);
        if (!is(table.getItem(MicroTableMenu.OUT1), Blocks.STONE, 8, 16, 16) || !is(table.getItem(MicroTableMenu.OUT2), Blocks.STONE, 8, 16, 16)) errors.add("a block cut along x gave " + dims(table.getItem(1)) + " and " + dims(table.getItem(2)));
        if (table.getItem(MicroTableMenu.IN).getCount() != 1) errors.add("the cut used up " + (2 - table.getItem(MicroTableMenu.IN).getCount()) + " blocks");
        press(menu, player, MicroTableMenu.BTN_DO_CUT, 1);                                    // the outputs are full: nothing happens
        if (table.getItem(MicroTableMenu.IN).getCount() != 1) errors.add("a cut was made while the outputs were full");
        takeOutputs(menu);
        press(menu, player, MicroTableMenu.BTN_AXIS + 2, 1);                                  // z
        press(menu, player, MicroTableMenu.BTN_POS_MINUS, 4);
        press(menu, player, MicroTableMenu.BTN_DO_CUT, 1);
        if (!is(table.getItem(MicroTableMenu.OUT1), Blocks.STONE, 16, 16, 4) || !is(table.getItem(MicroTableMenu.OUT2), Blocks.STONE, 16, 16, 12)) errors.add("a block cut along z at 4 gave " + dims(table.getItem(1)) + " and " + dims(table.getItem(2)));
        takeOutputs(menu);

        // a microblock is cut by its own sizes; the place is kept inside them
        table.setItem(MicroTableMenu.IN, MicroItemData.make(Blocks.OAK_PLANKS, 16, 16, 4));
        press(menu, player, MicroTableMenu.BTN_POS_PLUS, 12);
        if (menu.pos() != 3) errors.add("the place went beyond the size of the piece: " + menu.pos());
        press(menu, player, MicroTableMenu.BTN_DO_CUT, 1);
        if (!is(table.getItem(MicroTableMenu.OUT1), Blocks.OAK_PLANKS, 16, 16, 3) || !is(table.getItem(MicroTableMenu.OUT2), Blocks.OAK_PLANKS, 16, 16, 1)) errors.add("a plane cut at 3 gave " + dims(table.getItem(1)) + " and " + dims(table.getItem(2)));
        takeOutputs(menu);
        table.setItem(MicroTableMenu.IN, MicroItemData.make(Blocks.OAK_PLANKS, 1, 16, 16));
        press(menu, player, MicroTableMenu.BTN_AXIS, 1);
        press(menu, player, MicroTableMenu.BTN_DO_CUT, 1);
        if (!table.getItem(MicroTableMenu.OUT1).isEmpty()) errors.add("a piece 1/16 thick was cut again");
        table.setItem(MicroTableMenu.IN, ItemStack.EMPTY);

        // ---- the blueprint: a block of microblocks, written on paper
        BlockPos spot = near.offset(0, 4, -20);
        level.setBlock(spot, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        PrefabData.place(level, spot, List.of(new Part(0, 0, 0, 16, 4, 16, Blocks.STONE), new Part(7, 4, 7, 9, 16, 9, Blocks.OAK_PLANKS)));
        player.setItemInHand(InteractionHand.MAIN_HAND, new ItemStack(Items.PAPER, 2));
        if (!MicroBlueprints.create(player, InteractionHand.MAIN_HAND, spot)) errors.add("a blueprint could not be made of a block of microblocks");
        if (player.getItemInHand(InteractionHand.MAIN_HAND).getCount() != (player.getAbilities().instabuild ? 2 : 1)) errors.add("the paper was not used up");
        ItemStack blueprint = ItemStack.EMPTY;
        for (int i = 0; i < inventory.getContainerSize(); i++) if (inventory.getItem(i).getItem() == Microblocks.BLUEPRINT.get()) blueprint = inventory.getItem(i).copy();
        if (blueprint.isEmpty()) {
            errors.add("no blueprint in the inventory");
        } else if (PrefabData.read(blueprint).size() != 2) {
            errors.add("the blueprint holds " + PrefabData.read(blueprint).size() + " parts, not 2");
        }
        level.setBlock(spot, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        // ---- the count: stone 1024 per prefab, oak 2 x 12 x 2 = 48; one block of each (4096) gives 4 by the stone
        inventory.clearContent();
        menu.setMode(MicroTableMenu.MODE_PREFAB);
        table.setItem(MicroTableMenu.PAPER, blueprint);
        table.setItem(MicroTableMenu.ING_FIRST, new ItemStack(Blocks.STONE.asItem(), 1));
        table.setItem(MicroTableMenu.ING_FIRST + 1, new ItemStack(Blocks.OAK_PLANKS.asItem(), 1));
        if (menu.prefabCount() != 4) errors.add("one block of each gave " + menu.prefabCount() + " prefabs, not 4");
        // more stone, in pieces of different sizes: 1 block + a plane 1/2 + a plane 1/4 = 4096 + 2048 + 1024 = 7168 -> 7
        table.setItem(MicroTableMenu.ING_FIRST + 2, MicroItemData.make(Blocks.STONE, 16, 16, 8));
        table.setItem(MicroTableMenu.ING_FIRST + 3, MicroItemData.make(Blocks.STONE, 16, 16, 4));
        if (menu.prefabCount() != 7) errors.add("pieces of different sizes gave " + menu.prefabCount() + " prefabs, not 7");
        table.setItem(MicroTableMenu.ING_FIRST + 2, ItemStack.EMPTY);
        table.setItem(MicroTableMenu.ING_FIRST + 3, ItemStack.EMPTY);
        if (menu.prefabCount() != 4) errors.add("after taking the pieces out: " + menu.prefabCount());
        // a material that is missing: nothing
        table.setItem(MicroTableMenu.ING_FIRST + 1, ItemStack.EMPTY);
        if (menu.prefabCount() != 0) errors.add("without the oak there were " + menu.prefabCount() + " prefabs");
        table.setItem(MicroTableMenu.ING_FIRST + 1, new ItemStack(Blocks.OAK_PLANKS.asItem(), 1));

        // taking the 4 prefabs: the stone block is used up, the oak block gives back what is left (3904 = 1/2 + 1/4 + 1/8 + 1/16 planes, 64 is lost)
        ItemStack result = menu.slots.get(MicroTableMenu.PREFAB_OUT).getItem().copy();
        menu.slots.get(MicroTableMenu.PREFAB_OUT).onTake(player, result);
        menu.slots.get(MicroTableMenu.PREFAB_OUT).set(ItemStack.EMPTY);
        if (!table.getItem(MicroTableMenu.ING_FIRST).isEmpty()) errors.add("the stone block was not used up");
        if (!table.getItem(MicroTableMenu.ING_FIRST + 1).isEmpty()) errors.add("the oak block was not used up");
        int back = 0;
        for (int i = 0; i < inventory.getContainerSize(); i++) { ItemStack stack = inventory.getItem(i); if (stack.getItem() instanceof MicroItem && MicroItemData.material(stack) == Blocks.OAK_PLANKS) back += PrefabData.volumeOf(stack) * stack.getCount(); }
        if (back != 3840) errors.add("the oak that was given back is " + back + " cubic sixteenths, not 3840");
        if (result.getCount() != 4 || PrefabData.read(result).size() != 2) errors.add("the prefabs taken: " + result.getCount() + " with " + PrefabData.read(result).size() + " parts");

        // ---- a prefab goes anywhere: into a free block space, not into one that is taken
        BlockPos free = near.offset(3, 4, -20);
        level.setBlock(free, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);
        List<Part> parts = PrefabData.read(result);
        if (!PrefabData.place(level, free, parts)) errors.add("a prefab could not be placed in a free space");
        else if (!(level.getBlockEntity(free) instanceof MicroBlockEntity be) || be.parts().size() != 2) errors.add("the placed prefab does not hold its parts");
        if (PrefabData.place(level, free, parts)) errors.add("a prefab was placed over itself");
        level.setBlock(free, Blocks.AIR.defaultBlockState(), Block.UPDATE_ALL);

        inventory.clearContent();
        System.out.println("AUTOTEST table checks: " + (errors.isEmpty() ? "all passed" : errors));
        return errors.isEmpty() ? "ok" : String.join("; ", errors);
    }
}
