package dev.aurora.launcher.service;

import java.io.BufferedWriter;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.AclEntry;
import java.nio.file.attribute.AclEntryFlag;
import java.nio.file.attribute.AclEntryPermission;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.FileAttribute;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.nio.file.attribute.UserPrincipal;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Starts Java with a private argument file instead of exposing tokens in the OS command line.
 *
 * <p>The Java executable receives only the @file path. All game/JVM arguments are encoded
 * independently using Java's argument-file grammar; no shell is involved. Argument contents
 * are never logged. The game runtime selected by the launcher is Java 25 and reads UTF-8 files.
 */
public final class GameProcess {
    private static final Set<PosixFilePermission> DIRECTORY_PERMISSIONS =
        PosixFilePermissions.fromString("rwx------");
    private static final Set<PosixFilePermission> FILE_PERMISSIONS =
        PosixFilePermissions.fromString("rw-------");
    private static final int MAX_ARGUMENT_CHARACTERS = 16 * 1024 * 1024;

    private GameProcess() { }

    /**
     * command[0] is the Java executable; every later element is one literal argument.
     *
     * <p>The argument directory must be owned by this process's user. It is created or
     * restricted to that owner before any argument is written. Filesystems without POSIX
     * permissions or Windows ACLs are refused instead of falling back to a public file.
     */
    public static RunningGame start(List<String> command, Path directory, Path privateArgDirectory)
        throws IOException {
        command = List.copyOf(Objects.requireNonNull(command, "command"));
        if (command.isEmpty()) throw new IllegalArgumentException("A Java executable is required");
        validateArguments(command);
        Path executable = Path.of(command.get(0)).toAbsolutePath().normalize();
        Path working = Objects.requireNonNull(directory, "directory").toAbsolutePath().normalize();
        Path argumentDirectory = Objects.requireNonNull(privateArgDirectory, "privateArgDirectory")
            .toAbsolutePath().normalize();
        if (!Files.isRegularFile(executable) || !Files.isExecutable(executable))
            throw new IOException("The selected Java executable is unavailable");
        if (!Files.isDirectory(working)) throw new IOException("The game directory is unavailable");

        secureDirectory(argumentDirectory);
        Path argumentFile = null;
        try {
            argumentFile = createPrivateFile(argumentDirectory);
            // Comparing owners also rejects an attacker-owned preexisting directory. At this
            // point the file is still empty: no secret has been written or passed to Java.
            if (!Files.getOwner(argumentDirectory, LinkOption.NOFOLLOW_LINKS)
                .equals(Files.getOwner(argumentFile, LinkOption.NOFOLLOW_LINKS))) {
                throw new IOException("The private argument directory belongs to another user");
            }
            try (BufferedWriter writer = Files.newBufferedWriter(argumentFile, StandardCharsets.UTF_8,
                StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING)) {
                for (int index = 1; index < command.size(); index++) {
                    writeArgument(writer, command.get(index));
                    writer.newLine();
                }
            }
            Process process = new ProcessBuilder(executable.toString(), "@" + argumentFile)
                .directory(working.toFile()).redirectErrorStream(true).start();
            return new RunningGame(process, argumentFile);
        } catch (IOException | RuntimeException | Error failure) {
            if (argumentFile != null) {
                try { Files.deleteIfExists(argumentFile); }
                catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            }
            throw failure;
        }
    }

    /** The file may be removed after Java has consumed it; closing never terminates Minecraft. */
    public static final class RunningGame implements AutoCloseable {
        private final Process process;
        private final Path argumentFile;
        private final CompletableFuture<Void> cleanup;
        private boolean closed;

        private RunningGame(Process process, Path argumentFile) {
            this.process = process;
            this.argumentFile = argumentFile;
            cleanup = process.onExit().thenAccept(ignored -> {
                try { close(); }
                catch (IOException failure) { throw new CompletionException(failure); }
            });
        }

        public Process process() { return process; }

        /** Allows callers to observe cleanup failures without exposing argument contents. */
        public CompletableFuture<Void> cleanupCompletion() { return cleanup.copy(); }

        // Package-private for lifecycle checks; production diagnostics must not read or log it.
        Path argumentFile() { return argumentFile; }

        /**
         * Delete only the argument file. Call after the child has read its launch arguments,
         * or after exit. A caller closing its UI immediately after start must retain its
         * process monitor rather than deleting this file before Java can open it.
         */
        @Override public synchronized void close() throws IOException {
            if (!closed) {
                Files.deleteIfExists(argumentFile);
                closed = true;
            }
        }
    }

