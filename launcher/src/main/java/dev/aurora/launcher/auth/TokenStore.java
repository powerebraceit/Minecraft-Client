package dev.aurora.launcher.auth;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.util.*;

/**
 * Stores only Microsoft's refresh token in a per-user file, never the Minecraft access token.
 * This is a permission-restricted file, not an operating-system credential vault or encryption.
 * POSIX permissions are 0600; Windows ACLs are restricted to the file owner when supported.
 */
public final class TokenStore {
    private static final int MAX_FILE_BYTES = 65536;
    private final Path file;

    public TokenStore(Path file) { this.file = Objects.requireNonNull(file).toAbsolutePath().normalize(); }

    public synchronized Optional<String> load(String clientId) throws IOException {
        if (!Files.exists(file, LinkOption.NOFOLLOW_LINKS)) return Optional.empty();
        rejectLink(file);
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS) || Files.size(file) > MAX_FILE_BYTES)
            throw new IOException("Den sparade inloggningsfilen är ogiltig.");
        restrict(file);
        Properties properties = new Properties();
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { properties.load(reader); }
        catch (IllegalArgumentException invalid) { throw new IOException("Den sparade inloggningen kunde inte läsas."); }
        if (!Objects.equals(clientId, properties.getProperty("clientId"))) return Optional.empty();
        String token = properties.getProperty("refreshToken", "");
        return token.isBlank() ? Optional.empty() : Optional.of(token);
    }

    public synchronized void save(String clientId, String refreshToken) throws IOException {
        if (clientId == null || clientId.isBlank() || refreshToken == null || refreshToken.isBlank() || refreshToken.length() > 16000)
            throw new IOException("Inloggningen saknar giltiga sessionsuppgifter.");
        Path parent = file.getParent();
        if (parent == null) throw new IOException("Inloggningen behöver en lokal datamapp.");
        rejectLink(parent);
        rejectLink(file);
        Files.createDirectories(parent);
        if (!Files.isDirectory(parent, LinkOption.NOFOLLOW_LINKS)) throw new IOException("Inloggningsmappen är ogiltig.");
        Path temp = Files.createTempFile(parent, ".aurora-session-", ".tmp");
        try {
            restrict(temp);
            Properties properties = new Properties();
            properties.setProperty("clientId", clientId);
            properties.setProperty("refreshToken", refreshToken);
            try (Writer writer = Files.newBufferedWriter(temp, StandardCharsets.UTF_8)) {
                properties.store(writer, "Aurora Microsoft refresh session - private user file");
            }
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException unavailable) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
            restrict(file);
        } finally { Files.deleteIfExists(temp); }
    }

    public synchronized void clear() throws IOException {
        rejectLink(file);
        Files.deleteIfExists(file);
    }

    private static void rejectLink(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Inloggningsfilen får inte vara en symbolisk länk.");
    }

    private static void restrict(Path path) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            posix.setPermissions(PosixFilePermissions.fromString("rw-------"));
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (acl != null) {
            UserPrincipal owner = Files.getOwner(path, LinkOption.NOFOLLOW_LINKS);
            AclEntry ownerOnly = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build();
            acl.setAcl(List.of(ownerOnly));
        }
    }
}
