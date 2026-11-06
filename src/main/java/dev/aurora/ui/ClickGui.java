package dev.aurora.ui;

import dev.aurora.core.Category;
import dev.aurora.core.ClientConfig;
import dev.aurora.core.ClientModule;
import dev.aurora.core.ModuleRegistry;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.IntFunction;

/** Animated GUI state and hit testing, independent of changing Minecraft rendering APIs. */
public final class ClickGui {
    private static final int WHITE = 0xffedf1ff, MUTED = 0xff8a93ad, ACCENT = 0xff9b8aff;
    private final ModuleRegistry registry;
    private final ClientConfig config;
    private final Runnable persist;
    private final IntFunction<String> keyName;
    private final Map<String, Float> toggles = new HashMap<>();
    private Category selected = Category.COMBAT;
    private String query = "";
    private boolean searchFocused, rebinding;
    private float progress, scroll, x, y, width = 680, height = 420;
    private String status = "";

    public ClickGui(ModuleRegistry registry, ClientConfig config, Runnable persist) {
        this(registry, config, persist, key -> key == ClientConfig.RIGHT_SHIFT ? "Right Shift" : "Key " + key);
    }

    public ClickGui(ModuleRegistry registry, ClientConfig config, Runnable persist, IntFunction<String> keyName) {
        this.registry = Objects.requireNonNull(registry);
        this.config = Objects.requireNonNull(config);
        this.persist = Objects.requireNonNull(persist);
        this.keyName = Objects.requireNonNull(keyName);
    }

    public Category selected() { return selected; }
    public String query() { return query; }
    public boolean rebinding() { return rebinding; }
    public String status() { return status; }
    public List<ClientModule> visible() { return registry.search(selected, query); }

    public void render(Canvas canvas, int screenWidth, int screenHeight, double mouseX, double mouseY, float deltaSeconds) {
        float dt = Math.max(0, Math.min(deltaSeconds, 0.1f));
        progress = approach(progress, 1, dt, config.reducedMotion());
        width = Math.min(680, Math.max(320, screenWidth - 24));
        height = Math.min(420, Math.max(240, screenHeight - 24));
        x = (screenWidth - width) / 2;
        y = (screenHeight - height) / 2 + (1 - progress) * 18;
        canvas.roundedRect(0, 0, screenWidth, screenHeight, 0, 0x99080a13);
        canvas.roundedRect(x - 1, y - 1, width + 2, height + 2, 17, 0xff32374e);
        canvas.roundedRect(x, y, width, height, 16, 0xff111522);
        canvas.roundedRect(x, y, 148, height, 16, 0xff171b2c);
        canvas.text("AURORA", x + 20, y + 24, 20, WHITE);
        canvas.text("MAKE IT YOURS", x + 20, y + 50, 8, ACCENT);
        for (Category category : Category.values()) {
            float cy = y + categoryTop() + category.ordinal() * categoryStep();
            boolean active = category == selected;
            if (active || inside(mouseX, mouseY, x + 12, cy, 124, 34))
                canvas.roundedRect(x + 12, cy, 124, Math.min(34, categoryStep() - 2), 8, active ? 0xff343052 : 0xff22273b);
            if (active) canvas.roundedRect(x + 12, cy + 9, 3, 16, 1, ACCENT);
            canvas.text(category.label(), x + 27, cy + 11, 11, active ? WHITE : MUTED);
        }
        float left = x + 166, bodyWidth = width - 184;
        canvas.text(query.isBlank() ? selected.label() : "Search results", left, y + 22, 18, WHITE);
        canvas.text(visible().size() + " modules", left, y + 47, 9, MUTED);
        canvas.roundedRect(left + bodyWidth - 116, y + 19, 116, 24, 6, 0xff252b40);
        canvas.text(config.reducedMotion() ? "Animations: Off" : "Animations: On", left + bodyWidth - 108, y + 27, 9, WHITE);
        canvas.roundedRect(left, y + 67, bodyWidth, 32, 8, searchFocused ? 0xff30364e : 0xff202638);
        canvas.text(query.isEmpty() ? "Search all modules..." : ellipsis(query, bodyWidth, 11), left + 12, y + 78, 11, query.isEmpty() ? MUTED : WHITE);
        canvas.clip(left, y + 110, bodyWidth, height - 176);
        List<ClientModule> visible = visible();
        clampScroll();
        for (int i = 0; i < visible.size(); i++) {
            ClientModule module = visible.get(i);
            float cy = y + 110 + i * 66 - scroll;
            boolean hover = inside(mouseX, mouseY, left, cy, bodyWidth, 58);
            canvas.roundedRect(left, cy, bodyWidth, 58, 9, hover ? 0xff292f46 : 0xff1d2335);
            canvas.text(ellipsis(module.name(), bodyWidth - 64, 12), left + 12, cy + 12, 12, WHITE);
            canvas.text(ellipsis(module.available() ? module.description() : "Awaiting Minecraft 26.3 integration", bodyWidth - 26, 8), left + 12, cy + 34, 8, MUTED);
            float amount = approach(toggles.getOrDefault(module.id(), 0f), module.enabled() ? 1 : 0, dt, config.reducedMotion());
            toggles.put(module.id(), amount);
            canvas.roundedRect(left + bodyWidth - 44, cy + 12, 30, 16, 8, mix(0xff42495e, ACCENT, amount));
            canvas.roundedRect(left + bodyWidth - 42 + amount * 14, cy + 14, 12, 12, 6, module.available() ? WHITE : MUTED);
        }
        if (visible.isEmpty()) canvas.text("No modules found", left + 12, y + 126, 11, MUTED);
        canvas.unclip();
        canvas.roundedRect(left, y + height - 56, bodyWidth, 26, 7, rebinding ? 0xff41355c : 0xff252b40);
        String binding = keyName.apply(config.menuKey());
        canvas.text(ellipsis(rebinding ? "Press a key (Esc cancels)" : "Menu key: " + binding + "  /  Change", bodyWidth - 18, 9), left + 9, y + height - 47, 9, WHITE);
        canvas.text(ellipsis(status.isEmpty() ? "Esc to close  /  Scroll to browse" : status, bodyWidth, 8), left, y + height - 18, 8, MUTED);
    }

