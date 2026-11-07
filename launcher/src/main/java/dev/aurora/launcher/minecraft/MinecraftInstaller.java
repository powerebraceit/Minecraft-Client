package dev.aurora.launcher.minecraft;

import dev.aurora.launcher.net.Json;
import java.io.*;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.*;
import java.time.Duration;
import java.util.*;
import java.util.function.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.*;
import java.util.zip.*;

/**
 * Installs official Minecraft artifacts and a Fabric profile into one selected instance.
 * Every game/library/asset download is hash-checked before atomic publication. No account
 * credentials are written into manifests or launch profiles.
 */
public final class MinecraftInstaller {
    public static final String MINECRAFT="26.3",FABRIC="0.20.1";
    public record Progress(int percent,String message) { }
    public record Platform(String os,String arch,String version) {
        public static Platform current() {
            String name=System.getProperty("os.name","").toLowerCase(Locale.ROOT);
            return new Platform(name.contains("win")?"windows":name.contains("mac")?"osx":"linux",System.getProperty("os.arch",""),System.getProperty("os.version",""));
        }
    }
    public record Endpoints(URI versions,URI fabricProfile,URI assets) {
        public static Endpoints official(){return new Endpoints(URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json"),
            URI.create("https://meta.fabricmc.net/v2/versions/loader/"+MINECRAFT+"/"+FABRIC+"/profile/json"),URI.create("https://resources.download.minecraft.net/"));}
    }
    @FunctionalInterface public interface Downloader {byte[] get(URI uri,int maxBytes,BooleanSupplier cancelled)throws IOException;}
    private final Downloader downloader;
    private final Endpoints endpoints;
    private final Platform platform;
    public MinecraftInstaller(){this(new HttpDownloader(),Endpoints.official(),Platform.current());}
    public MinecraftInstaller(Downloader downloader,Endpoints endpoints,Platform platform){this.downloader=Objects.requireNonNull(downloader);this.endpoints=endpoints;this.platform=platform;}

