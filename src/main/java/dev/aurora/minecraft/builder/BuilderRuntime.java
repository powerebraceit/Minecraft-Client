package dev.aurora.minecraft.builder;

import dev.aurora.core.builder.*;
import dev.aurora.minecraft.AuroraClient;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.network.chat.Component;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

public final class BuilderRuntime implements BuildSession.Driver {
    private final AuroraClient client;
    public final SchematicPreview preview=new SchematicPreview(this);
    private SchematicMaterials materials;
    private String materialsError="Choose a .litematic file first";
    public SchematicMaterials materials() {return materials;}
    public String materialsError() {return materialsError;}
    public boolean documentInWorld() {return document!=null && documentWorld==Minecraft.getInstance().level;}
    public boolean previewVisible() {return documentInWorld() && client.config().flag("auto_builder","preview",true);}
    private void refreshSchematic() {
        preview.reset(document,anchor);
        try {materials=new SchematicMaterials(document.at(anchor));materials.tick(Minecraft.getInstance().level);materialsError="";}
        catch(RuntimeException error) {materials=null;materialsError=error.getMessage();}
    }
    private DirectSchematic document;
    private BlockPos anchor;
    private Object documentWorld;
    private NativePlacement.Attempt attempt;
    private BlockPos aimedTarget;
    private int aimTicks;
    private final Set<BlockPos> secondSlabClick=new HashSet<>();
    public String schematicName() {return document==null?"Choose a .litematic file":document.name();}
    public DirectSchematic document() {return document;}
    public BlockPos anchor() {return anchor;}
    public void load(DirectSchematic selected) {
        Minecraft mc=Minecraft.getInstance();
        if(mc.player==null)throw new IllegalStateException("Join a world before selecting a schematic");
        stop("Schematic loaded");document=selected;anchor=mc.player.blockPosition().immutable();documentWorld=mc.level;session=null;
        client.config().setting("auto_builder","last_file",selected.file().toString());client.save();
        refreshSchematic();message="Loaded "+selected.name();
    }
    public void cornerAtFeet() {
        if(document==null)throw new IllegalStateException("Choose a .litematic file first");
        Minecraft mc=Minecraft.getInstance();if(mc.player==null)throw new IllegalStateException("Join a world first");
        stop("Corner moved to feet");anchor=mc.player.blockPosition().immutable();documentWorld=mc.level;refreshSchematic();
    }
    public final WalkNavigator navigator=new WalkNavigator();
    public final RestockChests chests;
    public final RestockJob restock;
    private DirectSchematic.Snapshot snapshot;
    private BuildSession session;
    private boolean preflight;
    private Object level;
    private String message="Choose a .litematic file";
    private final Map<BlockPos,BlockState> confirmations=new HashMap<>();
    private final Set<BlockPos> awaiting=new HashSet<>();
    public void confirmed(BlockPos pos,BlockState state) {
        if(awaiting.contains(pos))confirmations.put(pos.immutable(),state);
        if(documentInWorld() && materials!=null)materials.changed(pos,Minecraft.getInstance().level);
    }
    public void inspectChests() { stop("Inspecting chests");level=Minecraft.getInstance().level;restock.startScan(); }
    private int waitingRevision=-1,swapTicks,moveTicks;
    public BuilderRuntime(AuroraClient client) { this.client=client;chests=new RestockChests(client);restock=new RestockJob(chests,navigator); }
    public BuildSession.State state() { return session==null?BuildSession.State.IDLE:session.state(); }
    public String status() { return restock.busy()?restock.status():session==null?message:session.status(); }
    public boolean controlsInput() {
        return restock.busy() || session!=null && (session.state()==BuildSession.State.BUILDING || session.state()==BuildSession.State.RESTOCKING);
    }
    public void start() {
        Minecraft mc=Minecraft.getInstance();
        if(mc.player==null || mc.level==null)throw new IllegalStateException("Join a world first");
        boolean ownScreen=mc.gui.screen() instanceof dev.aurora.minecraft.AuroraScreen || mc.gui.screen() instanceof dev.aurora.minecraft.ModuleSettingsScreen;
        if(mc.gui.screen()!=null && !ownScreen)throw new IllegalStateException("Close other menus before starting the builder");
        if(document==null) {
            mc.gui.setScreen(new SchematicFileScreen(mc.gui.screen(),client,true));return;
        }
        if(documentWorld!=mc.level)throw new IllegalStateException("Move the schematic corner to your feet in this world first");
        var plan=document.at(anchor);
        for(var pos:plan.states().keySet())if(mc.level.isOutsideBuildHeight(pos))throw new IllegalStateException("Schematic extends outside the world build height");
        if(ownScreen)mc.gui.setScreen(null);
        startSnapshot(plan);
    }
    /** Also accepts an explicit immutable snapshot for the development integration runner. */
    public void startSnapshot(DirectSchematic.Snapshot plan) {
        stop("Preparing build");snapshot=plan;level=Minecraft.getInstance().level;
        session=new BuildSession(plan.plan());moveTicks=0;waitingRevision=-1;
        if(BuildPlan.missing(BuildPlan.materials(plan.plan().remaining(this::placed)),inventory()).isEmpty())session.start(this);
        else { restock.startScan();preflight=true; }
    }
    public void stop(String reason) {
        preflight=false;navigator.stop();restock.cancel(reason);waitingRevision=-1;awaiting.clear();confirmations.clear();secondSlabClick.clear();attempt=null;aimedTarget=null;
        if(session!=null)session.pause(reason);message=reason;
    }
    public void tick() {
        Minecraft mc=Minecraft.getInstance();
        if(mc.player==null || mc.level==null)return;
        chests.outlines();
        if(documentInWorld() && materials!=null)materials.tick(mc.level);
        if(controlsInput() && (mc.level!=level || !mc.player.isAlive())) { stop("World changed or player died; restart explicitly");return; }
        if(snapshot!=null && controlsInput()) {
            for(BlockPos pos:new ArrayList<>(awaiting)) {
                var expected=snapshot.states().get(pos);var confirmed=confirmations.get(pos);
                if(expected!=null && expected.getBlock() instanceof net.minecraft.world.level.block.SlabBlock
                    && expected.getValue(net.minecraft.world.level.block.SlabBlock.TYPE)==net.minecraft.world.level.block.state.properties.SlabType.DOUBLE
                    && confirmed!=null && confirmed.getBlock()==expected.getBlock() && confirmed!=expected && secondSlabClick.add(pos)) {
                    try {var click=NativePlacement.find(pos,expected);click.aim();click.click();}
                    catch(RuntimeException error){stop(error.getMessage());return;}
                }
            }
        }
        if(restock.busy())restock.tick();
        if(preflight && !restock.busy()) {
            preflight=false;
            if(restock.failed() || !restock.stock().inspected()) { stop(restock.status());return; }
            session.start(this);
        }
        if(session!=null && !preflight && (mc.gui.screen()==null || restock.busy())) {
            try { session.tick(this); }
            catch(RuntimeException error) { stop(error.getMessage()==null?"Builder failed":error.getMessage()); }
            if(session.state()==BuildSession.State.COMPLETE || session.state()==BuildSession.State.PAUSED || session.state()==BuildSession.State.MISSING_MATERIALS)navigator.stop();
        }
    }
    public boolean actionKey(int key) {
        // Preserve explicit module bindings from earlier Aurora versions over new default action keys.
        for(var module:client.modules().all())if(key!=0 && client.config().number(module.id(),"key",0)==key)return false;
        int anchor=client.config().number("auto_builder","anchor_key",10);
        int bind=client.config().number("auto_builder","restock_key",25);
        if(key==0 || key!=anchor && key!=bind)return false;
        try {
            if(key==anchor) { stop("Placement moved");cornerAtFeet();message="Schematic corner moved to your feet"; }
            else { stop("Restock selection changed");chests.bindLookedAt();restock.invalidate();message="Restock chest selection updated"; }
            Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal("Aurora: "+message));
        }catch(RuntimeException error) { message=error.getMessage();Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal("Aurora: "+message)); }
        return true;
    }
    @Override public String worldIdentity() { return Integer.toHexString(System.identityHashCode(Minecraft.getInstance().level)); }
    @Override public boolean placed(BuildPlan.Block block) {
        Minecraft mc=Minecraft.getInstance();BlockPos pos=new BlockPos(block.x(),block.y(),block.z());
        if(awaiting.contains(pos)) {
            if(confirmations.get(pos)!=snapshot.states().get(pos))return false;
            return snapshot.companions().getOrDefault(pos,List.of()).stream().allMatch(p->confirmations.get(p)==snapshot.states().get(p));
        }
        return mc.level.hasChunkAt(pos) && mc.level.getBlockState(pos)==snapshot.states().get(pos)
            && snapshot.companions().getOrDefault(pos,List.of()).stream().allMatch(p->mc.level.getBlockState(p)==snapshot.states().get(p));
    }
    @Override public Map<String,Integer> inventory() { return RestockJob.inventory(); }
    @Override public BuildSession.Stock stock() { return restock.stock(); }
    @Override public BuildSession.Restock restock(Map<String,Integer> requirements) {
        if(restock.busy())return BuildSession.Restock.PENDING;
        if(restock.failed())throw new IllegalStateException(restock.status());
        if(restock.status().equals("Restock complete") && BuildPlan.missing(requirements,inventory()).isEmpty())return BuildSession.Restock.COMPLETE;
        try { restock.startRestock(requirements);return BuildSession.Restock.PENDING; }
        catch(RuntimeException error) { message=error.getMessage();return BuildSession.Restock.BLOCKED; }
    }
    @Override public boolean preparePlacement(BuildPlan.Block block) {
        Minecraft mc=Minecraft.getInstance();
        if(restock.busy())return false;
        if(mc.gui.screen()!=null)return false;
        if(++moveTicks>1200)throw new IllegalStateException("Could not reach the next block within 60 seconds");
        BlockPos target=new BlockPos(block.x(),block.y(),block.z());
        if(!mc.level.hasChunkAt(target))throw new IllegalStateException("Target chunk is not loaded");
        if(!mc.level.getBlockState(target).canBeReplaced() && !placed(block))throw new IllegalStateException("Remove the conflicting block at "+target.toShortString());
        if(!navigator.walkTo(target,2.7,1.5))return false;
        if(waitingRevision!=-1) {
            if(mc.player.inventoryMenu.getStateId()==waitingRevision) {
                if(++swapTicks>100)throw new IllegalStateException("Inventory swap was not confirmed");
                return false;
            }
            waitingRevision=-1;swapTicks=0;
        }
        var inv=mc.player.getInventory();
        for(int i=0;i<36;i++) {
            if(!inv.getItem(i).isEmpty() && RestockJob.itemId(inv.getItem(i)).equals(block.item())) {
                if(i<9) {
                    inv.setSelectedSlot(i);
                    if(!target.equals(aimedTarget) || attempt==null) {attempt=NativePlacement.find(target,snapshot.states().get(target));aimedTarget=target;aimTicks=2;}
                    attempt.aim();
                    if(aimTicks-->0)return false;
                    moveTicks=0;return true;
                }
                waitingRevision=mc.player.inventoryMenu.getStateId();swapTicks=0;
                mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId,i,0,ContainerInput.SWAP,mc.player);
                return false;
            }
        }
        throw new IllegalStateException("Required material disappeared from inventory");
    }
    @Override public void place(BuildPlan.Block block) {
        Minecraft mc=Minecraft.getInstance();BlockPos target=new BlockPos(block.x(),block.y(),block.z());
        awaiting.add(target.immutable());confirmations.remove(target);
        for(BlockPos companion:snapshot.companions().getOrDefault(target,List.of())) {awaiting.add(companion);confirmations.remove(companion);}
        if(attempt==null)throw new IllegalStateException("No validated placement context");
        attempt.click();attempt=null;aimedTarget=null;
    }
    public void drawStatus(GuiGraphicsExtractor graphics) {
        Minecraft mc=Minecraft.getInstance();
        if(mc.player==null || mc.gui.hud.isHidden() || !controlsInput() && !client.enabled("auto_builder") && (document==null || !client.config().flag("auto_builder","preview",true)))return;
        String text=mc.font.plainSubstrByWidth("Auto Builder: "+(controlsInput() || client.enabled("auto_builder") ? status() : "Preview: "+schematicName()),Math.max(40,graphics.guiWidth()-24));
        graphics.text(mc.font,text,Math.max(6,(graphics.guiWidth()-mc.font.width(text))/2),6,0xffc4baff,true);
    }
}
