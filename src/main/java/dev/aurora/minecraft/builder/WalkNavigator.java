package dev.aurora.minecraft.builder;

import dev.aurora.core.builder.WalkPath;
import java.util.List;
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
    private boolean forward,jump;
    public boolean forward() { return forward; }
    public boolean jump() { return jump; }
    public void stop() { target=null;path=List.of();index=0;forward=false;jump=false;stuckTicks=0;last=null; }
    public boolean walkTo(BlockPos destination, double range) { return walkTo(destination,range,0); }
    public boolean walkTo(BlockPos destination, double range, double minimumRange) {
        Minecraft mc=Minecraft.getInstance();
        if(mc.player==null || mc.level==null) { stop();return false; }
        if(mc.gui.screen()!=null) { forward=false;jump=false;return false; }
        if(mc.player.position().distanceTo(Vec3.atBottomCenterOf(destination))<=range && mc.player.position().distanceTo(Vec3.atBottomCenterOf(destination))>=minimumRange) { stop();return true; }
        if(!destination.equals(target)) {
            stop();target=destination.immutable();
            var from=mc.player.blockPosition();
            path=WalkPath.find(new WalkPath.Point(from.getX(),from.getY(),from.getZ()), this::walkable, this::clear,
                p -> { double distance=Vec3.atBottomCenterOf(new BlockPos(p.x(),p.y(),p.z())).distanceTo(Vec3.atBottomCenterOf(destination)); return distance<=range && distance>=minimumRange; },8192);
        }
        if(index>=path.size()) { stop();return mc.player.position().distanceTo(Vec3.atBottomCenterOf(destination)) <= range + 0.7; }
        var point=path.get(index);
        if(!walkable(point)) { stop();throw new IllegalStateException("Terrain changed; recompute the walking route"); }
        Vec3 center=Vec3.atBottomCenterOf(new BlockPos(point.x(),point.y(),point.z()));
        if(mc.player.position().distanceTo(center)<0.35) { index++;forward=false;return false; }
        if(last!=null && mc.player.position().distanceTo(last)<0.015) stuckTicks++; else stuckTicks=0;
        last=mc.player.position();
        if(stuckTicks>60) { stop();throw new IllegalStateException("Walking route is blocked"); }
        Vec3 delta=center.subtract(mc.player.position());
        mc.player.setYRot((float)Math.toDegrees(Math.atan2(-delta.x,delta.z)));
        forward=true;jump=center.y>mc.player.getY()+0.35;
        return false;
    }
    private boolean clear(WalkPath.Point point) {
        Minecraft mc=Minecraft.getInstance();BlockPos pos=new BlockPos(point.x(),point.y(),point.z());
        return mc.level.hasChunkAt(pos) && mc.level.getBlockState(pos).getCollisionShape(mc.level,pos).isEmpty();
    }
    private boolean walkable(WalkPath.Point point) {
        Minecraft mc=Minecraft.getInstance();
        BlockPos p=new BlockPos(point.x(),point.y(),point.z());
        if(!mc.level.hasChunkAt(p) || !mc.level.getWorldBorder().isWithinBounds(p)) return false;
        var feet=mc.level.getBlockState(p);var head=mc.level.getBlockState(p.above());var ground=mc.level.getBlockState(p.below());
        return feet.getCollisionShape(mc.level,p).isEmpty() && head.getCollisionShape(mc.level,p.above()).isEmpty()
            && feet.getFluidState().isEmpty() && head.getFluidState().isEmpty()
            && !feet.is(Blocks.FIRE) && !feet.is(Blocks.POWDER_SNOW)
            && !ground.is(Blocks.MAGMA_BLOCK) && !ground.is(Blocks.CACTUS)
            && ground.isFaceSturdy(mc.level,p.below(),Direction.UP);
    }
}