    public InstalledGame prepare(Path selectedDirectory,Consumer<Progress> progress,BooleanSupplier cancelled)throws IOException {
        Objects.requireNonNull(selectedDirectory);Objects.requireNonNull(progress);Objects.requireNonNull(cancelled);check(cancelled);
        Files.createDirectories(selectedDirectory);Path directory=selectedDirectory.toRealPath();
        progress.accept(new Progress(1,"Hämtar Minecraft "+MINECRAFT));
        Map<String,Object> versions=readJson(endpoints.versions(),2*1024*1024,cancelled);
        Map<String,Object> versionEntry=arr(versions.get("versions")).stream().map(MinecraftInstaller::obj).filter(v->MINECRAFT.equals(str(v,"id"))).findFirst().orElseThrow(()->new IOException("Minecraft "+MINECRAFT+" saknas i den officiella versionslistan"));
        String versionId=MINECRAFT;Path versionDir=safe(directory,"versions/"+versionId);
        Path versionJson=safe(versionDir,versionId+".json");
        download(URI.create(str(versionEntry,"url")),versionJson,str(versionEntry,"sha1"),"SHA-1",-1,8*1024*1024,cancelled);
        var base=parse(Files.readString(versionJson));
        if(!MINECRAFT.equals(str(base,"id")))throw new IOException("Minecraft-versionens metadata stämmer inte");
        int javaMajor=(int)number(obj(base.get("javaVersion")),"majorVersion",25);
        Map<String,Object> client=obj(obj(base.get("downloads")).get("client"));Path clientJar=safe(versionDir,versionId+".jar");
        downloadArtifact(client,clientJar,cancelled);

        progress.accept(new Progress(8,"Installerar Fabric "+FABRIC));
        Map<String,Object> fabric=readJson(endpoints.fabricProfile(),4*1024*1024,cancelled);
        if(!MINECRAFT.equals(str(fabric,"inheritsFrom")))throw new IOException("Fabric-profilen gäller fel Minecraft-version");
        String profileId=str(fabric,"id");if(!profileId.matches("[A-Za-z0-9._+-]+"))throw new IOException("Ogiltigt profilnamn");
        atomic(safe(directory,"versions/"+profileId+"/"+profileId+".json"),Json.stringify(fabric).getBytes(StandardCharsets.UTF_8));
        Path natives=safe(directory,"versions/"+profileId+"/natives");
        for(String part:List.of("java","jna","lwjgl","netty"))Files.createDirectories(safe(natives,part));

        var libraries=new LinkedHashMap<String,Map<String,Object>>();
        for(Object entry:arr(base.get("libraries")))mergeLibrary(libraries,obj(entry));
        for(Object entry:arr(fabric.get("libraries")))mergeLibrary(libraries,obj(entry));
        List<Path> classpath=new ArrayList<>();int index=0;
        for(var library:libraries.values()) {
            check(cancelled);if(!allowed(library,platform))continue;
            String coordinate=str(library,"name");var downloads=obj(library.get("downloads"));var artifact=obj(downloads.get("artifact"));
            if(!artifact.isEmpty()) {
                Path file=safe(directory,"libraries/"+str(artifact,"path"));downloadArtifact(artifact,file,cancelled);classpath.add(file);
            }else {
                String path=mavenPath(coordinate);String repo=optional(library,"url","https://libraries.minecraft.net/");
                URI uri=URI.create(repo.endsWith("/")?repo+path:repo+"/"+path);
                String hash=optional(library,"sha256","");String algorithm="SHA-256";
                if(hash.isBlank()){hash=optional(library,"sha1","");algorithm="SHA-1";}
                if(hash.isBlank()) {hash=checksum(uri,cancelled);algorithm="SHA-256";}
                Path file=safe(directory,"libraries/"+path);download(uri,file,hash,algorithm,number(library,"size",-1),512*1024*1024,cancelled);classpath.add(file);
            }
            Map<String,Object> nativeNames=obj(library.get("natives"));String classifier=optional(nativeNames,platform.os(),"");
            if(!classifier.isEmpty()) {
                classifier=classifier.replace("${arch}",platform.arch().contains("64")?"64":"32");
                Map<String,Object> nativeArtifact=obj(obj(downloads.get("classifiers")).get(classifier));
                if(nativeArtifact.isEmpty())throw new IOException("Native-bibliotek saknas för "+coordinate);
                Path nativeJar=safe(directory,"libraries/"+str(nativeArtifact,"path"));downloadArtifact(nativeArtifact,nativeJar,cancelled);
                List<String> exclude=arr(obj(library.get("extract")).get("exclude")).stream().map(String::valueOf).toList();
                extractNatives(nativeJar,safe(natives,"java"),exclude,cancelled);
            }
            progress.accept(new Progress(10+(++index*35/Math.max(1,libraries.size())),"Verifierar spelbibliotek "+index+" / "+libraries.size()));
        }
        classpath.add(clientJar);
        Map<String,Object> assetIndex=obj(base.get("assetIndex"));String assetsId=str(assetIndex,"id");
        if(!assetsId.matches("[A-Za-z0-9._-]+"))throw new IOException("Ogiltigt resursindex");
        Path indexFile=safe(directory,"assets/indexes/"+assetsId+".json");downloadArtifact(assetIndex,indexFile,cancelled);
        Map<String,Object> assets=parse(Files.readString(indexFile));var objects=obj(assets.get("objects"));index=0;
        var uniqueAssets=new LinkedHashMap<String,Map.Entry<String,Object>>();
        for(var entry:objects.entrySet())uniqueAssets.putIfAbsent(str(obj(entry.getValue()),"hash"),entry);
        var completed=new AtomicInteger();
        ExecutorService assetWorkers=Executors.newFixedThreadPool(6,r->{var t=new Thread(r,"aurora-assets");t.setDaemon(true);return t;});
        var completion=new ExecutorCompletionService<Void>(assetWorkers);
        try {
            for(var asset:uniqueAssets.values())completion.submit(()->{
                check(cancelled);Map<String,Object> descriptor=obj(asset.getValue());String hash=str(descriptor,"hash");
                if(!hash.matches("[0-9a-f]{40}"))throw new IOException("Ogiltig resurshash");
                String relative=hash.substring(0,2)+"/"+hash;Path file=safe(directory,"assets/objects/"+relative);
                download(endpoints.assets().resolve(relative),file,hash,"SHA-1",number(descriptor,"size",-1),128*1024*1024,cancelled);
                int done=completed.incrementAndGet();
                if(done%32==0 || done==uniqueAssets.size())progress.accept(new Progress(45+done*50/Math.max(1,uniqueAssets.size()),"Hämtar resurser "+done+" / "+uniqueAssets.size()));
                return null;
            });
            for(int i=0;i<uniqueAssets.size();i++) {
                check(cancelled);
                try {
                    Future<Void> finished;
                    while((finished=completion.poll(200,TimeUnit.MILLISECONDS))==null)check(cancelled);
                    finished.get();
                }
                catch(InterruptedException error){Thread.currentThread().interrupt();throw new InterruptedIOException("Installationen avbröts");}
                catch(ExecutionException error){if(error.getCause() instanceof IOException io)throw io;throw new IOException("En spelresurs kunde inte installeras",error.getCause());}
            }
        }finally {assetWorkers.shutdownNow();}
        if(Boolean.TRUE.equals(assets.get("virtual")) || Boolean.TRUE.equals(assets.get("map_to_resources"))) {
            String prefix=Boolean.TRUE.equals(assets.get("map_to_resources"))?"resources/":"assets/virtual/"+assetsId+"/";
            for(var asset:objects.entrySet()) {
                check(cancelled);String hash=str(obj(asset.getValue()),"hash");
                atomic(safe(directory,prefix+asset.getKey()),Files.readAllBytes(safe(directory,"assets/objects/"+hash.substring(0,2)+"/"+hash)));
            }
        }
        Map<String,Object> baseArgs=obj(base.get("arguments")),fabricArgs=obj(fabric.get("arguments"));
        List<String> jvm=new ArrayList<>(arguments(baseArgs.get("default-user-jvm"),platform));
        jvm.addAll(arguments(baseArgs.get("jvm"),platform));jvm.addAll(arguments(fabricArgs.get("jvm"),platform));
        List<String> game=new ArrayList<>(arguments(baseArgs.get("game"),platform));game.addAll(arguments(fabricArgs.get("game"),platform));
        if(!jvm.contains("${classpath}")){jvm.add("-cp");jvm.add("${classpath}");}
        progress.accept(new Progress(100,"Minecraft och Fabric är installerade"));
        return new InstalledGame(directory,natives,profileId,assetsId,str(fabric,"mainClass"),optional(base,"type","release"),javaMajor,classpath,jvm,game);
    }

