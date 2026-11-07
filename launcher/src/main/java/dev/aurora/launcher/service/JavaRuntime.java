package dev.aurora.launcher.service;

import dev.aurora.launcher.net.Json;
import java.io.*;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermission;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.*;
import java.util.regex.*;

/** Resolves Java 25 or installs Mojang's verified runtime without executing a third-party installer. */
public final class JavaRuntime {
    public static final int REQUIRED_FEATURE = 25;
    public static final URI INDEX = URI.create("https://launchermeta.mojang.com/v1/products/java-runtime/2ec0cc96c44e5a76b9c8b7c39df7210883d12871/all.json");
    private static final String COMPONENT = "java-runtime-epsilon";
    private static final String MARKER = ".aurora-runtime.json";
    private static final long MAX_TOTAL = 1024L * 1024 * 1024;
    private final Downloader downloader;
    private final URI indexUri;
    private final String platform;
    private final Path currentJava;
    private final VersionProbe probe;

    public interface Downloader {
        byte[] read(URI uri, BooleanSupplier cancelled) throws IOException, InterruptedException;
        void fetch(URI uri, Path destination, long expectedSize, BooleanSupplier cancelled) throws IOException, InterruptedException;
    }
    @FunctionalInterface public interface VersionProbe {
        int feature(Path executable) throws IOException, InterruptedException;
    }

