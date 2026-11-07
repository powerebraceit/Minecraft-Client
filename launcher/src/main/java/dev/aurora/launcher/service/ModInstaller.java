package dev.aurora.launcher.service;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.zip.ZipEntry;
import java.util.zip.ZipFile;
import java.util.zip.ZipInputStream;

/**
 * Installs the verified, bundled Aurora mod into a user-selected instance.
 * No game, native library, JAR entry point or downloaded code is executed here.
 * Existing mods are identified by their top-level Fabric id, never just their name.
 */
public final class ModInstaller {
    public static final String VERSION = "1.10.0";
    public static final String FILE_NAME = "aurora-client-" + VERSION + ".jar";
    public static final String MINECRAFT_VERSION = "26.3";
    public static final String MINIMUM_LOADER = "0.20.1";
    private static final String RESOURCE = "/bundled/" + FILE_NAME;
    private static final int MAX_JAR_BYTES = 32 * 1024 * 1024;
    private static final int MAX_METADATA_BYTES = 1024 * 1024;
    private static final LinkOption[] NO_FOLLOW = {LinkOption.NOFOLLOW_LINKS};

    public record InstallResult(Path destination, List<Path> backups, String sha256,
                                String version, boolean alreadyInstalled) {
        public InstallResult { backups = List.copyOf(backups); }
    }

    @FunctionalInterface private interface Source { InputStream open() throws IOException; }
    @FunctionalInterface interface MoveOperation { void move(Path from, Path to) throws IOException; }
    private final Source source;
    private final String expectedHash;
    private final MoveOperation move;

    /** The build bundles the exact release JAR and its SHA-256 file into this launcher. */
    public ModInstaller() {
        source = () -> requiredResource(RESOURCE);
        expectedHash = null;
        move = ModInstaller::atomicMove;
    }

    /** Injectable local artifact, useful for offline validation and installer tests. */
    public ModInstaller(Path sourceJar, String expectedSha256) {
        this(sourceJar, expectedSha256, ModInstaller::atomicMove);
    }

    ModInstaller(Path sourceJar, String expectedSha256, MoveOperation move) {
        Path input = sourceJar.toAbsolutePath().normalize();
        source = () -> {
            rejectSymbolicLinks(input);
            if (!Files.isRegularFile(input, NO_FOLLOW)) throw new IOException("Modfilen saknas: " + input);
            return Files.newInputStream(input);
        };
        expectedHash = normalizedHash(expectedSha256);
        this.move = Objects.requireNonNull(move);
    }

    public InstallResult install(Path instance) throws IOException { return install(instance, ignored -> { }); }

