package dev.aurora;

import dev.aurora.core.*;
import dev.aurora.ui.*;
import java.nio.file.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Run with Java assertions enabled. No game installation or external test libraries required. */
public final class CoreTest {
    private static int checks;
    private static void check(boolean condition, String message) {
        checks++;
        if (!condition) throw new AssertionError(message);
    }

    public static void main(String[] args) throws Exception {
        ModuleRegistry registry = ModuleRegistry.catalog();
        check(registry.all().size() == 10, "Catalog size");
        for (Category category : Category.values()) check(!registry.search(category, "").isEmpty(), category.label());
        check(registry.search(Category.COMBAT, "  zOoM  ").getFirst().id().equals("zoom"), "Global case-insensitive search");
        check(registry.search(Category.COMBAT, "durability").size() == 1, "Description search");
        check(registry.search(Category.COMBAT, "unknown module").isEmpty(), "No results");
        try { registry.add(registry.get("zoom")); throw new AssertionError("Duplicate accepted"); }
        catch (IllegalArgumentException expected) { checks++; }
        ClientModule zoom = registry.get("zoom");
        try { zoom.setEnabled(true); throw new AssertionError("Unimplemented module enabled"); }
        catch (IllegalStateException expected) { checks++; }
        AtomicInteger effect = new AtomicInteger();
        zoom.bind(new ClientModule.Effect() {
            public void enable() { effect.incrementAndGet(); }
            public void disable() { effect.decrementAndGet(); }
        });
        zoom.setEnabled(true); zoom.setEnabled(true);
        check(effect.get() == 1, "Enabling is idempotent");
        zoom.setEnabled(false);
        check(effect.get() == 0, "Disable restores effect");
        Path dir = Files.createTempDirectory("aurora-test-");
        try {
            Path path = dir.resolve("config/aurora.properties");
            ClientConfig config = ClientConfig.load(path);
            check(config.menuKey() == 344, "Right Shift default");
            config.menuKey(82); config.reducedMotion(true);
            zoom.setEnabled(true); config.remember(zoom); config.save(path); config.save(path);
            ClientConfig loaded = ClientConfig.load(path);
            check(loaded.menuKey() == 82 && loaded.reducedMotion(), "Settings round trip");
            check(loaded.enabledModules().contains("zoom"), "Module settings round trip");
            Files.writeString(path, "menu.key=invalid\nmodules.enabled=zoom,../invalid\n");
            check(ClientConfig.load(path).menuKey() == 344, "Invalid binding fallback");
            check(ClientConfig.load(path).enabledModules().size() == 1, "Ignore invalid module ids");
            AtomicInteger saves = new AtomicInteger();
            ClickGui gui = new ClickGui(registry, config, saves::incrementAndGet);
            RecordingCanvas canvas = new RecordingCanvas();
            // A 720x460 viewport places the panel at (20,20).
            gui.render(canvas, 720, 460, 0, 0, 1);
            check(canvas.clipDepth == 0 && canvas.rectangles > 10, "Render and balanced clipping");
            check(gui.click(220, 100, 0), "Focus search");
            for (char c : "zoom".toCharArray()) gui.charTyped(c);
            check(gui.visible().size() == 1 && gui.visible().getFirst() == zoom, "Search input");
            gui.keyPressed(259);
            check(gui.query().equals("zoo"), "Backspace");
            gui.charTyped(0x1f319); gui.keyPressed(259);
            check(gui.query().equals("zoo"), "Unicode backspace");
            check(gui.click(220, 390, 0) && gui.rebinding(), "Start key binding");
            gui.keyPressed(256);
            check(!gui.rebinding() && config.menuKey() == 82, "Escape cancels binding");
            gui.click(220, 390, 0); gui.keyPressed(344);
            check(config.menuKey() == 344 && saves.get() == 1, "Binding update persisted");
            gui.click(50, 216, 0); // Render category.
            check(gui.selected() == Category.RENDER && gui.query().isEmpty(), "Category selection resets query");
            gui.click(220, 140, 0); // Zoom, which was enabled above.
            check(!zoom.enabled() && effect.get() == 0 && saves.get() == 2, "Module toggle and persistence");
            gui.click(50, 130, 0); // Combat category has no game bindings yet.
            gui.click(220, 140, 0);
            check(gui.status().contains("not installed"), "Unavailable module is visibly blocked");
            gui.scroll(-1000); gui.render(canvas, 720, 460, 0, 0, -1);
            check(canvas.clipDepth == 0, "Scroll and negative frame delta safe");
        } finally {
            try (var files = Files.walk(dir)) {
                for (Path file : files.sorted(java.util.Comparator.reverseOrder()).toList()) Files.delete(file);
            }
        }
        System.out.println("PASS: " + checks + " core, configuration and GUI checks");
    }

    private static final class RecordingCanvas implements Canvas {
        int rectangles, clipDepth;
        public void roundedRect(float x, float y, float w, float h, float r, int color) {
            if (!Float.isFinite(x + y + w + h) || w < 0 || h < 0) throw new AssertionError("Invalid rectangle");
            rectangles++;
        }
        public void text(String value, float x, float y, float size, int color) { }
        public void clip(float x, float y, float w, float h) { clipDepth++; }
        public void unclip() { if (--clipDepth < 0) throw new AssertionError("Unbalanced clipping"); }
    }
}
