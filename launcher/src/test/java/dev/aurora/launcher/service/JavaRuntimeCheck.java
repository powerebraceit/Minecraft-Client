package dev.aurora.launcher.service;

import dev.aurora.launcher.net.Json;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.util.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Standalone checks use synthetic manifests; downloaded or uploaded native code is never executed. */
public final class JavaRuntimeCheck {
    private static int checks;
    private static final URI INDEX=URI.create("https://runtime.test/index.json");
    private static final byte[] JAVA="test-owned runtime launcher".getBytes(StandardCharsets.UTF_8);
    private static final class Fake implements JavaRuntime.Downloader {
        final Map<URI,byte[]> content=new HashMap<>();
        int reads,fetches;
        boolean corrupt;
        Runnable afterFetch=()->{};
        Fake(Map<String,Object> entries) throws Exception {
            byte[] manifest=Json.stringify(Map.of("files",entries)).getBytes(StandardCharsets.UTF_8);
            URI manifestUri=URI.create("https://runtime.test/manifest.json");content.put(manifestUri,manifest);
            content.put(INDEX,Json.stringify(Map.of("linux",Map.of("java-runtime-epsilon",List.of(Map.of("manifest",descriptor(manifestUri,manifest)))))).getBytes(StandardCharsets.UTF_8));
            content.put(URI.create("https://runtime.test/java"),JAVA);
        }
        public byte[] read(URI uri,BooleanSupplier cancelled) throws IOException {reads++;if(cancelled.getAsBoolean())throw new InterruptedIOException();byte[] value=content.get(uri);if(value==null)throw new IOException("Unknown test URI");return value;}
        public void fetch(URI uri,Path dest,long size,BooleanSupplier cancelled)throws IOException {fetches++;Files.write(dest,corrupt?new byte[]{0}:content.get(uri),StandardOpenOption.CREATE_NEW);afterFetch.run();}
    }
    public static void main(String[] args) throws Exception {
        check(JavaRuntime.parseFeature("openjdk version \"25.0.1\" 2025-10-21")==25,"Current Java version");
        check(JavaRuntime.parseFeature("java version \"1.8.0_461\"")==8,"Legacy Java version");
        check(JavaRuntime.parseFeature("openjdk 21.0.8 2025-07-15")==21,"Modern short version");
        fails(()->JavaRuntime.parseFeature("not a Java executable"),"Unrecognized version");
        check(JavaRuntime.platform("Windows 11","amd64").equals("windows-x64"),"Windows platform");
        check(JavaRuntime.platform("Darwin","aarch64").equals("mac-os-arm64"),"Darwin does not match Windows");
        check(JavaRuntime.platform("Linux","x86_64").equals("linux"),"Linux platform");
        Path root=Files.createTempDirectory("aurora-java-check-");
        try {
            Path explicit=root.resolve("chosen java/bin/java");Files.createDirectories(explicit.getParent());Files.writeString(explicit,"test-owned path");
            Fake never=new Fake(files());JavaRuntime explicitRuntime=new JavaRuntime(never,INDEX,"linux",null,path->25);
            check(explicitRuntime.resolve(explicit,root.resolve("unused"),s->{},()->false).equals(explicit.toRealPath()),"Explicit Java path");
            check(never.reads==0,"Explicit Java does not download a runtime");
            fails(()->new JavaRuntime(never,INDEX,"linux",null,path->17).resolve(explicit,root.resolve("unused"),s->{},()->false),"Old explicit Java is rejected");
            JavaRuntime current=new JavaRuntime(never,INDEX,"linux",explicit,path->25);
            check(current.resolve(null,root.resolve("unused"),s->{},()->false).equals(explicit.toRealPath()),"Current Java 25 is reused");
            Fake source=new Fake(files());Path cache=root.resolve("cache");JavaRuntime installer=runtime(source);
            Path resolved=installer.resolve(null,cache,s->{},()->false);
            check(Arrays.equals(JAVA,Files.readAllBytes(resolved)),"Verified runtime installed");
            check(Files.isExecutable(resolved),"Executable permission retained");
            check(Files.exists(resolved.getParent().getParent().resolve("legal/LICENSE-copy")),"Internal legal link created");
            int reads=source.reads,fetches=source.fetches;
            check(installer.resolve(null,cache,s->{},()->false).equals(resolved),"Cached runtime reused");
            check(source.reads==reads && source.fetches==fetches,"Cached runtime performs no network access");
            Files.writeString(resolved,"corrupt cache");installer.resolve(null,cache,s->{},()->false);
            check(Arrays.equals(JAVA,Files.readAllBytes(resolved)) && source.fetches>fetches,"Corrupt cached launcher reinstalled");
            Fake broken=new Fake(files());broken.corrupt=true;Path damaged=root.resolve("damaged");
            Path existing=damaged.resolve("java-runtime-epsilon-linux");Files.createDirectories(existing);Files.writeString(existing.resolve("keep.txt"),"existing runtime");
            fails(()->runtime(broken).resolve(null,damaged,s->{},()->false),"Hash failure rejects runtime");
            check(Files.readString(existing.resolve("keep.txt")).equals("existing runtime"),"Failed download preserves existing cache");
            Map<String,Object> traversal=new LinkedHashMap<>(files());traversal.put("../escape",Map.of("type","directory"));
            fails(()->runtime(new Fake(traversal)).resolve(null,root.resolve("traversal"),s->{},()->false),"Manifest traversal rejected");
            check(!Files.exists(root.resolve("escape")),"Traversal cannot create external files");
            Map<String,Object> badLink=new LinkedHashMap<>(files());badLink.put("legal/outside",Map.of("type","link","target","../../../outside"));
            fails(()->runtime(new Fake(badLink)).resolve(null,root.resolve("bad-link"),s->{},()->false),"External runtime link rejected");
            AtomicBoolean cancel=new AtomicBoolean();Fake cancellable=new Fake(files());cancellable.afterFetch=()->cancel.set(true);Path cancelled=root.resolve("cancelled");
            fails(()->runtime(cancellable).resolve(null,cancelled,s->{},cancel::get),"Cancellation interrupts staged installation");
            check(!Files.exists(cancelled.resolve("java-runtime-epsilon-linux")),"Cancelled install never commits");
            Path hostJava=Path.of(System.getProperty("java.home"),"bin",System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("win")?"java.exe":"java");
            check(JavaRuntime.javaFeature(hostJava)>=17,"Actual current JVM version probe");
            System.out.println("JavaRuntime: "+checks+" checks passed");
        } finally {try(var paths=Files.walk(root)){for(Path path:paths.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(path);}}
    }
    private static JavaRuntime runtime(Fake source){return new JavaRuntime(source,INDEX,"linux",null,path->25);}
    private static Map<String,Object> files()throws Exception {
        Map<String,Object> files=new LinkedHashMap<>();files.put("bin",Map.of("type","directory"));files.put("legal",Map.of("type","directory"));
        files.put("bin/java",Map.of("type","file","executable",true,"downloads",Map.of("raw",descriptor(URI.create("https://runtime.test/java"),JAVA))));
        files.put("legal/LICENSE",Map.of("type","file","downloads",Map.of("raw",descriptor(URI.create("https://runtime.test/java"),JAVA))));
        files.put("legal/LICENSE-copy",Map.of("type","link","target","LICENSE"));return files;
    }
    private static Map<String,Object> descriptor(URI uri,byte[] bytes)throws Exception {return Map.of("url",uri.toString(),"sha1",HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(bytes)),"size",bytes.length);}
    private static void check(boolean condition,String text){checks++;if(!condition)throw new AssertionError(text);}
    @FunctionalInterface private interface Throwing {void run()throws Exception;}
    private static void fails(Throwing action,String text)throws Exception {checks++;try{action.run();throw new AssertionError(text);}catch(IOException expected){}}
}
