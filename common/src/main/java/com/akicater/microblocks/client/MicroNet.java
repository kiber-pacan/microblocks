package com.akicater.microblocks.client;

import com.akicater.microblocks.MicroConfig;
import com.akicater.microblocks.MicroSettings;

import dev.architectury.networking.NetworkManager;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.FriendlyByteBuf;

/** What the client says to the server about its placing settings, and a way to open a screen that is the same in every version. */
public final class MicroNet {
    private MicroNet() {}

    public static void send(int kind, int value) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || mc.getConnection() == null) return;
        #if MC_VER >= V26_2
        NetworkManager.sendToServer(new MicroSettings.Payload(kind, value));
        #else
        FriendlyByteBuf buf =
                #if MC_VER >= V1_21 new net.minecraft.network.RegistryFriendlyByteBuf(io.netty.buffer.Unpooled.buffer(), mc.getConnection().registryAccess()) #else new FriendlyByteBuf(io.netty.buffer.Unpooled.buffer()) #endif;
        buf.writeInt(kind);
        buf.writeInt(value);
        NetworkManager.sendToServer(MicroSettings.ID, #if MC_VER >= V1_21 (net.minecraft.network.RegistryFriendlyByteBuf) #endif buf);
        #endif
    }

    /** The state of the player here is kept in step with the server. */
    public static void change(int kind, int value) {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null) return;
        MicroSettings.apply(MicroSettings.of(mc.player), kind, value);
        send(kind, value);
    }

    public static void open(Screen screen) {
        Minecraft mc = Minecraft.getInstance();
        #if MC_VER >= V26_2
        mc.gui.setScreen(screen);
        #else
        mc.setScreen(screen);
        #endif
    }

    public static Screen current() {
        Minecraft mc = Minecraft.getInstance();
        #if MC_VER >= V26_2
        return mc.gui.screen();
        #else
        return mc.screen;
        #endif
    }

    private static Object syncedFor;

    /** Once for every new player entity (a world was entered): the defaults of the config become the state, and the server learns them. */
    public static void syncIfNew() {
        Minecraft mc = Minecraft.getInstance();
        if (mc.player == null || syncedFor == mc.player) return;
        syncedFor = mc.player;
        MicroSettings.State state = MicroSettings.of(mc.player);
        state.mode = MicroConfig.defaultMode;
        state.replace = MicroConfig.defaultReplace;
        state.ctrl = false;
        state.rotMode = 0;
        send(MicroSettings.KIND_ROTMODE, 0);
        send(MicroSettings.KIND_MODE, state.mode);
        send(MicroSettings.KIND_REPLACE, state.replace ? 1 : 0);
        send(MicroSettings.KIND_CTRL, 0);
    }
}
