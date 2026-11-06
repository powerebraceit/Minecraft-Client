package dev.aurora.minecraft;

import com.mojang.blaze3d.platform.InputConstants;
import dev.aurora.ui.ClickGui;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.CharacterEvent;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class AuroraScreen extends Screen {
    private final AuroraClient client;
    private final ClickGui gui;
    private long lastFrame = System.nanoTime();

    public AuroraScreen(AuroraClient client) {
        super(Component.literal("Aurora Client"));
        this.client = client;
        gui = new ClickGui(client.modules(), client.config(), client::save,
            key -> InputConstants.Type.KEYBOARD.getOrCreate(key).getDisplayName().getString());
    }

    private float scale() { return Math.max(0.1f, Math.min(1, Math.min(width / 720f, height / 460f))); }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        long now = System.nanoTime();
        float delta = (now - lastFrame) / 1e9f;
        lastFrame = now;
        float scale = scale();
        graphics.pose().pushMatrix();
        graphics.pose().scale(scale, scale);
        gui.render(new MinecraftCanvas(graphics, font), Math.round(width / scale), Math.round(height / scale), mouseX / scale, mouseY / scale, delta);
        graphics.pose().popMatrix();
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (event.button() != InputConstants.MOUSE_BUTTON_LEFT) return super.mouseClicked(event, doubleClick);
        return gui.click(event.x() / scale(), event.y() / scale(), 0) || super.mouseClicked(event, doubleClick);
    }
    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        gui.scroll(vertical); return true;
    }
    @Override public boolean charTyped(CharacterEvent event) {
        return gui.charTyped(event.codepoint()) || super.charTyped(event);
    }
    @Override public boolean keyPressed(KeyEvent event) {
        // Bind capture and search get first refusal, so a newly bound key cannot close this screen immediately.
        if (gui.keyPressed(event.key())) return true;
        if (event.key() == client.config().menuKey()) { onClose(); return true; }
        return super.keyPressed(event);
    }
    @Override public boolean isPauseScreen() { return false; }
}
