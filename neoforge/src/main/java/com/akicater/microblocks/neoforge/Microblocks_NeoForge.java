package com.akicater.microblocks.neoforge;

import com.akicater.microblocks.Microblocks;
import net.neoforged.bus.api.IEventBus;
import net.neoforged.fml.common.Mod;
import net.neoforged.fml.loading.FMLEnvironment;

@Mod(Microblocks.MOD_ID)
public final class Microblocks_NeoForge {
    public Microblocks_NeoForge(IEventBus modBus) {
        Microblocks.initializeServer();
        if (#if MC_VER >= V1_21_9 FMLEnvironment.getDist().isClient() #else FMLEnvironment.dist.isClient() #endif) {
            Microblocks.initializeClient();
            MicroNeoForgeClient.registerConfig();
        }
    }
}
