package dev.aurora.launcher.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Standalone checks: no Minecraft, network, credentials or executable JARs are needed. */
public final class ModInstallerCheck {
    private static int checks;
    private ModInstallerCheck() { }

    public static void main(String[] args) throws Exception {
        installsVerifiedArtifactAndIsIdempotent();
        replacementUsesActualFabricIdentityAndPreservesUnrelatedMods();
        hashMismatchMakesNoInstanceChanges();
        incorrectMetadataIsRejectedBeforeChanges();
        unrelatedDestinationCollisionDoesNotMoveOldMods();
        failedPublishRestoresPreviousAurora();
        symbolicModsDirectoryIsRejected();
        nestedFabricIdCannotMisidentifyAnUnrelatedMod();
        if (List.of(args).contains("--bundled")) bundledResourceIsValid();
        System.out.println("PASS: " + checks + " Aurora installer checks");
    }

    private static void installsVerifiedArtifactAndIsIdempotent() throws Exception {
        withTemp(root -> {
            Path instance = Files.createDirectory(root.resolve("instance"));
            Path source = jar(root.resolve("source.jar"), metadata("aurora", ModInstaller.VERSION, "26.3"));
            byte[] expected = Files.readAllBytes(source);
            var installer = new ModInstaller(source, ModInstaller.sha256(expected));
            var progress = new ArrayList<String>();
            var first = installer.install(instance, progress::add);
            require(Files.isRegularFile(first.destination()), "The selected instance receives the JAR");
            require(java.util.Arrays.equals(expected, Files.readAllBytes(first.destination())), "Installed bytes match the verified source");
            require(first.backups().isEmpty() && !first.alreadyInstalled(), "A fresh install needs no backup");
            require(first.version().equals(ModInstaller.VERSION) && !progress.isEmpty(), "Version and progress are available to the UI");
            var second = installer.install(instance);
            require(second.alreadyInstalled() && second.backups().isEmpty(), "An identical second install is idempotent");
            require(noStagingFiles(instance.resolve("mods")), "No partial files remain");
        });
        checks++;
    }

    private static void replacementUsesActualFabricIdentityAndPreservesUnrelatedMods() throws Exception {
        withTemp(root -> {
            Path instance = Files.createDirectory(root.resolve("instance"));
            Path mods = Files.createDirectory(instance.resolve("mods"));
            Path source = jar(root.resolve("source.jar"), metadata("aurora", ModInstaller.VERSION, "26.3"));
            Path old = jar(mods.resolve("renamed-personal-mod.jar"), metadata("aurora", "1.8.0", "26.3"));
            Path second = jar(mods.resolve("old-aurora.jar"), metadata("aurora", "1.9.0", "26.3"));
            Path unrelated = jar(mods.resolve("aurora-sounding-but-unrelated.jar"), metadata("another_mod", "9", "26.3"));
            Path invalid = mods.resolve("custom-library.jar");Files.writeString(invalid, "Unrelated non-zip data");
            byte[] oldBytes = Files.readAllBytes(old), secondBytes = Files.readAllBytes(second), unrelatedBytes = Files.readAllBytes(unrelated);
            var result = installer(source).install(instance);
            require(result.backups().size() == 2 && !Files.exists(old) && !Files.exists(second), "Only actual Aurora Fabric ids are replaced");
            require(result.backups().stream().anyMatch(p -> sameBytes(p, oldBytes)) && result.backups().stream().anyMatch(p -> sameBytes(p, secondBytes)), "Both original files remain in recoverable backups");
            require(java.util.Arrays.equals(unrelatedBytes, Files.readAllBytes(unrelated)), "A similar filename is not an Aurora identity");
            require(Files.readString(invalid).equals("Unrelated non-zip data"), "Unrecognized JARs remain untouched");
        });
        checks++;
    }

    private static void hashMismatchMakesNoInstanceChanges() throws Exception {
        withTemp(root -> {
            Path instance = Files.createDirectory(root.resolve("instance"));
            Path source = jar(root.resolve("source.jar"), metadata("aurora", ModInstaller.VERSION, "26.3"));
            expectIOException(() -> new ModInstaller(source, "0".repeat(64)).install(instance));
            require(!Files.exists(instance.resolve("mods")), "Source verification precedes any instance mutation");
        });
        checks++;
    }

    private static void incorrectMetadataIsRejectedBeforeChanges() throws Exception {
        withTemp(root -> {
            Path instance = Files.createDirectory(root.resolve("instance"));
            Path source = jar(root.resolve("source.jar"), metadata("aurora", "1.9.0", "26.3"));
            expectIOException(() -> installer(source).install(instance));
            require(!Files.exists(instance.resolve("mods")), "A different Aurora version is not silently installed");
            jar(source, metadata("aurora", ModInstaller.VERSION, "1.21"));
            expectIOException(() -> installer(source).install(instance));
            require(!Files.exists(instance.resolve("mods")), "A different Minecraft target is not installed");
        });
        checks++;
    }

    private static void unrelatedDestinationCollisionDoesNotMoveOldMods() throws Exception {
        withTemp(root -> {
            Path instance = Files.createDirectory(root.resolve("instance"));Path mods = Files.createDirectory(instance.resolve("mods"));
            Path source = jar(root.resolve("source.jar"), metadata("aurora", ModInstaller.VERSION, "26.3"));
            Path old = jar(mods.resolve("aurora-old.jar"), metadata("aurora", "1.9.0", "26.3"));
            Path collision = jar(mods.resolve(ModInstaller.FILE_NAME), metadata("not_aurora", "1", "26.3"));
            byte[] original = Files.readAllBytes(collision);
            expectIOException(() -> installer(source).install(instance));
            require(Files.exists(old) && sameBytes(collision, original), "An unrelated collision is never overwritten and no old mod is moved");
            require(!Files.exists(mods.resolve(".aurora-backups")) && noStagingFiles(mods), "Collision detection happens before the transaction");
        });
        checks++;
    }

