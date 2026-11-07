package dev.aurora.launcher.minecraft;

import dev.aurora.launcher.net.Json;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.zip.*;

/** Self-contained checks: manifests and downloads are fixtures; no account or game is launched. */
public final class MinecraftInstallerCheck {
    private static int checks;
    public static void main(String[] args)throws Exception {
        var root=Files.createTempDirectory("aurora-game-check-");
        try {rules();paths(root);installation(root);if(args.length==2)realMetadata(root,Path.of(args[0]),Path.of(args[1]));System.out.println("PASS: Minecraft installer "+checks+" checks");}
        finally {try(var all=Files.walk(root)){for(var p:all.sorted(Comparator.reverseOrder()).toList())Files.deleteIfExists(p);}}
    }
    private static void rules()throws Exception {
        var win=new MinecraftInstaller.Platform("windows","amd64","10.0.22631");
        var linux=new MinecraftInstaller.Platform("linux","amd64","6.8.0");
        require(MinecraftInstaller.allowed(Map.of(),linux),"Libraries without rules are enabled");
        Map<String,Object> os=Map.of("rules",List.of(Map.of("action","allow","os",Map.of("name","windows"))));
        require(MinecraftInstaller.allowed(os,win) && !MinecraftInstaller.allowed(os,linux),"OS allow rules");
        Map<String,Object> features=Map.of("rules",List.of(Map.of("action","allow","features",Map.of("is_demo_user",true))));
        require(!MinecraftInstaller.allowed(features,win),"Paid account is never launched as demo");
        require(MinecraftInstaller.arguments(List.of("plain",Map.of("rules",List.of(Map.of("action","allow","os",Map.of("name","windows"))),"value",List.of("a","b"))),win).equals(List.of("plain","a","b")),"Argument lists are flattened by rules");
        Map<String,Object> gc=Map.of("rules",List.of(Map.of("action","allow","os",Map.of("name","windows","versionRange",Map.of("min","10.0.17134")))));
        require(MinecraftInstaller.allowed(gc,win),"New Minecraft Windows versionRange is honored");
        require(MinecraftInstaller.mavenPath("net.fabricmc:fabric-loader:0.20.1").equals("net/fabricmc/fabric-loader/0.20.1/fabric-loader-0.20.1.jar"),"Maven profile libraries resolve");
        require(MinecraftInstaller.mavenPath("org.lwjgl:lwjgl:3.4.3:natives-windows-arm64").endsWith("lwjgl-3.4.3-natives-windows-arm64.jar"),"Native classifier coordinates resolve");
    }
    private static void paths(Path root)throws Exception {
        for(String path:List.of("../escape","/outside","C:/outside","a\\..\\escape")) {
            boolean rejected=false;try{MinecraftInstaller.safe(root,path);}catch(IOException error){rejected=true;}require(rejected,"Unsafe path refused");
        }
        Path archive=root.resolve("bad-native.jar");try(var zip=new ZipOutputStream(Files.newOutputStream(archive))){zip.putNextEntry(new ZipEntry("../escaped.dll"));zip.write(1);zip.closeEntry();}
        boolean rejected=false;try{MinecraftInstaller.extractNatives(archive,root.resolve("natives"),List.of(),()->false);}catch(IOException error){rejected=true;}
        require(rejected && !Files.exists(root.resolve("escaped.dll")),"ZIP traversal never escapes native directory");
    }
    private static void installation(Path root)throws Exception {
        Map<URI,byte[]> payloads=new java.util.concurrent.ConcurrentHashMap<>();AtomicInteger binaryReads=new AtomicInteger();
        URI versions=URI.create("https://fixture.invalid/versions"),fabricUri=URI.create("https://fixture.invalid/fabric");
        byte[] client="client jar".getBytes(StandardCharsets.UTF_8),library="base lib".getBytes(StandardCharsets.UTF_8),loader="fabric lib".getBytes(StandardCharsets.UTF_8),asset="sound".getBytes(StandardCharsets.UTF_8);
        String hash=MinecraftInstaller.digest(asset,"SHA-1");URI assetUri=URI.create("https://fixture.invalid/assets/"+hash.substring(0,2)+"/"+hash);
        payloads.put(assetUri,asset);
        Map<String,Object> assetIndex=Map.of("objects",Map.of("sound/test.ogg",Map.of("hash",hash,"size",asset.length)));
        byte[] index=json(assetIndex);payloads.put(URI.create("https://fixture.invalid/index"),index);
        var base=new LinkedHashMap<String,Object>();base.put("id","26.3");base.put("type","release");base.put("javaVersion",Map.of("majorVersion",25,"component","java-runtime-epsilon"));
        base.put("downloads",Map.of("client",artifact("client",client,payloads)));
        base.put("assetIndex",new LinkedHashMap<>(artifact("index",index,payloads)));((Map<String,Object>)base.get("assetIndex")).put("id","34");
        var lib=new LinkedHashMap<String,Object>(artifact("base-lib",library,payloads));lib.put("path","test/base/1/base-1.jar");
        base.put("libraries",List.of(Map.of("name","test:base:1","downloads",Map.of("artifact",lib))));
        base.put("arguments",Map.of("default-user-jvm",List.of(Map.of("value",List.of("-Xms2G","-Xmx4G","-XX:+UseStringDeduplication"))),
            "jvm",List.of("-Djava.library.path=${natives_directory}/java","-cp","${classpath}"),
            "game",List.of("--username","${auth_player_name}","--uuid","${auth_uuid}","--accessToken","${auth_access_token}","--gameDir","${game_directory}",Map.of("rules",List.of(Map.of("action","allow","features",Map.of("is_demo_user",true))),"value","--demo"))));
        byte[] baseBytes=json(base);payloads.put(URI.create("https://fixture.invalid/base"),baseBytes);
        payloads.put(versions,json(Map.of("versions",List.of(Map.of("id","26.3","url","https://fixture.invalid/base","sha1",MinecraftInstaller.digest(baseBytes,"SHA-1"))))));
        payloads.put(URI.create("https://fixture.invalid/repository/test/loader/1/loader-1.jar"),loader);
        payloads.put(fabricUri,json(Map.of("id","fabric-loader-0.20.1-26.3","inheritsFrom","26.3","mainClass","net.fabricmc.loader.impl.launch.knot.KnotClient",
            "arguments",Map.of("jvm",List.of("-DFabricMcEmu= net.minecraft.client.main.Main "),"game",List.of()),
            "libraries",List.of(Map.of("name","test:loader:1","url","https://fixture.invalid/repository/","sha256",MinecraftInstaller.digest(loader,"SHA-256"),"size",loader.length)))));
        MinecraftInstaller.Downloader download=(uri,max,cancel)->{
            if(cancel.getAsBoolean())throw new InterruptedIOException();byte[] bytes=payloads.get(uri);if(bytes==null)throw new IOException("Unexpected fixture request "+uri);if(!uri.equals(versions) && !uri.equals(fabricUri))binaryReads.incrementAndGet();return bytes;
        };
        var installer=new MinecraftInstaller(download,new MinecraftInstaller.Endpoints(versions,fabricUri,URI.create("https://fixture.invalid/assets/")),new MinecraftInstaller.Platform("linux","amd64","6.8"));
        Path game=root.resolve("game with spaces");var installed=installer.prepare(game,p->{},()->false);
        require(installed.requiredJava()==25 && installed.classpath().size()==3,"Base, Fabric and client installed");
        require(Files.readAllBytes(game.resolve("assets/objects/"+hash.substring(0,2)+"/"+hash)).length==asset.length,"Assets verified and installed");
        Path java=root.resolve("java with spaces");Files.writeString(java,"fixture executable");
        var identity=new GameIdentity("Player","0123456789abcdef0123456789abcdef","SECRET_TOKEN","123");
        var command=installed.command(java,3072,identity);
        require(command.get(0).equals(java.toAbsolutePath().toString()),"Executable path remains one argument");
        require(command.contains("-Xmx3072M") && !command.contains("-Xmx4G"),"Configured heap replaces inherited heap");
        require(command.contains("net.fabricmc.loader.impl.launch.knot.KnotClient") && command.contains("-DFabricMcEmu= net.minecraft.client.main.Main "),"Fabric main class and spaced JVM argument preserved");
        require(command.contains(game.toRealPath().toString()) && !command.contains("--demo"),"Instance path is one literal argument; demo disabled");
        require(!InstalledGame.redacted(command).toString().contains("SECRET_TOKEN") && !identity.toString().contains("SECRET_TOKEN"),"Credentials redacted in diagnostics");
        int before=binaryReads.get();installer.prepare(game,p->{},()->false);require(binaryReads.get()==before,"Verified artifacts reused without binary re-download");
        Files.writeString(game.resolve("libraries/test/base/1/base-1.jar"),"corrupt");installer.prepare(game,p->{},()->false);
        require(Arrays.equals(Files.readAllBytes(game.resolve("libraries/test/base/1/base-1.jar")),library),"Corrupt cached artifact is repaired atomically");
        payloads.put(URI.create("https://fixture.invalid/client"),"bad client".getBytes(StandardCharsets.UTF_8));Files.delete(game.resolve("versions/26.3/26.3.jar"));
        boolean rejected=false;try{installer.prepare(game,p->{},()->false);}catch(IOException error){rejected=true;}
        require(rejected && !Files.exists(game.resolve("versions/26.3/26.3.jar")),"Hash mismatch cannot publish a game JAR");
    }
    @SuppressWarnings("unchecked") private static void realMetadata(Path root,Path baseFile,Path fabricFile)throws Exception {
        var base=(Map<String,Object>)Json.parse(Files.readString(baseFile));var fabric=(Map<String,Object>)Json.parse(Files.readString(fabricFile));
        var baseArgs=(Map<String,Object>)base.get("arguments");var fabricArgs=(Map<String,Object>)fabric.get("arguments");
        Path java=root.resolve("metadata-java");Files.writeString(java,"fixture");
        for(String os:List.of("windows","osx","linux")) {
            var platform=new MinecraftInstaller.Platform(os,"amd64","10.0.22631");
            var jvm=new ArrayList<>(MinecraftInstaller.arguments(baseArgs.get("default-user-jvm"),platform));
            jvm.addAll(MinecraftInstaller.arguments(baseArgs.get("jvm"),platform));jvm.addAll(MinecraftInstaller.arguments(fabricArgs.get("jvm"),platform));
            var game=new ArrayList<>(MinecraftInstaller.arguments(baseArgs.get("game"),platform));game.addAll(MinecraftInstaller.arguments(fabricArgs.get("game"),platform));
            var installed=new InstalledGame(root,root.resolve("natives"),(String)fabric.get("id"),"34",(String)fabric.get("mainClass"),"release",25,List.of(root.resolve("client.jar")),jvm,game);
            var command=installed.command(java,4096,new GameIdentity("Player","0123456789abcdef0123456789abcdef","secret","123","public-app-id"));
            require(command.stream().noneMatch(s->s.contains("${")),"Real26.3+Fabric metadata resolves all placeholders on "+os);
            require(command.contains("public-app-id"),"Configured Microsoft application ID reaches real game command");
        }
    }
    private static Map<String,Object> artifact(String path,byte[] bytes,Map<URI,byte[]> payloads)throws Exception {
        URI uri=URI.create("https://fixture.invalid/"+path);payloads.put(uri,bytes);
        return Map.of("url",uri.toString(),"sha1",MinecraftInstaller.digest(bytes,"SHA-1"),"size",bytes.length);
    }
    private static byte[] json(Object object){return Json.stringify(object).getBytes(StandardCharsets.UTF_8);}
    private static void require(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
}
