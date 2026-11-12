package com.akicater.microblocks.fabric;

import com.akicater.microblocks.Microblocks;
import net.fabricmc.api.ModInitializer;

public final class Microblocks_Fabric implements ModInitializer {
    @Override
    public void onInitialize() {
        Microblocks.initializeServer();
    }
}
