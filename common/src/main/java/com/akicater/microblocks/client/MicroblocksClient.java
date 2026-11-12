package com.akicater.microblocks.client;

import com.akicater.lib.client.ClientModels;
import com.akicater.lib.client.Overlay;
import com.akicater.microblocks.MicroBlock;
import com.akicater.microblocks.Microblocks;

public final class MicroblocksClient {
    private MicroblocksClient() {}

    public static void init() {
        // the parts are drawn by the chunk mesh (a model of Aki Lib), the renderer of the block entity is not used
        MicroBlock.bakedModel = true;
        ClientModels.block(Microblocks.MOD_ID + ":holder", new MicroBlockQuads());
        com.akicater.lib.client.Tints.block(Microblocks.BLOCK);      // grass and leaves take the colour of the biome
        // the items in the hand, in a slot and on the ground: the box itself, or the whole block space of a prefab in blue
        ClientModels.item(Microblocks.MOD_ID + ":microblock", stack -> MicroItemView.of(stack, false));
        ClientModels.item(Microblocks.MOD_ID + ":prefab", stack -> MicroItemView.of(stack, true));
        // the outline of the box that would be placed, the ghost of a prefab
        com.akicater.microblocks.MicroConfig.load();
        Overlay.register(new MicroPreviewCore());
        Overlay.register(new MicroDebugCore());
        // the keys of the wheel and the turn (in the controls of the game), and Ctrl: asked every tick (not from the drawing of the world, which a mod of graphics may leave out)
        dev.architectury.registry.client.keymappings.KeyMappingRegistry.register(MicroPreviewCore.WHEEL);
        dev.architectury.registry.client.keymappings.KeyMappingRegistry.register(MicroPreviewCore.ROTATE);
        dev.architectury.event.events.client.ClientTickEvent.CLIENT_POST.register(MicroPreviewCore::keys);
        // the screen of the table
        #if MC_VER >= V1_21_9
        Microblocks.TABLE_MENU.listen(type -> dev.architectury.registry.client.gui.MenuScreenRegistry.registerScreenFactory(type, MicroTableScreen::new));
        Microblocks.BLUEPRINT_MENU.listen(type -> dev.architectury.registry.client.gui.MenuScreenRegistry.registerScreenFactory(type, MicroTableScreen::new));
        #else
        Microblocks.TABLE_MENU.listen(type -> dev.architectury.registry.menu.MenuRegistry.registerScreenFactory(type, MicroTableScreen::new));
        Microblocks.BLUEPRINT_MENU.listen(type -> dev.architectury.registry.menu.MenuRegistry.registerScreenFactory(type, MicroTableScreen::new));
        #endif
    }
}
