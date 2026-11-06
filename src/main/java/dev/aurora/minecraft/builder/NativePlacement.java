package dev.aurora.minecraft.builder;

import java.util.List;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.SlabBlock;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.SlabType;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Tests vanilla placement contexts before clicking; unsupported states fail instead of building the wrong block. */
public final class NativePlacement {
    public record Attempt(BlockHitResult hit,float yaw,float pitch) {
        public void aim() { var p=Minecraft.getInstance().player;p.setYRot(yaw);p.setXRot(pitch); }
        public void click() { Minecraft mc=Minecraft.getInstance();aim();mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,hit); }
    }
    private NativePlacement() { }
    public static Attempt find(BlockPos target,BlockState desired) {
        Minecraft mc=Minecraft.getInstance();var player=mc.player;var stack=player.getMainHandItem();
        if(!(stack.getItem() instanceof BlockItem item))throw new IllegalStateException("Selected item is not placeable");
        float oldYaw=player.getYRot(),oldPitch=player.getXRot();
        try {
            // Include the existing block itself for snow layers and double slabs.
            for(Direction direction:List.of(Direction.DOWN,Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST,Direction.UP)) {
                for(BlockPos support:List.of(target.relative(direction),target)) {
                    BlockState supportState=mc.level.getBlockState(support);
                    if(supportState.getCollisionShape(mc.level,support).isEmpty() || supportState.hasBlockEntity())continue;
                    Direction face=direction.getOpposite();
                    for(double fraction:new double[]{0.5,0.2,0.8}) {
                        var shape=supportState.getShape(mc.level,support).bounds();
                        Vec3 hit=new Vec3(support.getX()+(shape.minX+shape.maxX)/2,support.getY()+(shape.minY+shape.maxY)/2,support.getZ()+(shape.minZ+shape.maxZ)/2);
                        hit=hit.add(face.getStepX()*(shape.maxX-shape.minX)/2,face.getStepY()*(shape.maxY-shape.minY)/2,face.getStepZ()*(shape.maxZ-shape.minZ)/2);
                        if(face.getAxis().isHorizontal())hit=new Vec3(hit.x,support.getY()+fraction,hit.z);
                        if(player.getEyePosition().distanceTo(hit)>player.blockInteractionRange())continue;
                        var ray=mc.level.clip(new ClipContext(player.getEyePosition(),hit.add(-face.getStepX()*0.001,-face.getStepY()*0.001,-face.getStepZ()*0.001),ClipContext.Block.OUTLINE,ClipContext.Fluid.NONE,player));
                        if(ray.getType()!=net.minecraft.world.phys.HitResult.Type.BLOCK || !ray.getBlockPos().equals(support))continue;
                        Vec3 delta=hit.subtract(player.getEyePosition());
                        float towardYaw=(float)Math.toDegrees(Math.atan2(-delta.x,delta.z));
                        float towardPitch=(float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z)));
                        for(float yaw:new float[]{towardYaw,oldYaw,0,90,180,270})for(float pitch:new float[]{towardPitch,oldPitch,0,75,-75}) {
                            player.setYRot(yaw);player.setXRot(pitch);
                            BlockHitResult click=new BlockHitResult(ray.getLocation(),ray.getDirection(),support,false);
                            BlockPlaceContext context=item.updatePlacementContext(new BlockPlaceContext(player,InteractionHand.MAIN_HAND,stack,click));
                            if(context==null || !context.canPlace() || !context.getClickedPos().equals(target))continue;
                            BlockState predicted=item.getBlock().getStateForPlacement(context);
                            boolean firstSlab=desired.getBlock() instanceof SlabBlock && desired.getValue(SlabBlock.TYPE)==SlabType.DOUBLE && predicted!=null && predicted.getBlock()==desired.getBlock();
                            if(predicted==desired || firstSlab)return new Attempt(click,yaw,pitch);
                        }
                    }
                }
            }
        }finally { player.setYRot(oldYaw);player.setXRot(oldPitch); }
        throw new IllegalStateException("No reachable vanilla placement reproduces the schematic state at "+target.toShortString());
    }
}