    public JavaRuntime() {
        this(new HttpDownloader(), INDEX, platform(System.getProperty("os.name"), System.getProperty("os.arch")),
            Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java"), JavaRuntime::javaFeature);
    }
    /** Injectable network and version probe keep installation tests independent of real Java downloads. */
    public JavaRuntime(Downloader downloader, URI indexUri, String platform, Path currentJava, VersionProbe probe) {
        this.downloader = Objects.requireNonNull(downloader);
        this.indexUri = Objects.requireNonNull(indexUri);
        this.platform = Objects.requireNonNull(platform);
        this.currentJava = currentJava;
        this.probe = Objects.requireNonNull(probe);
    }

    public Path resolve(Path explicitJava, Path runtimeCache, Consumer<String> progress, BooleanSupplier cancelled)
            throws IOException, InterruptedException {
        Objects.requireNonNull(runtimeCache); Objects.requireNonNull(progress); Objects.requireNonNull(cancelled);
        checkCancelled(cancelled);
        if (explicitJava != null) return requireJava(explicitJava);
        if (currentJava != null && Files.isRegularFile(currentJava)) {
            try { if (probe.feature(currentJava) >= REQUIRED_FEATURE) return currentJava.toRealPath(); }
            catch (IOException ignored) { /* A configured runtime may still be recovered by installing Java. */ }
        }
        Path cache = runtimeCache.toAbsolutePath().normalize();
        ensureDirectory(cache);
        cache = cache.toRealPath();
        Path installed = cache.resolve(COMPONENT + "-" + platform);
        Path cached = cachedJava(installed);
        if (cached != null) { progress.accept("Java 25 är redo"); return cached; }
        progress.accept("Hämtar Java 25 från Mojang");
        install(installed, progress, cancelled);
        return requireJava(findJava(installed));
    }

    private Path requireJava(Path executable) throws IOException, InterruptedException {
        Path path = executable.toAbsolutePath().normalize();
        if (!Files.isRegularFile(path)) throw new IOException("Java-programmet finns inte. Välj java.exe eller java i Java 25-installationen.");
        int feature = probe.feature(path);
        if (feature < REQUIRED_FEATURE) throw new IOException("Minecraft 26.3 kräver Java 25 eller senare. Det valda programmet är Java " + feature + ".");
        return path.toRealPath();
    }
    private Path cachedJava(Path root) throws InterruptedException {
        try {
            if (Files.isSymbolicLink(root) || !Files.isRegularFile(root.resolve(MARKER), LinkOption.NOFOLLOW_LINKS)) return null;
            Map<String,Object> marker = Json.object(Files.readString(root.resolve(MARKER)));
            Path java = safePath(root, Json.requiredString(marker,"java"));
            verify(java, Json.requiredString(marker,"sha1"), number(marker,"size"));
            return probe.feature(java) >= REQUIRED_FEATURE ? java : null;
        } catch (IOException | IllegalArgumentException e) { return null; }
    }

    private void install(Path installed, Consumer<String> progress, BooleanSupplier cancelled) throws IOException, InterruptedException {
        Map<String,Object> index = Json.object(new String(downloader.read(indexUri, cancelled), StandardCharsets.UTF_8));
        Map<String,Object> os = Json.obj(index, platform);
        Object candidates = os.get(COMPONENT);
        if (!(candidates instanceof List<?> versions) || versions.isEmpty())
            throw new IOException("Mojang erbjuder inte Java 25 för den här datorn (" + platform + "). Välj en egen Java 25-installation i inställningarna.");
        Map<String,Object> selected = Json.asObject(versions.get(0));
        Map<String,Object> descriptor = Json.obj(selected,"manifest");
        byte[] bytes = downloader.read(https(Json.requiredString(descriptor,"url")), cancelled);
        verify(bytes, Json.requiredString(descriptor,"sha1"), number(descriptor,"size"));
        Map<String,Object> files = Json.obj(Json.object(new String(bytes, StandardCharsets.UTF_8)),"files");
        if (files.isEmpty() || files.size() > 20000) throw new IOException("Java-manifestet har ett ogiltigt antal filer.");
        long total = 0;
        for (Object value : files.values()) {
            Map<String,Object> entry = Json.asObject(value);
            if (Json.str(entry,"type").equals("file")) total = Math.addExact(total, number(Json.obj(Json.obj(entry,"downloads"),"raw"),"size"));
            if (total > MAX_TOTAL) throw new IOException("Java-installationen är oväntat stor.");
        }
        Path staging = Files.createTempDirectory(installed.getParent(), ".aurora-java-");
        Path backup = installed.resolveSibling(installed.getFileName() + ".backup-" + UUID.randomUUID());
        boolean oldMoved = false, committed = false;
        try {
            // Resolve all paths before downloading. A malicious manifest cannot escape the new private directory.
            for (String name : files.keySet()) safePath(staging,name);
            int completed = 0;
            for (var file : files.entrySet()) {
                checkCancelled(cancelled);
                Map<String,Object> entry = Json.asObject(file.getValue());
                Path destination = safePath(staging,file.getKey());
                String type = Json.str(entry,"type");
                if (type.equals("directory")) ensureDirectory(destination);
                else if (type.equals("file")) {
                    ensureDirectory(destination.getParent());
                    Map<String,Object> raw = Json.obj(Json.obj(entry,"downloads"),"raw");
                    long size = number(raw,"size"); String sha1 = Json.requiredString(raw,"sha1");
                    downloader.fetch(https(Json.requiredString(raw,"url")), destination, size, cancelled);
                    verify(destination,sha1,size);
                    if (Boolean.TRUE.equals(entry.get("executable"))) executable(destination);
                } else if (!type.equals("link")) throw new IOException("Okänd filtyp i Java-manifestet.");
                if (++completed % 15 == 0) progress.accept("Hämtar Java 25: " + completed + " / " + files.size() + " filer");
            }
            createLinks(staging,files,cancelled);
            Path java = findJava(staging);
            // Verify the downloaded runtime while the old cache is still untouched.
            if (probe.feature(java) < REQUIRED_FEATURE) throw new IOException("Den hämtade Java-versionen är för gammal för Minecraft 26.3.");
            Map<String,Object> javaEntry = Json.asObject(files.get(staging.relativize(java).toString().replace('\\','/')));
            Map<String,Object> raw = Json.obj(Json.obj(javaEntry,"downloads"),"raw");
            Files.writeString(staging.resolve(MARKER),Json.stringify(Map.of("java",staging.relativize(java).toString().replace('\\','/'),
                "sha1",Json.requiredString(raw,"sha1"),"size",number(raw,"size"))),StandardOpenOption.CREATE_NEW);
            checkCancelled(cancelled);
            if (Files.exists(installed,LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(installed) || !Files.isDirectory(installed,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Java-cachemappen är inte en vanlig mapp.");
                move(installed,backup);oldMoved=true;
            }
            try { move(staging,installed);committed=true; }
            catch (IOException failure) { if (oldMoved) try { move(backup,installed); } catch (IOException rollback) { failure.addSuppressed(rollback); } throw failure; }
            progress.accept("Java 25 har installerats");
        } catch (ArithmeticException | IllegalArgumentException e) { throw new IOException("Java-manifestet är ogiltigt.",e); }
        finally {
            if (!committed) deleteTree(staging);
            if (committed && oldMoved) try { deleteTree(backup); } catch (IOException ignored) { /* A retained backup does not invalidate the verified new runtime. */ }
        }
    }

    private static void createLinks(Path root, Map<String,Object> files, BooleanSupplier cancelled) throws IOException {
        Map<Path,String> pending = new LinkedHashMap<>();
        for (var entry : files.entrySet()) {
            Map<String,Object> value = Json.asObject(entry.getValue());
            if (Json.str(value,"type").equals("link")) pending.put(safePath(root,entry.getKey()),Json.requiredString(value,"target"));
        }
        while (!pending.isEmpty()) {
            boolean changed = false;
            for (var iterator = pending.entrySet().iterator(); iterator.hasNext();) {
                checkCancelled(cancelled);
                var link = iterator.next(); String value = link.getValue();
                if (value.startsWith("/") || value.contains("\\") || value.matches("^[A-Za-z]:.*")) throw new IOException("Java-länk pekar utanför installationen.");
                Path target = link.getKey().getParent().resolve(value).normalize();
                if (!target.startsWith(root)) throw new IOException("Java-länk pekar utanför installationen.");
                if (!Files.exists(target)) continue;
                if (!target.toRealPath().startsWith(root.toRealPath())) throw new IOException("Java-länk pekar utanför installationen.");
                ensureDirectory(link.getKey().getParent());
                try { Files.createSymbolicLink(link.getKey(),Path.of(value)); }
                catch (UnsupportedOperationException | FileSystemException e) {
                    // Windows installations normally contain no links. File copies handle legal-file links without admin rights.
                    if (!Files.isRegularFile(target)) throw new IOException("Datorn kan inte skapa den Java-länk som krävs.",e);
                    Files.copy(target,link.getKey());
                }
                iterator.remove();changed=true;
            }
            if (!changed) throw new IOException("Java-manifestet innehåller en saknad eller cirkulär länk.");
        }
    }

    private static Path findJava(Path root) throws IOException {
        for (String relative : List.of("bin/java.exe","bin/java","jre.bundle/Contents/Home/bin/java")) {
            Path candidate = safePath(root,relative);
            if (Files.isRegularFile(candidate,LinkOption.NOFOLLOW_LINKS)) return candidate;
        }
        throw new IOException("Java-installationen saknar java-programmet.");
    }
    private static Path safePath(Path root, String relative) throws IOException {
        if (relative.isBlank() || relative.startsWith("/") || relative.contains("\\") || relative.matches("^[A-Za-z]:.*")
            || Arrays.asList(relative.split("/",-1)).contains("..")) throw new IOException("Ogiltig sökväg i Java-manifestet.");
        Path path=root.resolve(relative).normalize();
        if (!path.startsWith(root)) throw new IOException("Java-sökvägen lämnar installationen.");
        Path current=path;
        while (current!=null && current.startsWith(root)) {
            if (Files.isSymbolicLink(current)) throw new IOException("Java-sökvägen innehåller en oväntad länk.");
            current=current.getParent();
        }
        return path;
    }
    private static void ensureDirectory(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Installationsmappen får inte vara en symbolisk länk.");
        Files.createDirectories(path);
        if (!Files.isDirectory(path,LinkOption.NOFOLLOW_LINKS)) throw new IOException("Installationsmappen är ogiltig.");
    }
    private static void executable(Path path) throws IOException {
        try {
            var permissions=EnumSet.copyOf(Files.getPosixFilePermissions(path));
            permissions.add(PosixFilePermission.OWNER_EXECUTE);Files.setPosixFilePermissions(path,permissions);
        } catch (UnsupportedOperationException e) {
            if (!path.toFile().setExecutable(true,true) && !isWindows()) throw new IOException("Java kunde inte göras körbar.");
        }
    }
    private static void verify(Path path,String sha1,long size) throws IOException {
        if (!Files.isRegularFile(path,LinkOption.NOFOLLOW_LINKS) || Files.size(path)!=size) throw new IOException("Java-filens storlek stämmer inte.");
        try (InputStream input=Files.newInputStream(path)) { if (!digest(input).equalsIgnoreCase(sha1)) throw new IOException("Java-filens kontrollsumma stämmer inte."); }
    }
    private static void verify(byte[] bytes,String sha1,long size) throws IOException {
        if (bytes.length!=size || !digest(new ByteArrayInputStream(bytes)).equalsIgnoreCase(sha1)) throw new IOException("Java-manifestets kontrollsumma stämmer inte.");
    }
    private static String digest(InputStream input) throws IOException {
        try { MessageDigest digest=MessageDigest.getInstance("SHA-1");byte[] buffer=new byte[32768];int read;
            while ((read=input.read(buffer))!=-1) digest.update(buffer,0,read);return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {throw new AssertionError(e);}
    }
    private static long number(Map<String,Object> object,String key) throws IOException {
        Object value=object.get(key);
        if (!(value instanceof Number number) || number.longValue()<0 || number.longValue()>MAX_TOTAL) throw new IOException("Java-manifestet saknar giltig filstorlek.");
        return number.longValue();
    }
    private static URI https(String address) throws IOException {
        try {URI uri=URI.create(address);if (!"https".equals(uri.getScheme()) || uri.getHost()==null || uri.getUserInfo()!=null) throw new IllegalArgumentException();return uri;}
        catch (IllegalArgumentException e) {throw new IOException("Java-manifestets nedladdningsadress är ogiltig.",e);}
    }
    private static void move(Path from,Path to) throws IOException {
        try {Files.move(from,to,StandardCopyOption.ATOMIC_MOVE);}catch (AtomicMoveNotSupportedException e){Files.move(from,to);}
    }
    private static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root,LinkOption.NOFOLLOW_LINKS))return;
        try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}
    }
    private static void checkCancelled(BooleanSupplier cancelled) throws IOException {
        if (cancelled.getAsBoolean() || Thread.currentThread().isInterrupted()) throw new InterruptedIOException("Java-installationen avbröts.");
    }
    public static String platform(String osName,String archName) {
        String os=osName.toLowerCase(Locale.ROOT),arch=archName.toLowerCase(Locale.ROOT);
        boolean arm=arch.equals("aarch64") || arch.equals("arm64");
        boolean x64=arch.equals("amd64") || arch.equals("x86_64");
        if (os.contains("mac") || os.contains("darwin"))return arm?"mac-os-arm64":"mac-os";
        if (os.contains("win"))return arm?"windows-arm64":x64?"windows-x64":"windows-x86";
        if (os.contains("linux"))return x64?"linux":arm?"linux-arm64":"linux-i386";
        return "unsupported-"+arch;
    }
    private static boolean isWindows(){return System.getProperty("os.name","").toLowerCase(Locale.ROOT).contains("win");}