    public InstallResult install(Path instance, Consumer<String> progress) throws IOException {
        Objects.requireNonNull(instance, "instance");
        Objects.requireNonNull(progress, "progress");
        progress.accept("Kontrollerar Aurora-filen…");
        byte[] artifact;
        try (InputStream input = source.open()) { artifact = readLimited(input, MAX_JAR_BYTES); }
        String expected = expectedHash == null ? bundledHash() : expectedHash;
        String actual = sha256(artifact);
        if (!actual.equals(expected)) throw new IOException("Aurora-filens kontrollsumma stämmer inte. Ingen installation har gjorts.");
        verifyBundledMetadata(readMetadata(artifact));

        // Validate the artifact before making any changes in the chosen game folder.
        Path root = instance.toAbsolutePath().normalize();
        rejectSymbolicLinks(root);
        if (!Files.isDirectory(root, NO_FOLLOW)) throw new IOException("Välj en befintlig Minecraft-spelmapp.");
        Path mods = root.resolve("mods");
        rejectSymbolicLinks(mods);
        if (Files.exists(mods, NO_FOLLOW) && !Files.isDirectory(mods, NO_FOLLOW))
            throw new IOException("Spelmappens mods är inte en mapp.");
        Files.createDirectories(mods);
        Path destination = mods.resolve(FILE_NAME);
        List<Path> existing = findAuroraMods(mods);
        if (Files.exists(destination, NO_FOLLOW) && !existing.contains(destination))
            throw new IOException("En annan fil har redan namnet " + FILE_NAME + ". Den har lämnats orörd.");
        if (existing.size() == 1 && existing.get(0).equals(destination)
            && sha256(Files.readAllBytes(destination)).equals(actual)) {
            progress.accept("Aurora " + VERSION + " är redan installerad.");
            return new InstallResult(destination, List.of(), actual, VERSION, true);
        }

        Path staged = mods.resolve(".aurora-install-" + UUID.randomUUID() + ".part");
        Map<Path, Path> moved = new LinkedHashMap<>();
        boolean installed = false;
        Path backupDirectory = null;
        try {
            writeDurably(staged, artifact);
            if (!sha256(Files.readAllBytes(staged)).equals(actual)) throw new IOException("Kunde inte verifiera den skrivna modfilen.");
            if (!existing.isEmpty()) {
                progress.accept("Säkerhetskopierar tidigare Aurora-versioner…");
                Path backups = mods.resolve(".aurora-backups");
                rejectSymbolicLinks(backups);
                if (Files.exists(backups, NO_FOLLOW) && !Files.isDirectory(backups, NO_FOLLOW))
                    throw new IOException("Auroras backup-sökväg är inte en mapp.");
                Files.createDirectories(backups);
                backupDirectory = backups.resolve(Instant.now().toString().replace(':', '-') + "-" + UUID.randomUUID());
                Files.createDirectory(backupDirectory);
                for (Path old : existing) {
                    rejectSymbolicLinks(old);
                    Path backup = backupDirectory.resolve(old.getFileName());
                    move.move(old, backup);
                    moved.put(old, backup);
                }
            }
            progress.accept("Installerar Aurora " + VERSION + "…");
            rejectSymbolicLinks(mods);
            move.move(staged, destination);
            installed = true;
            if (!sha256(Files.readAllBytes(destination)).equals(actual)) throw new IOException("Den installerade modfilen kunde inte verifieras.");
            progress.accept("Aurora " + VERSION + " är installerad.");
            return new InstallResult(destination, new ArrayList<>(moved.values()), actual, VERSION, false);
        } catch (IOException | RuntimeException error) {
            IOException failure = error instanceof IOException io ? io : new IOException("Installationen avbröts.", error);
            // Remove only this transaction's new artifact; never overwrite an unrelated file on rollback.
            if (installed) {
                try {
                    if (Files.isRegularFile(destination, NO_FOLLOW) && sha256(Files.readAllBytes(destination)).equals(actual))
                        Files.delete(destination);
                    else failure.addSuppressed(new IOException("Den nya filen ändrades under installationen och lämnades orörd: " + destination));
                } catch (IOException rollbackError) { failure.addSuppressed(rollbackError); }
            }
            var entries = new ArrayList<>(moved.entrySet());
            for (int i = entries.size() - 1; i >= 0; i--) {
                var entry = entries.get(i);
                try {
                    if (Files.exists(entry.getKey(), NO_FOLLOW)) throw new IOException("Kan inte återställa över en ändrad fil: " + entry.getKey());
                    move.move(entry.getValue(), entry.getKey());
                } catch (IOException rollbackError) { failure.addSuppressed(rollbackError); }
            }
            throw failure;
        } finally {
            Files.deleteIfExists(staged);
            if (backupDirectory != null) {
                try (DirectoryStream<Path> contents = Files.newDirectoryStream(backupDirectory)) {
                    if (!contents.iterator().hasNext()) Files.delete(backupDirectory);
                } catch (IOException ignored) { /* Preserve backups if cleanup is unavailable. */ }
            }
        }
    }