    public boolean click(double mx, double my, int button) {
        if (button != 0 || rebinding) return false;
        float left = x + 166, bodyWidth = width - 184;
        searchFocused = inside(mx, my, left, y + 67, bodyWidth, 32);
        if (searchFocused) return true;
        if (inside(mx, my, left + bodyWidth - 116, y + 19, 116, 24)) {
            config.reducedMotion(!config.reducedMotion());
            try { persist.run(); status = "Animation preference saved"; }
            catch (RuntimeException e) { status = "Preference changed; settings could not be saved"; }
            return true;
        }
        for (Category category : Category.values()) {
            if (inside(mx, my, x + 12, y + categoryTop() + category.ordinal() * categoryStep(), 124, Math.min(34, categoryStep() - 2))) {
                selected = category; query = ""; scroll = 0; return true;
            }
        }
        if (inside(mx, my, left, y + height - 56, bodyWidth, 26)) { rebinding = true; return true; }
        if (inside(mx, my, left, y + 110, bodyWidth, height - 176)) {
            int index = (int) ((my - y - 110 + scroll) / 66);
            float inRow = (float) ((my - y - 110 + scroll) % 66);
            List<ClientModule> list = visible();
            if (index >= 0 && index < list.size() && inRow < 58) {
                ClientModule module = list.get(index);
                if (!module.available()) { status = "Game integration not installed"; return true; }
                try {
                    module.setEnabled(!module.enabled()); config.remember(module);
                } catch (RuntimeException e) { status = "Could not update module: " + module.name(); return true; }
                try { persist.run(); status = module.name() + (module.enabled() ? " enabled" : " disabled"); }
                catch (RuntimeException e) { status = "Module changed; settings could not be saved"; }
                return true;
            }
        }
        return false;
    }

    /** true consumes the key; false lets the Minecraft screen handle it (including Escape). */
    public boolean keyPressed(int key) {
        if (rebinding) {
            if (key == ClientConfig.ESCAPE) { rebinding = false; return true; }
            if (key < 4 || key > 511) return true;
            config.menuKey(key); rebinding = false;
            try { persist.run(); status = "Menu key updated"; }
            catch (RuntimeException e) { status = "Key changed; settings could not be saved"; }
            return true;
        }
        if (searchFocused && key == ClientConfig.BACKSPACE) {
            if (!query.isEmpty()) query = query.substring(0, query.offsetByCodePoints(query.length(), -1));
            scroll = 0; return true;
        }
        if (key == ClientConfig.ESCAPE && searchFocused) { searchFocused = false; return true; }
        return searchFocused;
    }

    public boolean charTyped(int codePoint) {
        if (!searchFocused || rebinding || Character.isISOControl(codePoint) || query.codePointCount(0, query.length()) >= 64) return false;
        query += new String(Character.toChars(codePoint)); scroll = 0; return true;
    }

    public void scroll(double amount) { scroll -= (float) amount * 24; clampScroll(); }
    private float categoryTop() { return height < 340 ? 76 : 96; }
    private float categoryStep() { return Math.min(43, (height - categoryTop() - 16) / 5); }
    private void clampScroll() { scroll = Math.max(0, Math.min(scroll, Math.max(0, visible().size() * 66 - (height - 176)))); }
    private static boolean inside(double mx, double my, float x, float y, float w, float h) { return mx >= x && my >= y && mx < x + w && my < y + h; }
    private static float approach(float from, float to, float dt, boolean instant) { return instant ? to : from + (to - from) * (float) -Math.expm1(-14 * dt); }
    private static String ellipsis(String text, float width, int size) {
        int max = Math.max(1, (int) (width / (size * 0.62f)));
        return text.length() <= max ? text : text.substring(0, Math.max(0, max - 3)) + "...";
    }
    private static int mix(int a, int b, float t) {
        int r = (int) (((a >> 16) & 255) * (1 - t) + ((b >> 16) & 255) * t);
        int g = (int) (((a >> 8) & 255) * (1 - t) + ((b >> 8) & 255) * t);
        int bl = (int) ((a & 255) * (1 - t) + (b & 255) * t);
        return 0xff000000 | r << 16 | g << 8 | bl;
    }
}
