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
    public final LitematicaBridge litematica=new LitematicaBridge();
    public final WalkNavigator navigator=new WalkNavigator();
    public final RestockChests chests;
    public final RestockJob restock;
    private LitematicaBridge.Snapshot snapshot;
    private BuildSession session;
    private boolean preflight;
    private Object level;
    private String message="Select a Litematica placement";
    private final Map<BlockPos,BlockState> confirmations=new HashMap<>();
    private final Set<BlockPos> awaiting=new HashSet<>();
    public void confirmed(BlockPos pos,BlockState state) { if(awaiting.contains(pos))confirmations.put(pos.immutable(),state); }
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
        litematica.preview(client.config().flag("auto_builder","preview",true));
        var plan=litematica.snapshot();
        if(ownScreen)mc.gui.setScreen(null);
        startSnapshot(plan);
    }
    /** Also accepts an explicit immutable snapshot for the development integration runner. */
    public void startSnapshot(LitematicaBridge.Snapshot plan) {
        stop("Preparing build");snapshot=plan;level=Minecraft.getInstance().level;
        session=new BuildSession(plan.plan());moveTicks=0;waitingRevision=-1;
        if(BuildPlan.missing(BuildPlan.materials(plan.plan().remaining(this::placed)),inventory()).isEmpty())session.start(this);
        else { restock.startScan();preflight=true; }
    }
    public void stop(String reason) {
        preflight=false;navigator.stop();restock.cancel(reason);waitingRevision=-1;awaiting.clear();confirmations.clear();
        if(session!=null)session.pause(reason);message=reason;
    }
    public void tick() {
        Minecraft mc=Minecraft.getInstance();
        if(mc.player==null || mc.level==null)return;
        chests.outlines();
        if(controlsInput() && (mc.level!=level || !mc.player.isAlive())) { stop("World changed or player died; restart explicitly");return; }
        if(snapshot!=null && snapshot.placement()!=null && controlsInput() && !litematica.unchanged(snapshot)) { stop("Schematic placement changed; restart explicitly");return; }
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
            if(key==anchor) { stop("Placement moved");litematica.cornerAtFeet();message="Schematic corner moved to your feet"; }
            else { stop("Restock selection changed");chests.bindLookedAt();restock.invalidate();message="Restock chest selection updated"; }
            Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal("Aurora: "+message));
        }catch(RuntimeException error) { message=error.getMessage();Minecraft.getInstance().gui.hud.getChat().addClientSystemMessage(Component.literal("Aurora: "+message)); }
        return true;
    }
    @Override public String worldIdentity() { return Integer.toHexString(System.identityHashCode(Minecraft.getInstance().level)); }
    @Override public boolean placed(BuildPlan.Block block) {
        Minecraft mc=Minecraft.getInstance();BlockPos pos=new BlockPos(block.x(),block.y(),block.z());
        if(awaiting.contains(pos))return confirmations.get(pos)==snapshot.states().get(pos);
        return mc.level.hasChunkAt(pos) && mc.level.getBlockState(pos)==snapshot.states().get(pos);
    }
    @Override public Map<String,Integer> inventory() { return RestockJob.inventory(); }
    @Override public BuildSession.Stock stock() { return restock.stock(); }
    @Override public BuildSession.Restock restock(Map<String,Integer> requirements) {
        if(restock.busy())return BuildSession.Restock.PENDING;
        if(restock.failed())return BuildSession.Restock.BLOCKED;
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
                if(i<9) { inv.setSelectedSlot(i);moveTicks=0;return true; }
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
        RestockJob.lookAt(Vec3.atCenterOf(target));
        if(snapshot.placement()!=null) { litematica.easyPlace(target);return; }
        // Native placement is used by the development runner; user-facing Auto Builder requires Litematica.
        for(Direction side:List.of(Direction.DOWN,Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST,Direction.UP)) {
            BlockPos support=target.relative(side);
            if(!mc.level.getBlockState(support).getCollisionShape(mc.level,support).isEmpty() && !mc.level.getBlockState(support).hasBlockEntity()) {
                Direction face=side.getOpposite();Vec3 hit=Vec3.atCenterOf(support).add(face.getStepX()*0.5,face.getStepY()*0.5,face.getStepZ()*0.5);
                RestockJob.lookAt(hit);
                mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(hit,face,support,false));return;
            }
        }
        throw new IllegalStateException("No reachable support face for block placement");
    }
    public void drawStatus(GuiGraphicsExtractor graphics) {
        Minecraft mc=Minecraft.getInstance();
        if(mc.player==null || mc.gui.hud.isHidden() || !controlsInput() && !client.enabled("auto_builder"))return;
        String text=mc.font.plainSubstrByWidth("Auto Builder: "+status(),Math.max(40,graphics.guiWidth()-24));
        graphics.text(mc.font,text,Math.max(6,(graphics.guiWidth()-mc.font.width(text))/2),6,0xffc4baff,true);
    }
}
