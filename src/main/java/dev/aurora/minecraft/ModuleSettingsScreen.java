package dev.aurora.minecraft;

import com.mojang.blaze3d.platform.InputConstants;
import dev.aurora.core.ClientConfig;
import dev.aurora.core.ClientModule;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.IntConsumer;
import java.util.function.Supplier;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.KeyEvent;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;

public final class ModuleSettingsScreen extends Screen {
    public static final Set<String> HUD_MODULES = Set.of("fps_counter", "coordinates", "biome_info", "block_info", "attack_cooldown", "armor_status");
    public static final int[] COLORS = {0xff9b8aff, 0xff77caff, 0xff8cdea4, 0xffffce7a, 0xffff7f8c, 0xffff96d1, 0xffffffff};
    private final Screen parent;
    private final AuroraClient client;
    private final ClientModule module;
    private final List<Row> rows = new ArrayList<>();
    private IntConsumer capturing;
    private float scroll;
    private String status = "Changes are saved automatically";

    public ModuleSettingsScreen(Screen parent, AuroraClient client, ClientModule module) {
        super(Component.literal(module.name() + " settings"));
        this.parent = parent; this.client = client; this.module = module;
    }

    @Override protected void init() {
        rows.clear();
        row(() -> "Enabled: " + (module.enabled() ? "On" : "Off"), () -> { module.setEnabled(!module.enabled()); client.config().remember(module); });
        row(() -> "Hotkey: " + keyName(client.config().number(module.id(), "key", 0)), () -> capturing = key -> {
            if (key != 0 && key == client.config().menuKey()) throw new IllegalArgumentException("This key already opens the menu");
            for (var other : client.modules().all()) {
                if (other != module && key != 0 && client.config().number(other.id(), "key", 0) == key)
                    throw new IllegalArgumentException("Key is used by " + other.name());
            }
            client.config().setting(module.id(), "key", Integer.toString(key));
        });
        if (HUD_MODULES.contains(module.id())) {
            flag("Background", "background", true);
            flag("Text shadow", "text_shadow", false);
            row(() -> "Accent: #" + Integer.toHexString(client.config().number(module.id(), "accent", COLORS[0]) & 0xffffff), () -> {
                int current = client.config().number(module.id(), "accent", COLORS[0]);
                int next = 0;
                for (int i = 0; i < COLORS.length; i++) if (COLORS[i] == current) next = (i + 1) % COLORS.length;
                client.config().setting(module.id(), "accent", Integer.toString(COLORS[next]));
            });
            row(() -> "Reset HUD position", () -> client.config().resetHudPosition(module.id()));
        }
        if (module.id().equals("zoom")) {
            row(() -> "Zoom: " + client.config().number("zoom", "factor", 4) + "x", () -> {
                int factor = client.config().number("zoom", "factor", 4);
                client.config().setting("zoom", "factor", Integer.toString(factor == 2 ? 4 : factor == 4 ? 8 : 2));
            });
            flag("Smooth zoom", "smooth", true);
        }
        if (module.id().equals("chat_timestamps")) flag("Show seconds", "seconds", false);
    }

    private void row(Supplier<String> label, Runnable action) { rows.add(new Row(label, action)); }
    private void flag(String label, String key, boolean defaultValue) {
        row(() -> label + ": " + (client.config().flag(module.id(), key, defaultValue) ? "On" : "Off"),
            () -> client.config().setting(module.id(), key, Boolean.toString(!client.config().flag(module.id(), key, defaultValue))));
    }
    private String keyName(int key) { return key == 0 ? "Unbound" : InputConstants.Type.KEYBOARD.getOrCreate(key).getDisplayName().getString(); }
    private int panelWidth() { return Math.min(440, width - 24); }
    private int panelHeight() { return Math.min(360, height - 24); }
    private int left() { return (width - panelWidth()) / 2; }
    private int top() { return (height - panelHeight()) / 2; }
    private void clampScroll() { scroll = Math.clamp(scroll, 0, Math.max(0, rows.size() * 34 - (panelHeight() - 112))); }

    @Override public void extractRenderState(GuiGraphicsExtractor graphics, int mouseX, int mouseY, float partialTick) {
        MinecraftCanvas canvas = new MinecraftCanvas(graphics, font);
        int x = left(), y = top(), w = panelWidth(), h = panelHeight();
        canvas.roundedRect(0, 0, width, height, 0, 0xcc080a13);
        canvas.roundedRect(x, y, w, h, 12, 0xff171b2c);
        canvas.text(module.name(), x + 16, y + 17, 16, 0xffedf1ff);
        canvas.text("MODULE SETTINGS", x + 16, y + 40, 8, 0xff9b8aff);
        canvas.roundedRect(x + w - 56, y + 16, 42, 24, 6, 0xff30364e);
        canvas.text("Back", x + w - 48, y + 24, 9, 0xffedf1ff);
        clampScroll();
        canvas.clip(x + 12, y + 64, w - 24, h - 112);
        for (int i = 0; i < rows.size(); i++) {
            float rowY = y + 64 + i * 34 - scroll;
            canvas.roundedRect(x + 12, rowY, w - 24, 28, 6, 0xff252b40);
            canvas.text(rows.get(i).label.get(), x + 22, rowY + 9, 9, 0xffedf1ff);
        }
        canvas.unclip();
        String message = capturing == null ? status : "Press key / Backspace: clear / Esc: cancel";
        canvas.text(font.plainSubstrByWidth(message, w - 24), x + 12, y + h - 32, 8, 0xffb5accf);
        canvas.text("Scroll for more settings", x + 12, y + h - 16, 8, 0xff8a93ad);
    }

    @Override public boolean mouseClicked(MouseButtonEvent event, boolean doubleClick) {
        if (capturing != null || event.button() != InputConstants.MOUSE_BUTTON_LEFT) return true;
        double x = event.x() - left(), y = event.y() - top();
        if (x >= panelWidth() - 56 && x < panelWidth() - 14 && y >= 16 && y < 40) { onClose(); return true; }
        if (x >= 12 && x < panelWidth() - 12 && y >= 64 && y < panelHeight() - 48) {
            int index = (int) ((y - 64 + scroll) / 34);
            if (index >= 0 && index < rows.size() && (y - 64 + scroll) % 34 < 28) {
                try { rows.get(index).action.run(); if (capturing == null) client.save(); status = "Saved"; }
                catch (RuntimeException error) { status = error.getMessage() == null ? "Could not change setting" : error.getMessage(); }
            }
            return true;
        }
        return super.mouseClicked(event, doubleClick);
    }

    @Override public boolean mouseScrolled(double x, double y, double horizontal, double vertical) {
        scroll -= (float) vertical * 24; clampScroll(); return true;
    }
    @Override public boolean keyPressed(KeyEvent event) {
        if (capturing != null) {
            if (event.key() == ClientConfig.ESCAPE) { capturing = null; return true; }
            if (event.key() < 4 || event.key() > 511) return true;
            try {
                capturing.accept(event.key() == ClientConfig.BACKSPACE ? 0 : event.key());
                client.save(); capturing = null; status = "Hotkey saved";
            } catch (RuntimeException error) { status = error.getMessage(); capturing = null; }
            return true;
        }
        return super.keyPressed(event);
    }
    @Override public void onClose() { minecraft.gui.setScreen(parent); }
    @Override public boolean isPauseScreen() { return false; }
    private record Row(Supplier<String> label, Runnable action) { }
}