    /** Executes only the Java executable selected by the user or verified by the resolver. */
    public static int javaFeature(Path executable) throws IOException,InterruptedException {
        Process process=new ProcessBuilder(executable.toString(),"-version").redirectErrorStream(true).start();
        ByteArrayOutputStream output=new ByteArrayOutputStream();
        Thread reader=new Thread(()->{try(InputStream input=process.getInputStream()){byte[] buffer=new byte[1024];int read;while((read=input.read(buffer))!=-1)if(output.size()<65536)output.write(buffer,0,Math.min(read,65536-output.size()));}catch(IOException ignored){}},"aurora-java-version");
        reader.setDaemon(true);reader.start();
        if(!process.waitFor(10,TimeUnit.SECONDS)){process.destroyForcibly();throw new IOException("Java svarade inte på versionskontrollen.");}
        reader.join(1000);
        if(process.exitValue()!=0)throw new IOException("Det valda Java-programmet kunde inte startas.");
        return parseFeature(output.toString(StandardCharsets.UTF_8));
    }
    public static int parseFeature(String output) throws IOException {
        Matcher match=Pattern.compile("(?:version\\s+\"|openjdk\\s+|java\\s+)([0-9]+)(?:\\.([0-9]+))?",Pattern.CASE_INSENSITIVE).matcher(output);
        if(!match.find())throw new IOException("Java-versionen kunde inte identifieras.");
        try {int major=Integer.parseInt(match.group(1));return major==1 && match.group(2)!=null?Integer.parseInt(match.group(2)):major;}
        catch(NumberFormatException e){throw new IOException("Java-versionen kunde inte identifieras.",e);}
    }

