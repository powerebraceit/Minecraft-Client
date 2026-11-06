package dev.aurora.minecraft.builder;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Tests vanilla placement contexts before clicking; unsupported states fail instead of building the wrong block. */
public final class NativePlacement {
    public record Attempt(BlockHitResult hit,float yaw,float pitch,boolean air) {
        public Attempt(BlockHitResult hit,float yaw,float pitch) {this(hit,yaw,pitch,false);}
        public void aim() { var p=Minecraft.getInstance().player;p.setYRot(yaw);p.setXRot(pitch); }
        public void click() { Minecraft mc=Minecraft.getInstance();aim();if(air)mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);else mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,hit); }
    }
    private NativePlacement() { }
    public static Attempt find(BlockPos target,BlockState desired) {
        return find(target,new PlacementRecipes.Step(desired.getBlock().asItem(),desired,PlacementRecipes.Kind.PLACE,0));
    }
    public static Attempt find(BlockPos target,PlacementRecipes.Step step) {
        Minecraft mc=Minecraft.getInstance();var player=mc.player;var stack=player.getMainHandItem();
        if(!(stack.getItem() instanceof BlockItem item))throw new IllegalStateException("Selected item is not placeable");
        var predictedStates=new java.util.HashSet<String>();int[] rays={0},contexts={0};
        float oldYaw=player.getYRot(),oldPitch=player.getXRot();
        try {
            // Include the existing block itself for snow layers and double slabs.
            for(Direction direction:List.of(Direction.DOWN,Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST,Direction.UP)) {
                for(BlockPos support:List.of(target.relative(direction),target)) {
                    BlockState supportState=mc.level.getBlockState(support);
                    if(supportState.getShape(mc.level,support).isEmpty() || supportState.hasBlockEntity())continue;
                    Direction face=direction.getOpposite();
                    for(double fraction:new double[]{0.5,0.2,0.8})for(double lateral:new double[]{0.5,0.2,0.8}) {
                        var shape=supportState.getShape(mc.level,support).bounds();
                        Vec3 hit=new Vec3(support.getX()+(shape.minX+shape.maxX)/2,support.getY()+(shape.minY+shape.maxY)/2,support.getZ()+(shape.minZ+shape.maxZ)/2);
                        hit=hit.add(face.getStepX()*(shape.maxX-shape.minX)/2,face.getStepY()*(shape.maxY-shape.minY)/2,face.getStepZ()*(shape.maxZ-shape.minZ)/2);
                        if(face.getAxis().isHorizontal())hit=new Vec3(hit.x,support.getY()+fraction,hit.z);
                        else hit=new Vec3(support.getX()+fraction,hit.y,support.getZ()+lateral);
                        if(player.getEyePosition().distanceTo(hit)>player.blockInteractionRange())continue;
                        var ray=mc.level.clip(new ClipContext(player.getEyePosition(),hit.add(-face.getStepX()*0.001,-face.getStepY()*0.001,-face.getStepZ()*0.001),ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,player));
                        if(ray.getType()!=net.minecraft.world.phys.HitResult.Type.BLOCK || !ray.getBlockPos().equals(support))continue;
                        rays[0]++;
                        Vec3 delta=hit.subtract(player.getEyePosition());
                        float towardYaw=(float)Math.toDegrees(Math.atan2(-delta.x,delta.z));
                        float towardPitch=(float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z)));
                        for(float yaw:new float[]{towardYaw,oldYaw,0,90,180,270})for(float pitch:new float[]{towardPitch,oldPitch,0,75,-75}) {
                            player.setYRot(yaw);player.setXRot(pitch);
                            BlockHitResult click=new BlockHitResult(ray.getLocation(),ray.getDirection(),support,false);
                            BlockPlaceContext context=item.updatePlacementContext(new BlockPlaceContext(player,InteractionHand.MAIN_HAND,stack,click));
                            if(context==null || !context.canPlace() || !context.getClickedPos().equals(target))continue;
                            contexts[0]++;
                            BlockState predicted=((dev.aurora.minecraft.mixin.BlockItemPlacementInvoker)item).aurora$placementState(context);
                            predictedStates.add(String.valueOf(predicted));
                            if(predicted!=null && step.matches(predicted))return new Attempt(click,yaw,pitch);
                        }
                    }
                }
            }
        }finally { player.setYRot(oldYaw);player.setXRot(oldPitch); }
        dev.aurora.minecraft.AuroraClient.LOG.warn("Placement diagnostic: target={} expected={} item={} player={} rays={} contexts={} predictions={} neighbors={}",target,step.state(),stack.getItem(),player.position(),rays[0],contexts[0],predictedStates,java.util.Arrays.stream(Direction.values()).map(d->d+"="+mc.level.getBlockState(target.relative(d))).toList());
        throw new IllegalStateException("No reachable vanilla placement reproduces the schematic state at "+target.toShortString());
    }

    public static Attempt interact(BlockPos target) {return rayTo(target,false,null);}
    public static Attempt airUse(BlockPos target,PlacementRecipes.Step step) {return rayTo(target.below(),true,step);}
    private static Attempt rayTo(BlockPos hitPos,boolean air,PlacementRecipes.Step step) {
        var mc=Minecraft.getInstance();var player=mc.player;
        var shape=mc.level.getBlockState(hitPos).getShape(mc.level,hitPos);
        var bounds=shape.isEmpty()?net.minecraft.world.phys.shapes.Shapes.block().bounds():shape.bounds();
        for(double x:new double[]{0.5,0.25,0.75})for(double y:new double[]{0.5,0.9,0.1})for(double z:new double[]{0.5,0.25,0.75}) {
            Vec3 point=new Vec3(hitPos.getX()+bounds.minX+(bounds.maxX-bounds.minX)*x,hitPos.getY()+bounds.minY+(bounds.maxY-bounds.minY)*y,hitPos.getZ()+bounds.minZ+(bounds.maxZ-bounds.minZ)*z);
            Vec3 delta=point.subtract(player.getEyePosition());if(delta.length()>player.blockInteractionRange())continue;
            var ray=mc.level.clip(new ClipContext(player.getEyePosition(),player.getEyePosition().add(delta.normalize().scale(player.blockInteractionRange())),ClipContext.Block.OUTLINE,air?ClipContext.Fluid.SOURCE_ONLY:ClipContext.Fluid.NONE,player));
            if(ray.getType()!=net.minecraft.world.phys.HitResult.Type.BLOCK || !ray.getBlockPos().equals(hitPos))continue;
            if(air) {
                var item=(BlockItem)player.getMainHandItem().getItem();
                var context=new BlockPlaceContext(player,InteractionHand.MAIN_HAND,player.getMainHandItem(),ray.withPosition(hitPos.above()));
                var predicted=((dev.aurora.minecraft.mixin.BlockItemPlacementInvoker)item).aurora$placementState(context);
                if(predicted==null || !context.getClickedPos().equals(hitPos.above()) || !step.matches(predicted))continue;
            }
            return new Attempt(ray,(float)Math.toDegrees(Math.atan2(-delta.x,delta.z)),(float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z))),air);
        }
        throw new IllegalStateException("No reachable interaction ray at "+hitPos.toShortString());
    }
}
