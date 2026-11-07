package dev.aurora.launcher.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.AclEntryType;
import java.nio.file.attribute.AclFileAttributeView;
import java.nio.file.attribute.PosixFileAttributeView;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

/** Starts harmless echo JVMs only; never starts Minecraft or contacts an account service. */
public final class GameProcessCheck {
    private static int checks;

    public static void main(String[] args) throws Exception {
        Path directory = Files.createTempDirectory("aurora-game-process-å spaces-");
        try {
            Path privateArgs = directory.resolve("private launch arguments åäö");
            List<String> values = List.of("", "with spaces", "\"quoted\"", "C:\\Games\\Aurora\\",
                "# not a comment", "åäö 😀", "single'quote", "\\\"", "\t tab \n newline \r carriage \f formfeed",
                "@not-another-file", "--flag=literal", "\"", "\\", "leading space ", " leading space",
                "argument with \\" + "\n" + " and a newline", "example-token-placeholder");
            List<String> echo = command(values);
            GameProcess.RunningGame running = GameProcess.start(echo, directory, privateArgs);
            Path argumentFile = running.argumentFile();
            permissions(privateArgs, argumentFile);
            try (BufferedReader output = running.process().inputReader(StandardCharsets.UTF_8)) {
                equal("COUNT=" + values.size(), output.readLine());
                for (String expected : values) {
                    String line = output.readLine();
                    require(line != null && line.startsWith("ARG="), "Echo protocol changed");
                    equal(expected, new String(Base64.getDecoder().decode(line.substring(4)), StandardCharsets.UTF_8));
                }
            }
            require(running.process().waitFor(10, TimeUnit.SECONDS), "Echo JVM did not exit");
            equal(0, running.process().exitValue());
            running.cleanupCompletion().get(5, TimeUnit.SECONDS);
            require(!Files.exists(argumentFile), "Arguments remained after process exit");
            running.close(); // idempotent after automatic cleanup
            require(isEmpty(privateArgs), "Private directory still contains an argument file");

            // Once Java has printed READY it has parsed @file. Closing releases only that file,
            // while the harmless child deliberately stays alive until this test terminates it.
            GameProcess.RunningGame sleeping = GameProcess.start(command(List.of("--sleep")), directory, privateArgs);
            Process child = sleeping.process();
            try (BufferedReader output = child.inputReader(StandardCharsets.UTF_8)) {
                equal("READY", output.readLine());
                sleeping.close();
                require(!Files.exists(sleeping.argumentFile()), "Explicit close did not remove arguments");
                require(child.isAlive(), "Explicit close must never kill the game");
            } finally {
                child.destroy();
                if (!child.waitFor(5, TimeUnit.SECONDS)) {child.destroyForcibly();child.waitFor(5, TimeUnit.SECONDS);}
                sleeping.cleanupCompletion().get(5, TimeUnit.SECONDS);
                sleeping.close();
            }

            Path invalidExecutable = directory.resolve("not-a-real-java.exe");
            Files.writeString(invalidExecutable, "#!/definitely/nonexistent/aurora-java-interpreter\n");
            invalidExecutable.toFile().setExecutable(true, true);
            ioReject(() -> GameProcess.start(List.of(invalidExecutable.toString(), "--version"), directory, privateArgs));
            require(isEmpty(privateArgs), "Failed process start left argument data behind");
            ioReject(() -> GameProcess.start(command(List.of("value")), directory.resolve("missing"), privateArgs));
            require(isEmpty(privateArgs), "Invalid working directory left argument data behind");
            reject(() -> GameProcess.start(List.of(), directory, privateArgs));
            reject(() -> GameProcess.start(command(List.of("invalid\u0000value")), directory, privateArgs));
            reject(() -> GameProcess.start(command(List.of("\uD800")), directory, privateArgs));

            Path shared = directory.resolve("shared");Files.createDirectories(shared);
            PosixFileAttributeView posix = Files.getFileAttributeView(shared, PosixFileAttributeView.class);
            if (posix != null) {
                Files.setPosixFilePermissions(shared, PosixFilePermissions.fromString("rwxrwxrwx"));
                ioReject(() -> GameProcess.start(command(List.of("value")), directory, shared));
                equal(PosixFilePermissions.fromString("rwxrwxrwx"), Files.getPosixFilePermissions(shared));
                require(isEmpty(shared), "A shared directory must not receive private launch arguments");
                Path link = directory.resolve("link");Files.createSymbolicLink(link, privateArgs);
                ioReject(() -> GameProcess.start(command(List.of("value")), directory, link));
            }
            System.out.println("PASS: GameProcessCheck " + checks + " checks");
        } finally {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.deleteIfExists(path);
            }
        }
    }

    private static List<String> command(List<String> values) {
        String javaName = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win") ? "java.exe" : "java";
        Path javaExecutable = Path.of(System.getProperty("java.home"), "bin", javaName);
        List<String> classpath = new ArrayList<>();
        for (String entry : System.getProperty("java.class.path").split(Pattern.quote(java.io.File.pathSeparator), -1))
            classpath.add(Path.of(entry.isEmpty() ? "." : entry).toAbsolutePath().normalize().toString());
        List<String> result = new ArrayList<>(List.of(javaExecutable.toString(), "-cp", String.join(java.io.File.pathSeparator, classpath), Echo.class.getName()));
        result.addAll(values);return result;
    }

    private static void permissions(Path directory, Path file) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(file, PosixFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
        if (posix != null) {
            equal(PosixFilePermissions.fromString("rw-------"), Files.getPosixFilePermissions(file));
            equal(PosixFilePermissions.fromString("rwx------"), Files.getPosixFilePermissions(directory));
        } else {
            AclFileAttributeView acl = Files.getFileAttributeView(file, AclFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            require(acl != null, "Private ACL must be available");
            var owner = acl.getOwner();
            require(acl.getAcl().stream().noneMatch(entry -> entry.type() == AclEntryType.ALLOW && !entry.principal().equals(owner)),
                "Argument ACL allows another principal");
        }
    }
    private static boolean isEmpty(Path directory) throws IOException {try (var entries = Files.list(directory)) {return entries.findAny().isEmpty();}}
    private static void equal(Object expected, Object actual) {if (!java.util.Objects.equals(expected, actual)) throw new AssertionError("Game argument/lifecycle mismatch");checks++;}
    private static void require(boolean condition, String message) {if (!condition) throw new AssertionError(message);checks++;}
    private interface Action {void run() throws Exception;}
    private static void reject(Action action) throws Exception {
        try {action.run();}catch (IllegalArgumentException expected) {checks++;return;}
        throw new AssertionError("Invalid Java argument was accepted");
    }
    private static void ioReject(Action action) throws Exception {
        try {action.run();}catch (IOException expected) {checks++;return;}
        throw new AssertionError("Invalid process/filesystem input was accepted");
    }

    /** Public only so the separate harmless JVM can invoke it. It has no launcher dependencies. */
    public static final class Echo {
        public static void main(String[] args) throws Exception {
            if (args.length == 1 && args[0].equals("--sleep")) {
                System.out.println("READY");System.out.flush();Thread.sleep(30000);return;
            }
            System.out.println("COUNT=" + args.length);
            for (String value : args) System.out.println("ARG=" + Base64.getEncoder().encodeToString(value.getBytes(StandardCharsets.UTF_8)));
        }
    }
}
