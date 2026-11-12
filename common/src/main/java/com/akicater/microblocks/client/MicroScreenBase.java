package com.akicater.microblocks.client;

#if MC_VER >= V26_1
import net.minecraft.client.gui.GuiGraphicsExtractor;
#elif MC_VER >= V1_20_1
import net.minecraft.client.gui.GuiGraphics;
#else
import com.mojang.blaze3d.vertex.PoseStack;
import net.minecraft.client.gui.GuiComponent;
#endif
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.network.chat.Component;

/** A screen that draws with a few calls that are the same in every version of the game (a filled rectangle and a line of text); the versions differ only in here. */
public abstract class MicroScreenBase extends Screen {
    public interface Gfx {
        void fill(int x0, int y0, int x1, int y1, int argb);

        void text(String text, int x, int y, int argb);

        void center(String text, int cx, int y, int argb);
    }

    protected MicroScreenBase(Component title) {
        super(title);
    }

    protected abstract void paint(Gfx g, int mouseX, int mouseY);

    protected Button button(int x, int y, int w, int h, Component label, Runnable action) {
        #if MC_VER >= V1_19_4
        return addRenderableWidget(Button.builder(label, b -> action.run()).bounds(x, y, w, h).build());
        #else
        return addRenderableWidget(new Button(x, y, w, h, label, b -> action.run()));
        #endif
    }

    #if MC_VER >= V26_1
    @Override
    public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        super.extractRenderState(graphics, mouseX, mouseY, partialTick);
        paint(new Gfx() {
            public void fill(int x0, int y0, int x1, int y1, int argb) { graphics.fill(x0, y0, x1, y1, argb); }
            public void text(String text, int x, int y, int argb) { graphics.text(font, com.akicater.lib.Compat.literal(text), x, y, argb, true); }
            public void center(String text, int cx, int y, int argb) { graphics.centeredText(font, com.akicater.lib.Compat.literal(text), cx, y, argb); }
        }, mouseX, mouseY);
    }
    #elif MC_VER >= V1_20_1
    @Override
    public void render(GuiGraphics graphics, int mouseX, int mouseY, float partialTick) {
        #if MC_VER < V1_20_4
        this.renderBackground(graphics);
        #endif
        super.render(graphics, mouseX, mouseY, partialTick);
        paint(new Gfx() {
            public void fill(int x0, int y0, int x1, int y1, int argb) { graphics.fill(x0, y0, x1, y1, argb); }
            public void text(String text, int x, int y, int argb) { graphics.drawString(font, com.akicater.lib.Compat.literal(text), x, y, argb, true); }
            public void center(String text, int cx, int y, int argb) { graphics.drawCenteredString(font, com.akicater.lib.Compat.literal(text), cx, y, argb); }
        }, mouseX, mouseY);
    }
    #else
    @Override
    public void render(PoseStack pose, int mouseX, int mouseY, float partialTick) {
        this.renderBackground(pose);
        super.render(pose, mouseX, mouseY, partialTick);
        paint(new Gfx() {
            public void fill(int x0, int y0, int x1, int y1, int argb) { GuiComponent.fill(pose, x0, y0, x1, y1, argb); }
            public void text(String text, int x, int y, int argb) { font.drawShadow(pose, com.akicater.lib.Compat.literal(text), x, y, argb); }
            public void center(String text, int cx, int y, int argb) { GuiComponent.drawCenteredString(pose, font, com.akicater.lib.Compat.literal(text), cx, y, argb); }
        }, mouseX, mouseY);
    }
    #endif
}
