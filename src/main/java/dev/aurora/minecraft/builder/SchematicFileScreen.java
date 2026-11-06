package dev.aurora.minecraft.builder;

import dev.aurora.minecraft.AuroraClient;
import dev.aurora.minecraft.MinecraftCanvas;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.client.gui.screens.Screen;
import net.minecraft.client.input.MouseButtonEvent;
import net.minecraft.network.chat.Component;
import com.mojang.blaze3d.platform.InputConstants;

/** In-game file chooser on the player's own machine; no web uploads, desktop service or mod dependency. */
public final class SchematicFileScreen extends Screen {
    private final Screen parent;
    private final AuroraClient client;
    private final boolean startAfterSelection;
    private Path directory;
    private EditBox address;
    private List<Path> entries=List.of();
    private String status="Choose a .litematic file";
    private int scroll,generation;
    private boolean loading;
    public SchematicFileScreen(Screen parent,AuroraClient client) {this(parent,client,false);}
    public SchematicFileScreen(Screen parent,AuroraClient client,boolean startAfterSelection) {
        super(Component.literal("Open schematic"));this.parent=parent;this.client=client;this.startAfterSelection=startAfterSelection;
        directory=minecraft.gameDirectory.toPath().resolve("schematics").toAbsolutePath();
        String previous=client.config().option("auto_builder","last_file","");
        try { if(!previous.isBlank() && Files.isDirectory(Path.of(previous).toAbsolutePath().getParent()))directory=Path.of(previous).toAbsolutePath().getParent(); }
        catch(InvalidPathException ignored) { }
    }
    public List<Path> entries() { return entries; }
    @Override protected void init() {
        address=addRenderableWidget(new EditBox(font,12,35,Math.max(80,width-120),20,Component.literal("Folder or file path")));
        address.setMaxLength(4096);address.setValue(directory.toString());
        addRenderableWidget(Button.builder(Component.literal("Go"),button->{try {
            Path entered=Path.of(address.getValue());Path path=(entered.isAbsolute()?entered:directory.resolve(entered)).toAbsolutePath();if(Files.isDirectory(path))browse(path);else select(path);
        }catch(RuntimeException e){status="Invalid path";}}).bounds(width-100,35,40,20).build());
        addRenderableWidget(Button.builder(Component.literal("Up"),button->{if(directory.getParent()!=null)browse(directory.getParent());}).bounds(width-55,35,42,20).build());
        addRenderableWidget(Button.builder(Component.literal("Home"),button->browse(Path.of(System.getProperty("user.home")))).bounds(12,height-28,55,20).build());
        addRenderableWidget(Button.builder(Component.literal("Back"),button->onClose()).bounds(width-67,height-28,55,20).build());
        browse(directory);
    }
    public void browse(Path folder) {
        if(loading)return;
        int request=++generation;status="Reading folder...";scroll=0;
        CompletableFuture.supplyAsync(()->{
            try(var stream=Files.list(folder)) {
                return stream.filter(p->Files.isDirectory(p) || p.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".litematic"))
                    .sorted(Comparator.comparing((Path p)->!Files.isDirectory(p)).thenComparing(p->p.getFileName().toString(),String.CASE_INSENSITIVE_ORDER)).limit(501).toList();
            }catch(Exception e){throw new java.util.concurrent.CompletionException(e);}
        }).whenComplete((files,error)->minecraft.execute(()->{
            if(request!=generation)return;
            if(error!=null){status="Cannot read this folder";entries=List.of();return;}
            directory=folder.toAbsolutePath();address.setValue(directory.toString());entries=files;scroll=0;
            status=files.size()>500?"First 500 entries shown; use the path field for more":"Click a file to load it / folders open with one click";
        }));
    }
    private void select(Path file) {
        if(loading)return;
        loading=true;int request=++generation;status="Loading "+file.getFileName()+"...";
        CompletableFuture.supplyAsync(()->{try{return DirectSchematic.read(file);}catch(Exception e){throw new java.util.concurrent.CompletionException(e);}})
            .whenComplete((document,error)->minecraft.execute(()->{
                if(request!=generation || minecraft.gui.screen()!=this)return;loading=false;
                if(error!=null){Throwable cause=error.getCause()==null?error:error.getCause();status=cause.getMessage()==null?"Could not read schematic":cause.getMessage();return;}
                try {client.builder().load(document);}
                catch(RuntimeException failure){status=failure.getMessage();cancelPendingStart();return;}
                if(!startAfterSelection) {minecraft.gui.setScreen(new MaterialListScreen(parent,client));return;}
                minecraft.gui.setScreen(null);
                try {client.builder().start();}
                catch(RuntimeException failure) {
                    cancelPendingStart();
                    minecraft.gui.setScreen(new MaterialListScreen(parent,client,"Auto build paused: "+failure.getMessage()));
                }
            }));
    }
    private int visibleRows() { return Math.max(1,(height-112)/24); }
    @Override public void extractRenderState(GuiGraphicsExtractor graphics,int mouseX,int mouseY,float partialTick) {
        var canvas=new MinecraftCanvas(graphics,font);
        canvas.roundedRect(0,0,width,height,0,0xee111522);
        canvas.text("OPEN .LITEMATIC",12,12,14,0xffedf1ff);
        int count=Math.min(500,entries.size());scroll=Math.clamp(scroll,0,Math.max(0,count-visibleRows()));
        canvas.clip(12,67,width-24,Math.max(1,height-112));
        for(int i=scroll;i<Math.min(count,scroll+visibleRows());i++) {
            Path path=entries.get(i);int y=67+(i-scroll)*24;
            canvas.roundedRect(12,y,width-24,21,5,0xff252b40);
            canvas.text(font.plainSubstrByWidth((Files.isDirectory(path)?"[Folder] ":"")+path.getFileName(),width-44),21,y+7,9,0xffedf1ff);
        }
        canvas.unclip();
        canvas.text(font.plainSubstrByWidth(status==null?"Could not open file":status,width-24),12,height-43,8,0xffb5accf);
        super.extractRenderState(graphics,mouseX,mouseY,partialTick);
    }
    @Override public boolean mouseClicked(MouseButtonEvent event,boolean doubleClick) {
        if(!loading && event.button()==InputConstants.MOUSE_BUTTON_LEFT && event.x()>=12 && event.x()<width-12 && event.y()>=67 && event.y()<height-45) {
            int index=scroll+(int)((event.y()-67)/24);
            if(index>=0 && index<Math.min(500,entries.size())) {var path=entries.get(index);if(Files.isDirectory(path))browse(path);else select(path);}
            return true;
        }
        return super.mouseClicked(event,doubleClick);
    }
    @Override public boolean mouseScrolled(double x,double y,double horizontal,double vertical) {scroll-=(int)(vertical*2);return true;}
    private void cancelPendingStart() {
        if(startAfterSelection) {
            var module=client.modules().get("auto_builder");module.setEnabled(false);client.config().remember(module);
            try{client.save();}catch(IllegalStateException ignored){ }
        }
    }
    @Override public void onClose() {generation++;cancelPendingStart();minecraft.gui.setScreen(parent);}
    @Override public boolean isPauseScreen() {return false;}
}
