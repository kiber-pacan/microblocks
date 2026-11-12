package com.akicater.microblocks.fabric.client;

import com.akicater.microblocks.client.MicroConfigScreen;
import com.terraformersmc.modmenu.api.ConfigScreenFactory;
import com.terraformersmc.modmenu.api.ModMenuApi;

/** The settings page of the mod in the mod menu. */
public final class MicroModMenu implements ModMenuApi {
    @Override
    public ConfigScreenFactory<?> getModConfigScreenFactory() {
        return screen -> new MicroConfigScreen(screen);
    }
}
