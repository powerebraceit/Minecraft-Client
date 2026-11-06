package dev.aurora.minecraft.builder;

import dev.aurora.minecraft.AuroraClient;
import java.util.*;
import java.util.function.Supplier;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.gizmos.*;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.*;

/** Creates two diagonal water seeds in a contained basin and verifies regeneration after every refill. */
public final class WaterSupply {
    private enum Phase { IDLE, SEED, WAIT_SEED, WAIT_POOL, PICKUP, WAIT_REFILL }
    private final AuroraClient client;
    private final WalkNavigator navigator;
    private final BucketActions buckets;
    private final Supplier<String> scope;
    private final Map<BlockPos,BlockState> confirmations=new HashMap<>();
    private Phase phase=Phase.IDLE;
    private BlockPos pool,target;
    private AABB reserved;
    private Object world;
    private boolean needed,ready;
    private int seed,ticks,totalTicks,aimTicks;
    private long inventoryAfter;
    private BucketActions.Aim aim;
    private String failure="",status="No water pool selected";
    public WaterSupply(AuroraClient client,WalkNavigator navigator,BucketActions buckets,Supplier<String> scope) {
        this.client=client;this.navigator=navigator;this.buckets=buckets;this.scope=scope;
    }
    public String failure() {return failure;}
    public boolean busy() {return phase!=Phase.IDLE;}
    public boolean ready() {return ready && world==Minecraft.getInstance().level;}
    public boolean canSupply() {
        var inv=RestockJob.inventory();int filled=inv.getOrDefault("minecraft:water_bucket",0);
        if(world!=Minecraft.getInstance().level || filled+inv.getOrDefault("minecraft:bucket",0)==0)return false;
        if(ready())return true;
        if(!needed || pool==null)return false;
        var cells=WaterPool.cells(pool);int seeds=(WaterPool.source(Minecraft.getInstance().level,cells.get(0))?1:0)+(WaterPool.source(Minecraft.getInstance().level,cells.get(3))?1:0);
        return seeds+filled>=2;
    }
    public String status() {return busy()?status:failure.isEmpty()?pool==null?"Auto-detect 2x2 water basin":pool.toShortString():failure;}
    public void cancel() {phase=Phase.IDLE;aim=null;navigator.stop();buckets.cancel();}
    public void confirmed(BlockPos pos,BlockState state) {if(pool!=null && WaterPool.cells(pool).contains(pos))confirmations.put(pos.immutable(),state);}
    public void bindAtFeet() {
        var mc=Minecraft.getInstance();if(mc.player==null)throw new IllegalStateException("Join a world first");
        var corner=mc.player.blockPosition().immutable();
        if(!WaterPool.safe(mc.level,corner,p->mc.level.hasChunkAt(p)))throw new IllegalStateException("Stand in a contained, one-block-deep 2x2 basin, or an existing shallow 2x2 source pool");
        client.config().setting("auto_builder","water_pool",scope.get()+","+corner.getX()+","+corner.getY()+","+corner.getZ());client.save();
        pool=corner;world=mc.level;ready=WaterPool.ready(mc.level,pool);failure="";
    }
    public void clearBinding() {cancel();pool=null;ready=false;client.config().setting("auto_builder","water_pool","");client.save();}
    public void configure(DirectSchematic.Snapshot schematic,long remainingWater) {
        cancel();world=Minecraft.getInstance().level;failure="";confirmations.clear();pool=null;ready=false;needed=remainingWater>2 && !Minecraft.getInstance().player.hasInfiniteMaterials();
        if(remainingWater==0)return;
        int minX=Integer.MAX_VALUE,minY=Integer.MAX_VALUE,minZ=Integer.MAX_VALUE,maxX=Integer.MIN_VALUE,maxY=Integer.MIN_VALUE,maxZ=Integer.MIN_VALUE;
        for(var p:schematic.states().keySet()) {minX=Math.min(minX,p.getX());minY=Math.min(minY,p.getY());minZ=Math.min(minZ,p.getZ());maxX=Math.max(maxX,p.getX());maxY=Math.max(maxY,p.getY());maxZ=Math.max(maxZ,p.getZ());}
        reserved=new AABB(minX-1,minY-1,minZ-1,maxX+2,maxY+2,maxZ+2);
        String[] stored=client.config().option("auto_builder","water_pool","").split(",");
        if(stored.length==4 && stored[0].equals(scope.get())) {
            try{pool=new BlockPos(Integer.parseInt(stored[1]),Integer.parseInt(stored[2]),Integer.parseInt(stored[3]));}catch(NumberFormatException ignored){ }
            if(pool!=null && !safe(pool))throw new IllegalStateException("Bound water pool is blocked, uncontained or overlaps the build; clear or move its binding");
        }
        if(pool==null)pool=find();
        ready=pool!=null && WaterPool.ready(Minecraft.getInstance().level,pool);
        if(needed && pool==null)throw new IllegalStateException("Prepare a contained one-block-deep 2x2 basin within 12 blocks, or bind a water pool in Auto Builder settings");
    }
    private boolean safe(BlockPos corner) {
        var mc=Minecraft.getInstance();
        return WaterPool.safe(mc.level,corner,p->mc.level.hasChunkAt(p) && mc.level.getWorldBorder().isWithinBounds(p) && !reserved.contains(Vec3.atCenterOf(p)));
    }
    private BlockPos find() {
        var mc=Minecraft.getInstance();var start=mc.player.blockPosition();BlockPos empty=null,full=null;double bestEmpty=Double.MAX_VALUE,bestFull=Double.MAX_VALUE;
        for(int x=-12;x<=12;x++)for(int z=-12;z<=12;z++)for(int y=-3;y<=1;y++) {
            var p=start.offset(x,y,z);if(!safe(p))continue;double distance=p.distSqr(start);
            if(WaterPool.ready(mc.level,p)) {if(distance<bestFull){bestFull=distance;full=p;}}
            else if(distance<bestEmpty){bestEmpty=distance;empty=p;}
        }
        return full!=null?full:needed?empty:null;
    }
    public int seedBucketsNeeded() {
        if(!needed || ready() || pool==null)return 0;
        var cells=WaterPool.cells(pool);var level=Minecraft.getInstance().level;
        return (WaterPool.source(level,cells.get(0))?0:1)+(WaterPool.source(level,cells.get(3))?0:1);
    }
    public boolean prepare() {
        if(!failure.isEmpty())throw new IllegalStateException(failure);
        if(!needed || ready())return true;
        if(!busy()) {if(pool==null || !safe(pool))throw new IllegalStateException("Water basin is no longer contained");phase=Phase.SEED;seed=0;ticks=0;totalTicks=0;aim=null;}
        return false;
    }
    public void refill() {
        if(!failure.isEmpty())throw new IllegalStateException(failure);
        if(busy())return;
        if(!ready() || !safe(pool) || !WaterPool.ready(Minecraft.getInstance().level,pool))throw new IllegalStateException("Water pool is no longer an infinite source; inspect it before restarting");
        phase=Phase.PICKUP;ticks=0;totalTicks=0;aim=null;target=WaterPool.cells(pool).get(1);
    }
    public void tick() {
        if(!busy())return;
        var mc=Minecraft.getInstance();
        try {
            if(mc.level!=world || mc.player==null || !mc.player.isAlive())throw new IllegalStateException("Water operation stopped after a world change or death");
            if(mc.gui.screen()!=null)throw new IllegalStateException("Close menus before working with the water pool");
            if(++totalTicks>1200)throw new IllegalStateException("Could not reach or refill the water pool within 60 seconds");
            if(phase==Phase.SEED) {
                status="Creating infinite water pool";
                if(seed>=2) {phase=Phase.WAIT_POOL;ticks=0;return;}
                target=WaterPool.cells(pool).get(seed==0?0:3);
                if(WaterPool.source(mc.level,target)){seed++;aim=null;return;}
                if(!safe(pool))throw new IllegalStateException("Water basin changed or lost its containment");
                if(use("minecraft:water_bucket",false)) {phase=Phase.WAIT_SEED;ticks=0;}
            }else if(phase==Phase.WAIT_SEED) {
                var confirmed=confirmations.get(target);
                if(confirmed!=null && SchematicFluids.source(confirmed) && buckets.observed("minecraft:bucket",inventoryAfter)) {seed++;phase=Phase.SEED;aim=null;}
                else if(++ticks>160)throw new IllegalStateException("Water seed placement or bucket conversion was not confirmed by the server");
            }else if(phase==Phase.WAIT_POOL) {
                status="Waiting for four regenerating water sources";
                if(WaterPool.ready(mc.level,pool)) {ready=true;phase=Phase.IDLE;navigator.stop();}
                else if(++ticks>200)throw new IllegalStateException("Water did not regenerate; check containment and the waterSourceConversion game rule");
            }else if(phase==Phase.PICKUP) {
                status="Refilling from infinite water pool";
                if(!safe(pool) || !WaterPool.ready(mc.level,pool))throw new IllegalStateException("Pool lost a source; refusing to drain it");
                if(use("minecraft:bucket",true)) {phase=Phase.WAIT_REFILL;ticks=0;}
            }else if(phase==Phase.WAIT_REFILL) {
                var confirmed=confirmations.get(target);
                if(buckets.observed("minecraft:water_bucket",inventoryAfter) && confirmed!=null && SchematicFluids.source(confirmed) && WaterPool.ready(mc.level,pool)) {
                    phase=Phase.IDLE;aim=null;navigator.stop();
                }else if(++ticks>200)throw new IllegalStateException("Water refill or source regeneration was not confirmed; inspect the pool and inventory space");
            }
        }catch(RuntimeException error){failure=error.getMessage();cancel();}
    }
    private boolean use(String item,boolean pickup) {
        if(!navigator.walkTo(target,2.8,1.8))return false;
        if(!buckets.select(item))return false;
        if(aim==null) {aim=BucketActions.find(target,pickup);aimTicks=2;}
        aim.aim();if(aimTicks-->0)return false;
        confirmations.remove(target);inventoryAfter=buckets.serial();aim.use();return true;
    }
    public void outlines() {
        var mc=Minecraft.getInstance();if(pool==null || mc.level!=world || mc.player==null)return;
        try(var ignored=mc.collectPerTickGizmos()) {for(var cell:WaterPool.cells(pool))Gizmos.cuboid(cell,GizmoStyle.stroke(0xff64bfff,1.5f));}
    }
}