    private static List<Path> findAuroraMods(Path mods) throws IOException {
        var found = new ArrayList<Path>();
        try (DirectoryStream<Path> files = Files.newDirectoryStream(mods)) {
            for (Path path : files) {
                if (!path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".jar")) continue;
                if (Files.isSymbolicLink(path)) throw new IOException("En modfil är en symbolisk länk. Välj en spelmapp med vanliga JAR-filer: " + path.getFileName());
                if (!Files.isRegularFile(path, NO_FOLLOW)) continue;
                try (ZipFile jar = new ZipFile(path.toFile())) {
                    ZipEntry entry = jar.getEntry("fabric.mod.json");
                    if (entry == null || entry.getSize() > MAX_METADATA_BYTES) continue;
                    String json;
                    try (InputStream input = jar.getInputStream(entry)) {
                        json = new String(readLimited(input, MAX_METADATA_BYTES), StandardCharsets.UTF_8);
                    }
                    if ("aurora".equals(JsonFields.string(json, "id"))) found.add(path);
                } catch (IOException | IllegalArgumentException ignored) {
                    // Corrupt or non-Fabric mods are unrelated data. Do not delete or rename them.
                }
            }
        }
        found.sort(Path::compareTo);
        return found;
    }

    private static void verifyBundledMetadata(String json) throws IOException {
        try {
            if (!"aurora".equals(JsonFields.string(json, "id")) || !VERSION.equals(JsonFields.string(json, "version")))
                throw new IOException("Den medföljande filen är inte rätt Aurora-version.");
            String depends = JsonFields.object(json, "depends");
            if (!MINECRAFT_VERSION.equals(JsonFields.string(depends, "minecraft"))
                || !(">=" + MINIMUM_LOADER).equals(JsonFields.string(depends, "fabricloader")))
                throw new IOException("Aurora-filens Minecraft- eller Fabric-version stämmer inte.");
        } catch (IllegalArgumentException error) {
            throw new IOException("Aurora-filens modinformation är ogiltig.", error);
        }
    }

    private static String readMetadata(byte[] artifact) throws IOException {
        try (ZipInputStream jar = new ZipInputStream(new ByteArrayInputStream(artifact))) {
            for (ZipEntry entry; (entry = jar.getNextEntry()) != null; ) {
                if (entry.getName().equals("fabric.mod.json")) {
                    if (entry.getSize() > MAX_METADATA_BYTES) throw new IOException("För stor modinformation.");
                    return new String(readLimited(jar, MAX_METADATA_BYTES), StandardCharsets.UTF_8);
                }
            }
        }
        throw new IOException("Aurora-filen saknar fabric.mod.json.");
    }

    private static InputStream requiredResource(String name) throws IOException {
        InputStream input = ModInstaller.class.getResourceAsStream(name);
        if (input == null) throw new IOException("Launchern saknar sin medföljande Aurora-fil.");
        return input;
    }

    private static String bundledHash() throws IOException {
        try (InputStream input = requiredResource(RESOURCE + ".sha256")) {
            String text = new String(readLimited(input, 1024), StandardCharsets.US_ASCII).trim();
            try { return normalizedHash(text.split("\\s+", 2)[0]); }
            catch (IllegalArgumentException error) { throw new IOException("Launcherns kontrollsumma är ogiltig.", error); }
        }
    }

    private static String normalizedHash(String value) {
        if (value == null || !value.matches("[a-fA-F0-9]{64}")) throw new IllegalArgumentException("Invalid SHA-256");
        return value.toLowerCase(Locale.ROOT);
    }

    private static byte[] readLimited(InputStream input, int maximum) throws IOException {
        byte[] bytes = input.readNBytes(maximum + 1);
        if (bytes.length > maximum) throw new IOException("Filen överskrider den tillåtna storleken.");
        return bytes;
    }

    public static String sha256(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }

    private static void rejectSymbolicLinks(Path path) throws IOException {
        Path current = path.getRoot();
        for (Path component : path) {
            current = current == null ? component : current.resolve(component);
            if (Files.isSymbolicLink(current)) throw new IOException("Sökvägen innehåller en symbolisk länk: " + current);
        }
    }

    private static void writeDurably(Path path, byte[] bytes) throws IOException {
        try (FileChannel file = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            ByteBuffer buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) file.write(buffer);
            file.force(true);
        }
    }

    private static void atomicMove(Path from, Path to) throws IOException {
        if (Files.exists(to, NO_FOLLOW)) throw new java.nio.file.FileAlreadyExistsException(to.toString());
        try { Files.move(from, to, StandardCopyOption.ATOMIC_MOVE); }
        catch (AtomicMoveNotSupportedException unsupported) { Files.move(from, to); }
    }

    /** Small, bounded metadata reader: keys are matched only at the current object's top level. */
    private static final class JsonFields {
        static String string(String object, String key) {
            String value = field(object, key);
            if (value == null || !value.startsWith("\"")) return null;
            return quoted(value, 0).text;
        }
        static String object(String json, String key) {
            String value = field(json, key);
            if (value == null || !value.startsWith("{")) throw new IllegalArgumentException("Missing object " + key);
            return value;
        }
        static String field(String json, String key) {
            if (json == null) return null;
            int at = whitespace(json, 0);
            if (at >= json.length() || json.charAt(at++) != '{') throw new IllegalArgumentException("Expected object");
            String result = null;
            while (true) {
                at = whitespace(json, at);
                if (at >= json.length()) throw new IllegalArgumentException("Unclosed object");
                if (json.charAt(at) == '}') return result;
                Quoted name = quoted(json, at);at = whitespace(json, name.end);
                if (at >= json.length() || json.charAt(at++) != ':') throw new IllegalArgumentException("Missing colon");
                at = whitespace(json, at);int end = valueEnd(json, at);
                if (name.text.equals(key)) {
                    if (result != null) throw new IllegalArgumentException("Duplicate key " + key);
                    result = json.substring(at, end).trim();
                }
                at = whitespace(json, end);
                if (at >= json.length()) throw new IllegalArgumentException("Unclosed object");
                if (json.charAt(at) == '}') return result;
                if (json.charAt(at++) != ',') throw new IllegalArgumentException("Missing comma");
            }
        }
        private static int valueEnd(String text, int start) {
            if (start >= text.length()) throw new IllegalArgumentException("Missing value");
            char first = text.charAt(start);
            if (first == '"') return quoted(text, start).end;
            if (first == '{' || first == '[') {
                var closes = new ArrayList<Character>();closes.add(first == '{' ? '}' : ']');
                int at = start + 1;
                while (!closes.isEmpty() && at < text.length()) {
                    char c = text.charAt(at);
                    if (c == '"') {at = quoted(text, at).end;continue;}
                    if (c == '{' || c == '[') closes.add(c == '{' ? '}' : ']');
                    else if (c == '}' || c == ']') {
                        if (closes.remove(closes.size() - 1) != c) throw new IllegalArgumentException("Mismatched delimiter");
                    }
                    at++;
                }
                if (!closes.isEmpty()) throw new IllegalArgumentException("Unclosed value");
                return at;
            }
            int at = start;
            while (at < text.length() && ",}]".indexOf(text.charAt(at)) < 0) at++;
            if (at == start) throw new IllegalArgumentException("Empty value");
            return at;
        }
        private record Quoted(String text, int end) { }
        private static Quoted quoted(String source, int start) {
            if (start >= source.length() || source.charAt(start) != '"') throw new IllegalArgumentException("Expected string");
            var decoded = new StringBuilder();int at = start + 1;
            while (at < source.length()) {
                char c = source.charAt(at++);
                if (c == '"') return new Quoted(decoded.toString(), at);
                if (c < 32) throw new IllegalArgumentException("Control character");
                if (c != '\\') {decoded.append(c);continue;}
                if (at >= source.length()) throw new IllegalArgumentException("Unclosed escape");
                char escape = source.charAt(at++);
                switch (escape) {
                    case '"', '\\', '/' -> decoded.append(escape);
                    case 'b' -> decoded.append('\b');
                    case 'f' -> decoded.append('\f');
                    case 'n' -> decoded.append('\n');
                    case 'r' -> decoded.append('\r');
                    case 't' -> decoded.append('\t');
                    case 'u' -> {
                        if (at + 4 > source.length()) throw new IllegalArgumentException("Invalid Unicode escape");
                        decoded.append((char)Integer.parseInt(source.substring(at, at + 4), 16));at += 4;
                    }
                    default -> throw new IllegalArgumentException("Invalid escape");
                }
            }
            throw new IllegalArgumentException("Unclosed string");
        }
        private static int whitespace(String text, int at) {
            while (at < text.length() && Character.isWhitespace(text.charAt(at))) at++;
            return at;
        }
    }
}
