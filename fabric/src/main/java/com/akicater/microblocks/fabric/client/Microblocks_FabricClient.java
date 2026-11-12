package com.akicater.microblocks.fabric.client;

import com.akicater.microblocks.Microblocks;
import net.fabricmc.api.ClientModInitializer;

public final class Microblocks_FabricClient implements ClientModInitializer {
    @Override
    public void onInitializeClient() {
        Microblocks.initializeClient();
    }
}
