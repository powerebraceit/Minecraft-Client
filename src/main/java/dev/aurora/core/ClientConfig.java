package dev.aurora.core;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.HashSet;
import java.util.Properties;
import java.util.Set;
import java.util.Map;
import java.util.HashMap;

/** SDL keyboard scancodes, matching Minecraft 26.3's InputConstants. */
public final class ClientConfig {
    public static final int RIGHT_SHIFT = 229;
    public static final int ESCAPE = 41;
    public static final int BACKSPACE = 42;
    private int menuKey = RIGHT_SHIFT;
    private boolean reducedMotion;
    private final Set<String> enabled = new HashSet<>();
    private final Map<String, HudLayout.Position> hudPositions = new HashMap<>();

    public int menuKey() { return menuKey; }
    public void menuKey(int key) {
        if (key < 4 || key > 511 || key == ESCAPE) throw new IllegalArgumentException("Unsupported menu key");
        menuKey = key;
    }
    public boolean reducedMotion() { return reducedMotion; }
    public void reducedMotion(boolean value) { reducedMotion = value; }
    public Set<String> enabledModules() { return Set.copyOf(enabled); }
    public HudLayout.Position hudPosition(String id) { return hudPositions.get(id); }
    public void resetHudPosition(String id) { hudPositions.remove(id); }
    public void hudPosition(String id, HudLayout.Position position) {
        if (!id.matches("[a-z][a-z0-9_]*")) throw new IllegalArgumentException("Invalid HUD id");
        hudPositions.put(id, java.util.Objects.requireNonNull(position));
    }
    public void remember(ClientModule module) {
        if (module.enabled()) enabled.add(module.id()); else enabled.remove(module.id());
    }

    public static ClientConfig load(Path file) throws IOException {
        ClientConfig config = new ClientConfig();
        if (!Files.exists(file)) return config;
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) { p.load(reader); }
        try { config.menuKey(Integer.parseInt(p.getProperty("menu.key", Integer.toString(RIGHT_SHIFT)))); }
        catch (IllegalArgumentException ignored) { /* A bad binding must not lock the user out. */ }
        config.reducedMotion(Boolean.parseBoolean(p.getProperty("ui.reducedMotion", "false")));
        for (String id : p.getProperty("modules.enabled", "").split(",")) {
            if (id.matches("[a-z][a-z0-9_]*")) config.enabled.add(id);
        }
        for (String key : p.stringPropertyNames()) {
            if (!key.matches("hud\\.[a-z][a-z0-9_]*\\.x")) continue;
            String id = key.substring(4, key.length() - 2);
            try {
                config.hudPosition(id, new HudLayout.Position(Double.parseDouble(p.getProperty(key)),
                    Double.parseDouble(p.getProperty("hud." + id + ".y", "NaN"))));
            } catch (IllegalArgumentException ignored) { /* Ignore only the malformed panel position. */ }
        }
        return config;
    }

    public void save(Path file) throws IOException {
        Path target = file.toAbsolutePath();
        Files.createDirectories(target.getParent());
        Properties p = new Properties();
        p.setProperty("menu.key", Integer.toString(menuKey));
        p.setProperty("ui.reducedMotion", Boolean.toString(reducedMotion));
        p.setProperty("modules.enabled", String.join(",", enabled.stream().sorted().toList()));
        hudPositions.forEach((id, position) -> {
            p.setProperty("hud." + id + ".x", Double.toString(position.x()));
            p.setProperty("hud." + id + ".y", Double.toString(position.y()));
        });
        Path tmp = Files.createTempFile(target.getParent(), "aurora-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(tmp)) { p.store(writer, "Aurora Client settings"); }
            try { Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); }
            catch (AtomicMoveNotSupportedException e) {
                Files.move(tmp, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally { Files.deleteIfExists(tmp); }
    }
}
