package com.akicater.microblocks.neoforge;

import com.akicater.microblocks.client.MicroConfigScreen;
import net.neoforged.fml.ModLoadingContext;
import net.neoforged.neoforge.client.gui.IConfigScreenFactory;

/** The settings page of the mod in the list of mods (client only: this class is only touched there). */
final class MicroNeoForgeClient {
    private MicroNeoForgeClient() {}

    static void registerConfig() {
        ModLoadingContext.get().registerExtensionPoint(IConfigScreenFactory.class, () -> (client, parent) -> new MicroConfigScreen(parent));
    }
}