    /** Ordered allow/disallow rules follow Mojang's manifest semantics; absent features are false. */
    public static boolean allowed(Map<String,Object> entry,Platform platform) {
        if(!entry.containsKey("rules"))return true;
        boolean allowed=false;
        for(Object value:arr(entry.get("rules"))) {
            Map<String,Object> rule=obj(value),os=obj(rule.get("os"));boolean match=true;
            if(os.containsKey("name"))match&=platform.os().equals(str(os,"name"));
            if(os.containsKey("arch"))match&=platform.arch().matches(str(os,"arch"));
            if(os.containsKey("version"))match&=platform.version().matches(str(os,"version"));
            Map<String,Object> range=obj(os.get("versionRange"));
            if(range.containsKey("min"))match&=compareVersions(platform.version(),str(range,"min"))>=0;
            if(range.containsKey("max"))match&=compareVersions(platform.version(),str(range,"max"))<0;
            for(Object wanted:obj(rule.get("features")).values())if(Boolean.TRUE.equals(wanted))match=false;
            if(match)allowed="allow".equals(str(rule,"action"));
        }
        return allowed;
    }
    public static List<String> arguments(Object value,Platform platform) {
        List<String> out=new ArrayList<>();
        for(Object argument:arr(value)) {
            if(argument instanceof String s)out.add(s);
            else {Map<String,Object> rule=obj(argument);if(!allowed(rule,platform))continue;Object supplied=rule.get("value");if(supplied instanceof String s)out.add(s);else for(Object item:arr(supplied))out.add(String.valueOf(item));}
        }
        return out;
    }
    public static String mavenPath(String coordinate)throws IOException {
        String[] extension=coordinate.split("@",-1),parts=extension[0].split(":",-1);
        if(parts.length<3 || parts.length>4 || extension.length>2)throw new IOException("Ogiltigt Maven-bibliotek");
        for(String part:parts)if(!part.matches("[A-Za-z0-9_.+\\-]+") || part.contains(".."))throw new IOException("Ogiltigt Maven-bibliotek");
        String ext=extension.length==2?extension[1]:"jar";if(!ext.matches("[A-Za-z0-9]+"))throw new IOException("Ogiltig bibliotekstyp");
        return parts[0].replace('.','/')+"/"+parts[1]+"/"+parts[2]+"/"+parts[1]+"-"+parts[2]+(parts.length==4?"-"+parts[3]:"")+"."+ext;
    }
    private void mergeLibrary(Map<String,Map<String,Object>> out,Map<String,Object> library)throws IOException {
        String name=str(library,"name");String[] parts=name.split(":");if(parts.length<3)throw new IOException("Ogiltigt bibliotek");
        String key=parts[0]+":"+parts[1]+(parts.length>3?":"+parts[3]:"");out.put(key,library);
    }
    private String checksum(URI artifact,BooleanSupplier cancelled)throws IOException {
        String checksum=new String(downloader.get(URI.create(artifact+".sha256"),1024,cancelled),StandardCharsets.US_ASCII).trim().split("\\s+")[0];
        if(!checksum.matches("[0-9a-fA-F]{64}"))throw new IOException("Bibliotekets kontrollsumma saknas");return checksum;
    }
    private Map<String,Object> readJson(URI uri,int maximum,BooleanSupplier cancelled)throws IOException {return parse(new String(downloader.get(uri,maximum,cancelled),StandardCharsets.UTF_8));}
    private static Map<String,Object> parse(String text)throws IOException {
        try{return obj(Json.parse(text));}catch(RuntimeException error){throw new IOException("Ogiltig spelmetadata",error);}
    }
    private void downloadArtifact(Map<String,Object> descriptor,Path destination,BooleanSupplier cancelled)throws IOException {
        download(URI.create(str(descriptor,"url")),destination,str(descriptor,"sha1"),"SHA-1",number(descriptor,"size",-1),512*1024*1024,cancelled);
    }
    private void download(URI uri,Path destination,String expected,String algorithm,long size,int maximum,BooleanSupplier cancelled)throws IOException {
        check(cancelled);if(!expected.matches(algorithm.equals("SHA-1")?"[0-9a-fA-F]{40}":"[0-9a-fA-F]{64}"))throw new IOException("Kontrollsumman saknas för en spelfil");
        if(Files.isRegularFile(destination,LinkOption.NOFOLLOW_LINKS) && Files.size(destination)<=maximum && (size<0 || Files.size(destination)==size) && fileDigest(destination,algorithm).equalsIgnoreCase(expected))return;
        byte[] bytes=downloader.get(uri,maximum,cancelled);check(cancelled);
        if(size>=0 && bytes.length!=size || !digest(bytes,algorithm).equalsIgnoreCase(expected))throw new IOException("En nedladdad spelfil hade fel kontrollsumma eller storlek");
        atomic(destination,bytes);
    }
    public static void extractNatives(Path archive,Path destination,List<String> excluded,BooleanSupplier cancelled)throws IOException {
        Files.createDirectories(destination);long expanded=0;int entries=0;
        try(ZipInputStream zip=new ZipInputStream(Files.newInputStream(archive))) {
            for(ZipEntry entry;(entry=zip.getNextEntry())!=null;) {
                check(cancelled);if(++entries>10000)throw new IOException("För många filer i native-arkivet");
                String name=entry.getName();Path output=safe(destination,name);
                if(entry.isDirectory() || name.startsWith("META-INF/") || excluded.stream().anyMatch(name::startsWith))continue;
                var data=new ByteArrayOutputStream();byte[] buffer=new byte[65536];
                for(int count;(count=zip.read(buffer))!=-1;) {check(cancelled);expanded+=count;if(expanded>256L*1024*1024)throw new IOException("Native-arkivet är för stort");data.write(buffer,0,count);}
                atomic(output,data.toByteArray());
            }
        }
    }
    public static Path safe(Path root,String relative)throws IOException {
        String portable=relative.replace('\\','/');
        if(portable.startsWith("/") || portable.indexOf(':')>=0 || portable.indexOf('\0')>=0 || Arrays.asList(portable.split("/")).contains(".."))throw new IOException("Osäker sökväg i spelmetadata");
        Path base=root.toAbsolutePath().normalize(),result=base.resolve(portable).normalize();
        if(!result.startsWith(base))throw new IOException("Spelfilen ligger utanför instansen");
        for(Path cursor=result;cursor!=null && cursor.startsWith(base);cursor=cursor.getParent())if(Files.isSymbolicLink(cursor))throw new IOException("Symboliska länkar tillåts inte i spelcachen");
        return result;
    }
    private static void atomic(Path destination,byte[] bytes)throws IOException {
        if(Files.isSymbolicLink(destination))throw new IOException("Spelfilen är en symbolisk länk");
        Files.createDirectories(destination.getParent());Path temporary=Files.createTempFile(destination.getParent(),".aurora-",".part");
        try {Files.write(temporary,bytes);try{Files.move(temporary,destination,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING);}catch(AtomicMoveNotSupportedException e){Files.move(temporary,destination,StandardCopyOption.REPLACE_EXISTING);}}
        finally {Files.deleteIfExists(temporary);}
    }
    private static String fileDigest(Path file,String algorithm)throws IOException {
        try {
            var digest=MessageDigest.getInstance(algorithm);byte[] buffer=new byte[65536];
            try(InputStream in=Files.newInputStream(file)){for(int count;(count=in.read(buffer))!=-1;)digest.update(buffer,0,count);}
            return HexFormat.of().formatHex(digest.digest());
        }catch(NoSuchAlgorithmException error){throw new IOException(error);}
    }
    public static String digest(byte[] data,String algorithm)throws IOException {
        try{return HexFormat.of().formatHex(MessageDigest.getInstance(algorithm).digest(data));}catch(NoSuchAlgorithmException e){throw new IOException(e);}
    }
    private static void check(BooleanSupplier cancelled)throws IOException {if(cancelled.getAsBoolean() || Thread.currentThread().isInterrupted())throw new InterruptedIOException("Installationen avbröts");}
    @SuppressWarnings("unchecked") private static Map<String,Object> obj(Object value){return value instanceof Map<?,?>?(Map<String,Object>)value:Map.of();}
    @SuppressWarnings("unchecked") private static List<Object> arr(Object value){return value instanceof List<?>?(List<Object>)value:List.of();}
    private static String str(Map<String,Object> obj,String key){Object value=obj.get(key);if(!(value instanceof String text) || text.isBlank())throw new IllegalArgumentException("Metadata saknar "+key);return text;}
    private static String optional(Map<String,Object> obj,String key,String fallback){Object value=obj.get(key);return value instanceof String text?text:fallback;}
    private static long number(Map<String,Object> obj,String key,long fallback){Object value=obj.get(key);return value instanceof Number n?n.longValue():fallback;}
    private static int compareVersions(String left,String right){String[] a=left.split("[._-]"),b=right.split("[._-]");for(int i=0;i<Math.max(a.length,b.length);i++){int x=i<a.length?numeric(a[i]):0,y=i<b.length?numeric(b[i]):0;if(x!=y)return Integer.compare(x,y);}return 0;}
    private static int numeric(String value){try{return Integer.parseInt(value.replaceAll("[^0-9].*",""));}catch(NumberFormatException e){return 0;}}