    private static void failedPublishRestoresPreviousAurora() throws Exception {
        withTemp(root -> {
            Path instance = Files.createDirectory(root.resolve("instance"));Path mods = Files.createDirectory(instance.resolve("mods"));
            Path source = jar(root.resolve("source.jar"), metadata("aurora", ModInstaller.VERSION, "26.3"));
            Path old = jar(mods.resolve("old-client.jar"), metadata("aurora", "1.9.0", "26.3"));byte[] original = Files.readAllBytes(old);
            var broken = new ModInstaller(source, ModInstaller.sha256(Files.readAllBytes(source)), (from, to) -> {
                if (from.getFileName().toString().endsWith(".part")) throw new IOException("Simulated atomic publish failure");
                Files.move(from, to);
            });
            expectIOException(() -> broken.install(instance));
            require(sameBytes(old, original), "An old Aurora file is restored byte for byte when publishing fails");
            require(!Files.exists(mods.resolve(ModInstaller.FILE_NAME)) && noStagingFiles(mods), "A failed replacement leaves no active partial new version");
        });
        checks++;
    }

    private static void symbolicModsDirectoryIsRejected() throws Exception {
        withTemp(root -> {
            Path instance = Files.createDirectory(root.resolve("instance"));Path elsewhere = Files.createDirectory(root.resolve("external"));
            Path source = jar(root.resolve("source.jar"), metadata("aurora", ModInstaller.VERSION, "26.3"));
            try { Files.createSymbolicLink(instance.resolve("mods"), elsewhere); }
            catch (UnsupportedOperationException | SecurityException | java.nio.file.FileSystemException unsupported) {
                System.out.println("SKIP: symbolic links unavailable on this test host");return;
            }
            expectIOException(() -> installer(source).install(instance));
            try (var files = Files.list(elsewhere)) {require(files.findAny().isEmpty(), "The installer cannot escape through a mods symlink");}
        });
        checks++;
    }

    private static void nestedFabricIdCannotMisidentifyAnUnrelatedMod() throws Exception {
        withTemp(root -> {
            Path instance = Files.createDirectory(root.resolve("instance"));Path mods = Files.createDirectory(instance.resolve("mods"));
            Path source = jar(root.resolve("source.jar"), metadata("aurora", ModInstaller.VERSION, "26.3"));
            String unrelatedMetadata = "{\"nested\":{\"id\":\"aurora\"},\"id\":\"unrelated\",\"version\":\"1\"}";
            Path unrelated = jar(mods.resolve("unrelated.jar"), unrelatedMetadata);byte[] bytes = Files.readAllBytes(unrelated);
            var result = installer(source).install(instance);
            require(result.backups().isEmpty() && sameBytes(unrelated, bytes), "Only a root Fabric id identifies the owner of a mod");
        });
        checks++;
    }

    private static void bundledResourceIsValid() throws Exception {
        withTemp(root -> {
            Path instance = Files.createDirectory(root.resolve("instance"));
            var result = new ModInstaller().install(instance);
            require(result.version().equals(ModInstaller.VERSION) && Files.size(result.destination()) > 0, "The packaged launcher contains a verified installable Aurora release");
        });
        checks++;
    }

    private static ModInstaller installer(Path source) throws IOException {
        return new ModInstaller(source, ModInstaller.sha256(Files.readAllBytes(source)));
    }
    private static String metadata(String id, String version, String minecraft) {
        return "{\"schemaVersion\":1,\"id\":\"" + id + "\",\"version\":\"" + version
            + "\",\"depends\":{\"minecraft\":\"" + minecraft + "\",\"fabricloader\":\">=0.20.1\"}}";
    }
    private static Path jar(Path destination, String metadata) throws IOException {
        try (ZipOutputStream zip = new ZipOutputStream(Files.newOutputStream(destination))) {
            zip.putNextEntry(new ZipEntry("fabric.mod.json"));zip.write(metadata.getBytes(StandardCharsets.UTF_8));zip.closeEntry();
            zip.putNextEntry(new ZipEntry("content.txt"));zip.write("Test data, no executable classes".getBytes(StandardCharsets.UTF_8));zip.closeEntry();
        }
        return destination;
    }
    private static boolean sameBytes(Path path, byte[] expected) {
        try { return java.util.Arrays.equals(expected, Files.readAllBytes(path)); }
        catch (IOException error) { return false; }
    }
    private static boolean noStagingFiles(Path mods) throws IOException {
        try (var files = Files.list(mods)) {return files.noneMatch(p -> p.getFileName().toString().endsWith(".part"));}
    }
    private interface Work {void run(Path root) throws Exception;}
    private interface Action {void run() throws Exception;}
    private static void withTemp(Work work) throws Exception {
        Path root = Files.createTempDirectory("aurora-installer-check-");
        try { work.run(root); }
        finally {
            try (var paths = Files.walk(root)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }
    private static void expectIOException(Action action) throws Exception {
        try { action.run();throw new AssertionError("Expected IOException"); }
        catch (IOException expected) { }
    }
    private static void require(boolean condition, String message) {if (!condition) throw new AssertionError(message);}
}
