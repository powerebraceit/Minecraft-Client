package dev.aurora.launcher.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.Properties;

/** Preferences tests use a temporary directory and never write the actual user's launcher settings. */
public final class LauncherSettingsCheck {
    private static int checks;
    public static void main(String[] args) throws Exception {
        Path folder = Files.createTempDirectory("aurora-settings-check-");
        try {
            Path file = folder.resolve("preferences/settings.properties");
            equal(LauncherSettings.defaults(), LauncherSettings.load(file));
            var settings = new LauncherSettings(folder.resolve("Minecraft åäö with spaces/../game"),
                folder.resolve("Java Runtime/bin/java"), "  example-client-id  ", 6144, true);
            settings.save(file);equal(settings, LauncherSettings.load(file));
            equal("example-client-id", settings.clientId());
            require(settings.instancePath().isAbsolute() && !settings.instancePath().toString().contains(".."));
            var properties = new Properties();
            try (var reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {properties.load(reader);}
            equal(java.util.Set.of("formatVersion", "instancePath", "javaPath", "clientId", "memoryMb", "closeOnLaunch"), properties.stringPropertyNames());
            require(!properties.containsKey("accessToken") && !properties.containsKey("refreshToken") && !properties.containsKey("password"));

            var automatic = new LauncherSettings(folder.resolve("automatic"), null, null, 4096, false);
            automatic.save(file);equal(automatic, LauncherSettings.load(file));equal("", automatic.clientId());
            require(LauncherSettings.defaultFile().getFileName().toString().equals("settings.properties"));
            require(!LauncherSettings.defaultInstanceDirectory().endsWith(".minecraft"));
            reject(() -> new LauncherSettings(folder, null, "id", 512, false));
            reject(() -> new LauncherSettings(folder, null, "id", 65536, false));
            reject(() -> new LauncherSettings(folder, null, "id\nInjected", 4096, false));
            reject(() -> new LauncherSettings(folder, null, "x".repeat(513), 4096, false));
            Files.writeString(file, "memoryMb=not-a-number\n", StandardCharsets.UTF_8);
            ioReject(() -> LauncherSettings.load(file));
            Files.writeString(file, "closeOnLaunch=maybe\n", StandardCharsets.UTF_8);
            ioReject(() -> LauncherSettings.load(file));
            Files.writeString(file, "clientId=\\uQQQQ\n", StandardCharsets.UTF_8);
            ioReject(() -> LauncherSettings.load(file));
            Path occupied = folder.resolve("occupied");Files.createDirectories(occupied);
            Path retained = occupied.resolve("keep.txt");Files.writeString(retained, "original");
            ioReject(() -> settings.save(occupied));equal("original", Files.readString(retained));
            try (var paths = Files.list(folder)) {
                require(paths.noneMatch(path -> path.getFileName().toString().startsWith(".aurora-settings-")));
            }
            System.out.println("PASS: LauncherSettingsCheck " + checks + " checks");
        } finally {
            try (var paths = Files.walk(folder)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
    private static void equal(Object expected, Object actual) {
        if (!java.util.Objects.equals(expected, actual)) throw new AssertionError("Settings value mismatch");checks++;
    }
    private static void require(boolean value) {if (!value) throw new AssertionError("Settings check failed");checks++;}
    private static void reject(Runnable action) {
        try {action.run();}catch (IllegalArgumentException expected) {checks++;return;}
        throw new AssertionError("Invalid settings accepted");
    }
    private interface IoAction {void run() throws IOException;}
    private static void ioReject(IoAction action) throws IOException {
        try {action.run();}catch (IOException expected) {checks++;return;}
        throw new AssertionError("Invalid settings file accepted");
    }
}
