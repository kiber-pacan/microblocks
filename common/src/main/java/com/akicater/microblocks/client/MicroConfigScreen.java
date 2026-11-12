package com.akicater.microblocks.client;

import com.akicater.microblocks.MicroConfig;
import com.akicater.microblocks.MicroSettings;

import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** The settings of the mod: from the mod menu (Fabric) or the mods list (Forge, NeoForge). */
public class MicroConfigScreen extends MicroScreenBase {
    private final Screen parent;
    private Button debug, blink, mode, replace;

    public MicroConfigScreen(Screen parent) {
        super(com.akicater.lib.Compat.literal("Microblocks"));
        this.parent = parent;
    }

    private static String onOff(boolean value) {
        return value ? "ON" : "OFF";
    }

    private void labels() {
        debug.setMessage(com.akicater.lib.Compat.literal("Mesh view (debug): " + onOff(MicroConfig.debugMesh)));
        blink.setMessage(com.akicater.lib.Compat.literal("Slow blinking preview: " + onOff(MicroConfig.blink)));
        mode.setMessage(com.akicater.lib.Compat.literal("Placing: " + MicroSettings.Mode.values()[MicroConfig.defaultMode].name().toLowerCase()));
        replace.setMessage(com.akicater.lib.Compat.literal("Put into others: " + onOff(MicroConfig.defaultReplace)));
    }

    @Override
    protected void init() {
        super.init();
        int x = this.width / 2 - 110, y = this.height / 4;
        debug = button(x, y, 220, 20, com.akicater.lib.Compat.empty(), () -> { MicroConfig.debugMesh = !MicroConfig.debugMesh; save(); });
        blink = button(x, y + 24, 220, 20, com.akicater.lib.Compat.empty(), () -> { MicroConfig.blink = !MicroConfig.blink; save(); });
        mode = button(x, y + 48, 220, 20, com.akicater.lib.Compat.empty(), () -> { MicroConfig.defaultMode = (MicroConfig.defaultMode + 1) % 3; save(); });
        replace = button(x, y + 72, 220, 20, com.akicater.lib.Compat.empty(), () -> { MicroConfig.defaultReplace = !MicroConfig.defaultReplace; save(); });
        button(x, y + 104, 220, 20, com.akicater.lib.Compat.literal("Done"), this::onClose);
        labels();
    }

    private void save() {
        MicroConfig.save();
        labels();
    }

    @Override
    public void onClose() {
        MicroNet.open(parent);
    }

    @Override
    protected void paint(Gfx g, int mouseX, int mouseY) {
        g.center("Microblocks", this.width / 2, this.height / 4 - 24, 0xFFFFFFFF);
        g.center("End opens the wheel of the placing; Ctrl swaps free and snapping; R turns the box", this.width / 2, this.height / 4 + 130, 0xFFA0A0A0);
    }
}
