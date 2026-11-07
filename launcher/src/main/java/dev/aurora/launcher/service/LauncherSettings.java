package dev.aurora.launcher.service;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Locale;
import java.util.Objects;
import java.util.Properties;

/** Non-secret preferences. Authentication tokens belong in the account store, never this file. */
public record LauncherSettings(Path instancePath, Path javaPath, String clientId,
                               int memoryMb, boolean closeOnLaunch) {
    public static final int DEFAULT_MEMORY_MB = 4096;
    public static final int MIN_MEMORY_MB = 1024;
    public static final int MAX_MEMORY_MB = 32768;

    public LauncherSettings {
        instancePath = Objects.requireNonNull(instancePath, "instancePath").toAbsolutePath().normalize();
        javaPath = javaPath == null ? null : javaPath.toAbsolutePath().normalize();
        clientId = clientId == null ? "" : clientId.strip();
        if (clientId.length() > 512 || clientId.chars().anyMatch(Character::isISOControl)) {
            throw new IllegalArgumentException("Microsoft-appens klient-ID är ogiltigt");
        }
        if (memoryMb < MIN_MEMORY_MB || memoryMb > MAX_MEMORY_MB) {
            throw new IllegalArgumentException("Minnet måste vara mellan 1024 och 32768 MB");
        }
    }

    /** A null Java path means automatic runtime selection by the game installer/controller. */
    public static LauncherSettings defaults() {
        return new LauncherSettings(defaultInstanceDirectory(), null, "", DEFAULT_MEMORY_MB, false);
    }

    public static Path defaultFile() {
        return Path.of(System.getProperty("user.home"), ".aurora-launcher", "settings.properties");
    }

    /** Aurora owns a separate instance; it does not silently overwrite the user's .minecraft directory. */
    public static Path defaultInstanceDirectory() {
        String os = System.getProperty("os.name", "").toLowerCase(Locale.ROOT);
        Path home = Path.of(System.getProperty("user.home"));
        if (os.contains("win")) {
            String appData = System.getenv("APPDATA");
            Path root = appData == null || appData.isBlank() ? home.resolve("AppData/Roaming") : Path.of(appData);
            return root.resolve("AuroraLauncher/instances/default");
        }
        if (os.contains("mac")) return home.resolve("Library/Application Support/AuroraLauncher/instances/default");
        return home.resolve(".local/share/aurora-launcher/instances/default");
    }

    public static LauncherSettings load(Path file) throws IOException {
        Objects.requireNonNull(file, "file");
        if (!Files.exists(file)) return defaults();
        if (!Files.isRegularFile(file)) throw new IOException("Inställningsfilen är ingen vanlig fil");
        if (Files.size(file) > 256 * 1024) throw new IOException("Inställningsfilen är för stor");
        Properties values = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            values.load(reader);
        } catch (IllegalArgumentException malformed) {
            throw new IOException("Inställningsfilen innehåller ogiltiga tecken", malformed);
        }
        try {
            LauncherSettings fallback = defaults();
            String instance = values.getProperty("instancePath", fallback.instancePath().toString()).strip();
            String java = values.getProperty("javaPath", "").strip();
            String memory = values.getProperty("memoryMb", Integer.toString(DEFAULT_MEMORY_MB)).strip();
            String close = values.getProperty("closeOnLaunch", "false").strip();
            if (!close.equalsIgnoreCase("true") && !close.equalsIgnoreCase("false")) {
                throw new IllegalArgumentException("closeOnLaunch måste vara true eller false");
            }
            return new LauncherSettings(instance.isBlank() ? fallback.instancePath() : Path.of(instance),
                java.isBlank() ? null : Path.of(java), values.getProperty("clientId", ""),
                Integer.parseInt(memory), Boolean.parseBoolean(close));
        } catch (IllegalArgumentException error) {
            throw new IOException("Auroras inställningar kunde inte läsas: " + error.getMessage(), error);
        }
    }

    /** Replace the whole file atomically, keeping the old preferences if writing fails. */
    public void save(Path file) throws IOException {
        Path destination = Objects.requireNonNull(file, "file").toAbsolutePath().normalize();
        Files.createDirectories(destination.getParent());
        Properties values = new Properties();
        values.setProperty("formatVersion", "1");
        values.setProperty("instancePath", instancePath.toString());
        values.setProperty("javaPath", javaPath == null ? "" : javaPath.toString());
        values.setProperty("clientId", clientId);
        values.setProperty("memoryMb", Integer.toString(memoryMb));
        values.setProperty("closeOnLaunch", Boolean.toString(closeOnLaunch));
        Path temporary = Files.createTempFile(destination.getParent(), ".aurora-settings-", ".tmp");
        try {
            try (Writer writer = Files.newBufferedWriter(temporary, StandardCharsets.UTF_8)) {
                values.store(writer, "Aurora Launcher preferences; no passwords or tokens");
            }
            try {
                Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }
}