    private static final class HttpDownloader implements Downloader {
        private final HttpClient client=HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NORMAL).connectTimeout(Duration.ofSeconds(20)).build();
        @Override public byte[] get(URI uri,int maximum,BooleanSupplier cancelled)throws IOException {
            if(!"https".equalsIgnoreCase(uri.getScheme()))throw new IOException("Spelfiler kräver HTTPS");
            check(cancelled);
            try {
                var response=client.send(HttpRequest.newBuilder(uri).timeout(Duration.ofMinutes(3)).header("User-Agent","AuroraLauncher/1.0.0").GET().build(),HttpResponse.BodyHandlers.ofInputStream());
                if(response.statusCode()!=200){response.body().close();throw new IOException("Spelfilen kunde inte hämtas (HTTP "+response.statusCode()+")");}
                try(InputStream in=response.body();var out=new ByteArrayOutputStream()) {
                    byte[] buffer=new byte[65536];for(int count;(count=in.read(buffer))!=-1;){check(cancelled);if(out.size()+count>maximum)throw new IOException("Spelfilen är för stor");out.write(buffer,0,count);}return out.toByteArray();
                }
            }catch(InterruptedException e){Thread.currentThread().interrupt();throw new InterruptedIOException("Installationen avbröts");}
        }
    }
}
