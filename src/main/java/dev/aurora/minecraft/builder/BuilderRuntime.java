package dev.aurora.minecraft.builder;

import dev.aurora.core.builder.*;
import dev.aurora.minecraft.AuroraClient;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.client.gui.GuiGraphicsExtractor;
import net.minecraft.core.BlockPos;
import net.minecraft.network.chat.Component;
import net.minecraft.world.level.block.state.BlockState;

public final class BuilderRuntime implements BuildSession.Driver {
    private final AuroraClient client;
    public final BucketActions buckets=new BucketActions();
    public final WaterSupply water;
    private BucketActions.Aim bucketAim;
    public final SchematicPreview preview=new SchematicPreview(this);
    private SchematicMaterials materials;
    private String materialsError="Choose a .litematic file first";
    public SchematicMaterials materials() {return materials;}
    public String materialsError() {return materialsError;}
    public boolean documentInWorld() {return document!=null && documentWorld==Minecraft.getInstance().level;}
    public int previewOpacity() {return client.config().number("auto_builder","preview_opacity",70);}
    public boolean previewVisible() {return documentInWorld() && client.config().flag("auto_builder","preview",true);}
    private void refreshSchematic() {
        preview.reset(document,anchor);
        try {materials=new SchematicMaterials(document,anchor);materials.tick(Minecraft.getInstance().level);materialsError="";}
        catch(RuntimeException error) {materials=null;materialsError=error.getMessage();}
    }
    private DirectSchematic document;
    private BlockPos anchor;
    private Object documentWorld;
    private NativePlacement.Attempt attempt;
    private BlockPos aimedTarget;
    private int aimTicks;
    private double approachRange=2.7;
    private int finalCheckTicks;
    public String schematicName() {return document==null?"Choose a .litematic file":document.name();}
    public DirectSchematic document() {return document;}
    public BlockPos anchor() {return anchor;}
    public void load(DirectSchematic selected) {
        Minecraft mc=Minecraft.getInstance();
        if(mc.player==null)throw new IllegalStateException("Join a world before selecting a schematic");
        stop("Schematic loaded");document=selected;anchor=mc.player.blockPosition().immutable();documentWorld=mc.level;session=null;
        client.config().setting("auto_builder","last_file",selected.file().toString());client.save();
        client.config().setting("auto_builder","preview","true");
        client.config().setting("auto_builder","materials_hud","true");
        client.save();
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
        water.confirmed(pos,state);
        if(documentInWorld() && materials!=null)materials.changed(pos,Minecraft.getInstance().level);
    }
    public void inspectChests() { stop("Inspecting chests");level=Minecraft.getInstance().level;restock.startScan(); }
    private int moveTicks;
    public BuilderRuntime(AuroraClient client) { this.client=client;chests=new RestockChests(client);restock=new RestockJob(chests,navigator);water=new WaterSupply(client,navigator,buckets,chests::dimension); }
    public BuildSession.State state() { return session==null?BuildSession.State.IDLE:session.state(); }
    public String status() { return water.busy()?water.status():restock.busy()?restock.status():session==null?message:session.status(); }
    public boolean controlsInput() {
        return water.busy() || restock.busy() || session!=null && (session.state()==BuildSession.State.BUILDING || session.state()==BuildSession.State.RESTOCKING);
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
        session=new BuildSession(plan.plan());moveTicks=0;
        var remaining=plan.plan().remaining(this::placed);
        water.configure(plan,remaining.stream().filter(b->b.item().equals("minecraft:water_bucket")).count());
        var missing=new HashMap<>(BuildPlan.missing(BuildPlan.materials(remaining),inventory()));
        if(water.canSupply())missing.remove("minecraft:water_bucket");
        if(missing.isEmpty())session.start(this);
        else { restock.startScan();preflight=true; }
    }
    public void stop(String reason) {
        preflight=false;navigator.stop();restock.cancel(reason);water.cancel();buckets.cancel();bucketAim=null;awaiting.clear();confirmations.clear();finalCheckTicks=0;approachRange=2.7;attempt=null;aimedTarget=null;
        if(session!=null)session.pause(reason);message=reason;
    }
    public void tick() {
        Minecraft mc=Minecraft.getInstance();
        if(mc.player==null || mc.level==null)return;
        chests.outlines();water.outlines();
        if(documentInWorld() && materials!=null)materials.tick(mc.level);
        if(controlsInput() && (mc.level!=level || !mc.player.isAlive())) { stop("World changed or player died; restart explicitly");return; }
        if(water.busy())water.tick();
        if(controlsInput() && !water.failure().isEmpty()) {stop(water.failure());return;}
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
        var recipe=snapshot.recipes().get(pos);
        BlockState actual=awaiting.contains(pos)?confirmations.get(pos):mc.level.getBlockState(pos);
        if(actual==null || recipe==null || !mc.level.hasChunkAt(pos) || !recipe.done(block.stage(),actual))return false;
        return snapshot.companions().getOrDefault(pos,List.of()).stream().allMatch(p->PlacementRecipes.equivalent(awaiting.contains(p)?confirmations.get(p):mc.level.getBlockState(p),snapshot.states().get(p)));
    }
    @Override public boolean completionReady() {
        var mc=Minecraft.getInstance();
        long unmatched=snapshot.states().entrySet().stream().filter(e->mc.level.getBlockState(e.getKey())!=e.getValue()).count();
        if(unmatched==0)return true;
        if(++finalCheckTicks>=40)throw new IllegalStateException("Automatic steps finished; "+unmatched+" blocks still need manual state/growth or support. See the preview.");
        return false;
    }
    @Override public Map<String,Integer> inventory() { return RestockJob.inventory(); }
    @Override public BuildSession.Stock stock() {
        var stock=restock.stock();if(!water.canSupply())return stock;
        var items=new HashMap<>(stock.items());items.merge("minecraft:water_bucket",2,Math::max);
        var own=new HashMap<>(inventory());own.merge("minecraft:water_bucket",2,Math::max);
        boolean enough=snapshot!=null && BuildPlan.missing(BuildPlan.materials(snapshot.plan().remaining(this::placed)),own).isEmpty();
        return new BuildSession.Stock(items,stock.inspected() || enough);
    }
    @Override public BuildSession.Restock restockFor(BuildPlan.Block next,Map<String,Integer> requirements) {
        if(next.item().equals("minecraft:water_bucket") && (water.ready() || water.canSupply())) {
            if(water.busy())return BuildSession.Restock.PENDING;
            if(!water.ready()) {water.prepare();return BuildSession.Restock.PENDING;}
            if(inventory().getOrDefault("minecraft:water_bucket",0)>0)return BuildSession.Restock.COMPLETE;
            water.refill();return BuildSession.Restock.PENDING;
        }
        var wanted=new HashMap<>(requirements);if(water.canSupply())wanted.remove("minecraft:water_bucket");
        return restock(wanted);
    }
    @Override public BuildSession.Restock restock(Map<String,Integer> requirements) {
        if(restock.busy())return BuildSession.Restock.PENDING;
        if(restock.failed())throw new IllegalStateException(restock.status());
        if(restock.status().equals("Restock complete") && BuildPlan.missing(requirements,inventory()).isEmpty())return BuildSession.Restock.COMPLETE;
        try { restock.startRestock(requirements);return BuildSession.Restock.PENDING; }
        catch(RuntimeException error) { message=error.getMessage();return BuildSession.Restock.BLOCKED; }
    }
    @Override public boolean preparePlacement(BuildPlan.Block block) {
        Minecraft mc=Minecraft.getInstance();
        if(restock.busy() || water.busy() || mc.gui.screen()!=null)return false;
        if(block.item().equals("minecraft:water_bucket")) {
            int seeds=water.seedBucketsNeeded();
            if(inventory().getOrDefault("minecraft:water_bucket",0)<seeds) {
                if(restock.failed())throw new IllegalStateException(restock.status());
                restock.startRestock(Map.of("minecraft:water_bucket",seeds));return false;
            }
            if(!water.prepare())return false;
        }
        if(++moveTicks>1200)throw new IllegalStateException("Could not reach the next block within 60 seconds");
        BlockPos target=new BlockPos(block.x(),block.y(),block.z());
        if(!mc.level.hasChunkAt(target))throw new IllegalStateException("Target chunk is not loaded");
        var actual=mc.level.getBlockState(target);var recipe=snapshot.recipes().get(target);var step=recipe.steps().get(block.stage());var expected=step.state();
        if(SchematicFluids.source(expected) && !actual.getFluidState().isEmpty() && actual.getBlock()!=expected.getBlock())
            throw new IllegalStateException("Remove the conflicting fluid at "+target.toShortString());
        if(!actual.canBeReplaced() && !placed(block) && !recipe.canContinue(block.stage(),actual))throw new IllegalStateException("Remove the conflicting block at "+target.toShortString());
        if(!navigator.walkTo(target,approachRange,Math.min(1.5,approachRange-0.5)))return false;
        if(!buckets.select(block.item()))return false;
        try {
        if(step.kind()==PlacementRecipes.Kind.BUCKET) {
            if(!target.equals(aimedTarget) || bucketAim==null) {bucketAim=BucketActions.find(target,false,expected.hasProperty(net.minecraft.world.level.block.state.properties.BlockStateProperties.WATERLOGGED));aimedTarget=target;aimTicks=2;}
            bucketAim.aim();if(aimTicks-->0)return false;
        }else {
            if(!target.equals(aimedTarget) || attempt==null) {attempt=step.kind()==PlacementRecipes.Kind.INTERACT?NativePlacement.interact(target):step.kind()==PlacementRecipes.Kind.AIR_USE?NativePlacement.airUse(target,step):NativePlacement.find(target,step);aimedTarget=target;aimTicks=2;}
            attempt.aim();if(aimTicks-->0)return false;
        }
        }catch(IllegalStateException error) {
            // A reachable target can still hide its support face behind nearby blocks.
            // Walk closer (including vanilla jumps onto low walls) before declaring it inaccessible.
            if(approachRange<=1.5)throw error;
            approachRange=Math.max(1.5,approachRange-0.6);navigator.stop();attempt=null;bucketAim=null;aimedTarget=null;return false;
        }
        moveTicks=0;return true;
    }
    @Override public void place(BuildPlan.Block block) {
        Minecraft mc=Minecraft.getInstance();BlockPos target=new BlockPos(block.x(),block.y(),block.z());
        awaiting.add(target.immutable());confirmations.remove(target);
        for(BlockPos companion:snapshot.companions().getOrDefault(target,List.of())) {awaiting.add(companion);confirmations.remove(companion);}
        if(snapshot.recipes().get(target).steps().get(block.stage()).kind()==PlacementRecipes.Kind.BUCKET) {
            if(bucketAim==null)throw new IllegalStateException("No validated bucket ray");bucketAim.use();bucketAim=null;
        }else {if(attempt==null)throw new IllegalStateException("No validated placement context");attempt.click();attempt=null;}
        aimedTarget=null;approachRange=2.7;
    }
    public void drawStatus(GuiGraphicsExtractor graphics) {
        Minecraft mc=Minecraft.getInstance();
        if(mc.player==null || mc.gui.hud.isHidden() || !controlsInput() && !client.enabled("auto_builder") && (document==null || !client.config().flag("auto_builder","preview",true)))return;
        String text=mc.font.plainSubstrByWidth("Auto Builder: "+(controlsInput() || client.enabled("auto_builder") ? status() : "Preview: "+schematicName()),Math.max(40,graphics.guiWidth()-24));
        graphics.text(mc.font,text,Math.max(6,(graphics.guiWidth()-mc.font.width(text))/2),6,0xffc4baff,true);
    }
}
