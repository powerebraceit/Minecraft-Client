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

/** No game dependency; the adapter supplies GLFW key codes. */
public final class ClientConfig {
    public static final int RIGHT_SHIFT = 344;
    private int menuKey = RIGHT_SHIFT;
    private boolean reducedMotion;
    private final Set<String> enabled = new HashSet<>();

    public int menuKey() { return menuKey; }
    public void menuKey(int key) {
        if (key < 32 || key > 348) throw new IllegalArgumentException("Unsupported menu key");
        menuKey = key;
    }
    public boolean reducedMotion() { return reducedMotion; }
    public void reducedMotion(boolean value) { reducedMotion = value; }
    public Set<String> enabledModules() { return Set.copyOf(enabled); }
    public void remember(ClientModule module) {
        if (module.enabled()) enabled.add(module.id()); else enabled.remove(module.id());
    }

    public static ClientConfig load(Path file) throws IOException {
        ClientConfig config = new ClientConfig();
        if (!Files.exists(file)) return config;
        Properties p = new Properties();
        try (Reader reader = Files.newBufferedReader(file)) { p.load(reader); }
        try { config.menuKey(Integer.parseInt(p.getProperty("menu.key", "344"))); }
        catch (IllegalArgumentException ignored) { /* A bad binding must not lock the user out. */ }
        config.reducedMotion(Boolean.parseBoolean(p.getProperty("ui.reducedMotion", "false")));
        for (String id : p.getProperty("modules.enabled", "").split(",")) {
            if (id.matches("[a-z][a-z0-9_]*")) config.enabled.add(id);
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