    private static final class HttpDownloader implements Downloader {
        private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20)).followRedirects(HttpClient.Redirect.NORMAL).build();
        private InputStream open(URI uri) throws IOException,InterruptedException {
            https(uri.toString());
            var response=client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(3)).header("User-Agent","AuroraLauncher/1.0").GET().build(),HttpResponse.BodyHandlers.ofInputStream());
            if(response.statusCode()!=200){response.body().close();throw new IOException("Java kunde inte hämtas från Mojang (HTTP "+response.statusCode()+").");}
            return response.body();
        }
        @Override public byte[] read(URI uri,BooleanSupplier cancelled)throws IOException,InterruptedException {
            try(InputStream input=open(uri);ByteArrayOutputStream output=new ByteArrayOutputStream()) {
                byte[] buffer=new byte[32768];int read;
                while((read=input.read(buffer))!=-1){checkCancelled(cancelled);if(output.size()+read>16*1024*1024)throw new IOException("Java-manifestet är för stort.");output.write(buffer,0,read);}return output.toByteArray();
            }
        }
        @Override public void fetch(URI uri,Path destination,long expectedSize,BooleanSupplier cancelled)throws IOException,InterruptedException {
            try(InputStream input=open(uri);OutputStream output=Files.newOutputStream(destination,StandardOpenOption.CREATE_NEW)) {
                byte[] buffer=new byte[32768];long count=0;int read;
                while((read=input.read(buffer))!=-1){checkCancelled(cancelled);count+=read;if(count>expectedSize)throw new IOException("Java-filen är större än manifestet anger.");output.write(buffer,0,read);}
                if(count!=expectedSize)throw new IOException("Java-filen hämtades inte fullständigt.");
            }
        }
    }
}
