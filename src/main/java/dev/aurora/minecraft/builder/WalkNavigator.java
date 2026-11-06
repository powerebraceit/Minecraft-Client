package dev.aurora.minecraft.builder;

import dev.aurora.core.builder.WalkPath;
import java.util.List;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.level.block.Blocks;
import net.minecraft.world.phys.Vec3;

public final class WalkNavigator {
    private BlockPos target;
    private List<WalkPath.Point> path=List.of();
    private int index,stuckTicks;
    private Vec3 last;
    private boolean forward,jump,placement;
    private double goalRange,goalMinimum;
    public boolean forward() {return forward;}
    public boolean jump() {return jump;}
    public void stop() {target=null;path=List.of();index=0;forward=false;jump=false;stuckTicks=0;last=null;}
    public boolean walkTo(BlockPos destination,double range) {return walkTo(destination,range,0);}
    public boolean walkTo(BlockPos destination,double range,double minimumRange) {
        Predicate<Vec3> goal=feet->{double distance=feet.distanceTo(Vec3.atBottomCenterOf(destination));return distance<=range && distance>=minimumRange;};
        boolean changed=placement || range!=goalRange || minimumRange!=goalMinimum;
        if(changed)stop();placement=false;goalRange=range;goalMinimum=minimumRange;
        return walk(destination,goal);
    }
    /** Search for a real standing cell satisfying visibility and any rejected-viewpoint filter. */
    public boolean walkToPlacement(BlockPos destination,Predicate<Vec3> acceptableFeet) {
        if(!placement)stop();placement=true;
        return walk(destination,acceptableFeet);
    }
    private boolean walk(BlockPos destination,Predicate<Vec3> goal) {
        var mc=Minecraft.getInstance();
        if(mc.player==null || mc.level==null) {stop();return false;}
        if(mc.gui.screen()!=null) {forward=false;jump=false;return false;}
        var actual=mc.player.position();
        if(walkable(point(mc.player.blockPosition())) && goal.test(actual)) {stop();return true;}
        if(target!=null && target.equals(destination) && !path.isEmpty()) {
            var endpoint=path.getLast();
            if(!walkable(endpoint) || !goal.test(feet(endpoint)))stop();
        }
        if(!destination.equals(target)) {
            stop();target=destination.immutable();
            var from=mc.player.blockPosition();
            path=WalkPath.find(point(from),this::walkable,this::clear,p->walkable(p) && goal.test(feet(p)),8192);
        }
        if(index>=path.size()) {
            stop();
            // A depleted/empty route is not proof that a radius or visible placement goal was reached.
            return walkable(point(mc.player.blockPosition())) && goal.test(mc.player.position());
        }
        var next=path.get(index);
        if(!walkable(next)) {stop();throw new IllegalStateException("Terrain changed; recompute the walking route");}
        Vec3 center=feet(next);
        if(actual.distanceTo(center)<0.3) {index++;forward=false;return false;}
        if(last!=null && actual.distanceTo(last)<0.015)stuckTicks++;else stuckTicks=0;
        last=actual;
        if(stuckTicks>60) {stop();throw new IllegalStateException("Walking route is blocked");}
        Vec3 delta=center.subtract(actual);
        mc.player.setYRot((float)Math.toDegrees(Math.atan2(-delta.x,delta.z)));
        forward=true;jump=center.y>mc.player.getY()+0.35;
        return false;
    }
    private static WalkPath.Point point(BlockPos p) {return new WalkPath.Point(p.getX(),p.getY(),p.getZ());}
    private static Vec3 feet(WalkPath.Point p) {return new Vec3(p.x()+0.5,p.y(),p.z()+0.5);}
    private boolean clear(WalkPath.Point point) {
        var mc=Minecraft.getInstance();var pos=new BlockPos(point.x(),point.y(),point.z());
        return mc.level.hasChunkAt(pos) && mc.level.getBlockState(pos).getCollisionShape(mc.level,pos).isEmpty();
    }
    private boolean walkable(WalkPath.Point point) {
        var mc=Minecraft.getInstance();var p=new BlockPos(point.x(),point.y(),point.z());
        if(!mc.level.hasChunkAt(p) || !mc.level.getWorldBorder().isWithinBounds(p))return false;
        var feet=mc.level.getBlockState(p);var head=mc.level.getBlockState(p.above());var ground=mc.level.getBlockState(p.below());
        return feet.getCollisionShape(mc.level,p).isEmpty() && head.getCollisionShape(mc.level,p.above()).isEmpty()
            && feet.getFluidState().isEmpty() && head.getFluidState().isEmpty()
            && !feet.is(Blocks.FIRE) && !feet.is(Blocks.POWDER_SNOW)
            && !ground.is(Blocks.MAGMA_BLOCK) && !ground.is(Blocks.CACTUS)
            && ground.isFaceSturdy(mc.level,p.below(),Direction.UP);
    }
}