    private static void validateArguments(List<String> command) {
        long total = 0;
        for (String argument : command) {
            Objects.requireNonNull(argument, "Java arguments must not be null");
            total += argument.length();
            if (total > MAX_ARGUMENT_CHARACTERS)
                throw new IllegalArgumentException("The Java argument list is too large");
            for (int index = 0; index < argument.length(); index++) {
                char character = argument.charAt(index);
                if (character == 0) throw new IllegalArgumentException("Java arguments must not contain NUL");
                if (Character.isHighSurrogate(character)) {
                    if (++index >= argument.length() || !Character.isLowSurrogate(argument.charAt(index)))
                        throw new IllegalArgumentException("Java arguments must contain valid Unicode");
                } else if (Character.isLowSurrogate(character)) {
                    throw new IllegalArgumentException("Java arguments must contain valid Unicode");
                }
            }
        }
        if (command.get(0).isBlank()) throw new IllegalArgumentException("A Java executable is required");
    }

    /** Quotes each value, including empty values; # is then data, never an argument-file comment. */
    private static void writeArgument(BufferedWriter writer, String argument) throws IOException {
        writer.write('"');
        for (int index = 0; index < argument.length(); index++) {
            switch (argument.charAt(index)) {
                case '\\' -> writer.write("\\\\");
                case '"' -> writer.write("\\\"");
                case '\n' -> writer.write("\\n");
                case '\r' -> writer.write("\\r");
                case '\t' -> writer.write("\\t");
                case '\f' -> writer.write("\\f");
                default -> writer.write(argument.charAt(index));
            }
        }
        writer.write('"');
    }

    private static void secureDirectory(Path directory) throws IOException {
        try {
            if (Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS))
                    throw new IOException("The private argument path must be a real directory");
                // Do not chmod an arbitrary preexisting shared folder (for example /tmp or
                // the game directory). Existing argument folders must already be private.
                verifyExistingDirectory(directory);
            } else {
                Path parent = directory.getParent();
                if (parent == null) throw new IOException("Invalid private argument directory");
                Files.createDirectories(parent);
                if (Files.getFileAttributeView(parent, PosixFileAttributeView.class) != null) {
                    Files.createDirectory(directory, PosixFilePermissions.asFileAttribute(DIRECTORY_PERMISSIONS));
                } else {
                    Files.createDirectory(directory);
                }
            }
            makeOwnerOnly(directory, true);
        } catch (UnsupportedOperationException unsupported) {
            throw new IOException("Private Java argument files are not supported on this filesystem", unsupported);
        }
    }

    private static void verifyExistingDirectory(Path directory) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(directory, PosixFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            for (PosixFilePermission permission : posix.readAttributes().permissions()) {
                if (!DIRECTORY_PERMISSIONS.contains(permission))
                    throw new IOException("The existing argument directory is not private");
            }
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(directory, AclFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IOException("The filesystem cannot protect private Java arguments");
        UserPrincipal owner = acl.getOwner();
        for (AclEntry entry : acl.getAcl()) {
            if (entry.type() == AclEntryType.ALLOW && !entry.principal().equals(owner))
                throw new IOException("The existing argument directory is not private");
        }
    }

    private static Path createPrivateFile(Path directory) throws IOException {
        Path path;
        if (Files.getFileAttributeView(directory, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS) != null) {
            FileAttribute<Set<PosixFilePermission>> permissions = PosixFilePermissions.asFileAttribute(FILE_PERMISSIONS);
            path = Files.createTempFile(directory, ".aurora-java-", ".args", permissions);
        } else {
            // The parent ACL already restricts inheritance. Enforce the resulting file ACL
            // explicitly too, before writing any credentials into the initially empty file.
            path = Files.createTempFile(directory, ".aurora-java-", ".args");
        }
        try {
            makeOwnerOnly(path, false);
            return path;
        } catch (IOException | RuntimeException | Error failure) {
            try { Files.deleteIfExists(path); }
            catch (IOException cleanupFailure) { failure.addSuppressed(cleanupFailure); }
            throw failure;
        }
    }

    private static void makeOwnerOnly(Path path, boolean directory) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(path, PosixFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            Set<PosixFilePermission> permissions = directory ? DIRECTORY_PERMISSIONS : FILE_PERMISSIONS;
            posix.setPermissions(permissions);
            if (!posix.readAttributes().permissions().equals(permissions))
                throw new IOException("Could not restrict Java argument permissions");
            return;
        }
        AclFileAttributeView acl = Files.getFileAttributeView(path, AclFileAttributeView.class,
            LinkOption.NOFOLLOW_LINKS);
        if (acl == null) throw new IOException("The filesystem cannot protect private Java arguments");
        UserPrincipal owner = acl.getOwner();
        AclEntry.Builder entry = AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
            .setPermissions(EnumSet.allOf(AclEntryPermission.class));
        if (directory) entry.setFlags(AclEntryFlag.FILE_INHERIT, AclEntryFlag.DIRECTORY_INHERIT);
        acl.setAcl(List.of(entry.build()));
        for (AclEntry actual : acl.getAcl()) {
            if (actual.type() == AclEntryType.ALLOW && !actual.principal().equals(owner))
                throw new IOException("Could not restrict Java argument ACL to its owner");
        }
    }
}
