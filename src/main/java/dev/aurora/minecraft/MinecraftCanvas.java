package dev.aurora.minecraft;

import dev.aurora.ui.Canvas;
import net.minecraft.client.gui.Font;
import net.minecraft.client.gui.GuiGraphicsExtractor;

public final class MinecraftCanvas implements Canvas {
    private final GuiGraphicsExtractor graphics;
    private final Font font;

    public MinecraftCanvas(GuiGraphicsExtractor graphics, Font font) {
        this.graphics = graphics;
        this.font = font;
    }

    @Override public void roundedRect(float x, float y, float width, float height, float radius, int color) {
        int left = Math.round(x), top = Math.round(y), right = Math.round(x + width), bottom = Math.round(y + height);
        int r = Math.min(Math.round(radius), Math.min((right - left) / 2, (bottom - top) / 2));
        if (r <= 0) { graphics.fill(left, top, right, bottom, color); return; }
        graphics.fill(left, top + r, right, bottom - r, color);
        for (int row = 0; row < r; row++) {
            double dy = r - row - 0.5;
            int inset = Math.max(0, (int) Math.ceil(r - Math.sqrt(r * r - dy * dy)));
            graphics.fill(left + inset, top + row, right - inset, top + row + 1, color);
            graphics.fill(left + inset, bottom - row - 1, right - inset, bottom - row, color);
        }
    }

    @Override public void text(String text, float x, float y, float size, int color) {
        graphics.pose().pushMatrix();
        graphics.pose().translate(x, y);
        graphics.pose().scale(size / 9f, size / 9f);
        graphics.text(font, text, 0, 0, color, false);
        graphics.pose().popMatrix();
    }

    @Override public void clip(float x, float y, float width, float height) {
        graphics.enableScissor((int) x, (int) y, (int) Math.ceil(x + width), (int) Math.ceil(y + height));
    }
    @Override public void unclip() { graphics.disableScissor(); }
}
