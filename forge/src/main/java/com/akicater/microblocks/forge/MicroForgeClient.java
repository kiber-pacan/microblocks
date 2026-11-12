package com.akicater.microblocks.forge;

import com.akicater.microblocks.client.MicroConfigScreen;
import net.minecraftforge.fml.ModLoadingContext;
#if MC_VER >= V1_19_2
import net.minecraftforge.client.ConfigScreenHandler;
#else
import net.minecraftforge.client.ConfigGuiHandler;
#endif

/** The settings page of the mod in the list of mods (client only: this class is only touched there). */
final class MicroForgeClient {
    private MicroForgeClient() {}

    static void registerConfig() {
        ModLoadingContext.get().registerExtensionPoint(
                #if MC_VER >= V1_19_2 ConfigScreenHandler.ConfigScreenFactory.class #else ConfigGuiHandler.ConfigGuiFactory.class #endif,
                () -> new #if MC_VER >= V1_19_2 ConfigScreenHandler.ConfigScreenFactory #else ConfigGuiHandler.ConfigGuiFactory #endif((client, screen) -> new MicroConfigScreen(screen)));
    }
}
