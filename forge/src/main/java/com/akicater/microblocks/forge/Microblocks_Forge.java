package com.akicater.microblocks.forge;

import com.akicater.microblocks.Microblocks;
import dev.architectury.platform.forge.EventBuses;
import net.minecraftforge.api.distmarker.Dist;
import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;
import net.minecraftforge.fml.loading.FMLLoader;

@Mod(Microblocks.MOD_ID)
public final class Microblocks_Forge {
    public Microblocks_Forge() {
        EventBuses.registerModEventBus(Microblocks.MOD_ID, FMLJavaModLoadingContext.get().getModEventBus());
        Microblocks.initializeServer();
        if (FMLLoader.getDist() == Dist.CLIENT) {
            Microblocks.initializeClient();
            MicroForgeClient.registerConfig();
        }
    }
}
