package dev.aurora.launcher.minecraft;

import java.io.File;
import java.nio.file.*;
import java.util.*;
import java.util.regex.*;

/** Verified manifest installation. Commands are argument lists, never shell command strings. */
public final class InstalledGame {
    private final Path directory,natives;
    private final String version,assetIndex,mainClass,type;
    private final int requiredJava;
    private final List<Path> classpath;
    private final List<String> jvm,game;
    InstalledGame(Path directory,Path natives,String version,String assetIndex,String mainClass,String type,int requiredJava,
                  List<Path> classpath,List<String> jvm,List<String> game) {
        this.directory=directory;this.natives=natives;this.version=version;this.assetIndex=assetIndex;this.mainClass=mainClass;this.type=type;
        this.requiredJava=requiredJava;this.classpath=List.copyOf(classpath);this.jvm=List.copyOf(jvm);this.game=List.copyOf(game);
    }
    public Path directory(){return directory;}
    public String version(){return version;}
    public int requiredJava(){return requiredJava;}
    public List<Path> classpath(){return classpath;}
    public List<String> command(Path javaExecutable,int memoryMb,GameIdentity identity) {
        Objects.requireNonNull(identity);
        if(javaExecutable==null || !Files.isRegularFile(javaExecutable))throw new IllegalArgumentException("Välj en Java "+requiredJava+"-installation");
        if(memoryMb<1024 || memoryMb>32768)throw new IllegalArgumentException("Minnet måste vara mellan 1024 och 32768 MB");
        var replacements=new HashMap<String,String>();
        replacements.put("auth_player_name",identity.name());replacements.put("auth_uuid",identity.uuid());replacements.put("auth_access_token",identity.accessToken());
        replacements.put("auth_xuid",identity.xuid());replacements.put("clientid",identity.clientId());replacements.put("user_type","msa");replacements.put("user_properties","{}");
        replacements.put("version_name",version);replacements.put("version_type",type);replacements.put("game_directory",directory.toString());
        replacements.put("assets_root",directory.resolve("assets").toString());replacements.put("assets_index_name",assetIndex);
        replacements.put("natives_directory",natives.toString());replacements.put("launcher_name","Aurora");replacements.put("launcher_version","1.0.1");
        replacements.put("library_directory",directory.resolve("libraries").toString());replacements.put("classpath_separator",File.pathSeparator);
        replacements.put("classpath",String.join(File.pathSeparator,classpath.stream().map(Path::toString).toList()));
        var command=new ArrayList<String>();command.add(javaExecutable.toAbsolutePath().toString());command.add("-Xms512M");command.add("-Xmx"+memoryMb+"M");
        for(String argument:jvm)if(!argument.startsWith("-Xms") && !argument.startsWith("-Xmx"))command.add(substitute(argument,replacements));
        command.add(mainClass);for(String argument:game)command.add(substitute(argument,replacements));
        return List.copyOf(command);
    }
    /** Safe diagnostic representation; actual ProcessBuilder arguments contain the access token. */
    public static List<String> redacted(List<String> arguments) {
        var safe=new ArrayList<>(arguments);
        for(int i=0;i<safe.size();i++)if(safe.get(i).equals("--accessToken") && i+1<safe.size())safe.set(++i,"<redacted>");
        return List.copyOf(safe);
    }
    private static String substitute(String argument,Map<String,String> replacements) {
        Matcher matcher=Pattern.compile("\\$\\{([^}]+)}").matcher(argument);StringBuffer out=new StringBuffer();
        while(matcher.find()) {
            String value=replacements.get(matcher.group(1));
            if(value==null)throw new IllegalArgumentException("Okänt Minecraft-argument: "+matcher.group(1));
            matcher.appendReplacement(out,Matcher.quoteReplacement(value));
        }
        matcher.appendTail(out);return out.toString();
    }
}
