package dev.aurora.minecraft.builder;

import java.util.*;
import dev.aurora.core.builder.PlacementReach;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.core.Direction;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.entity.Pose;
import net.minecraft.world.item.BlockItem;
import net.minecraft.world.item.context.BlockPlaceContext;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.level.block.state.BlockState;
import net.minecraft.world.level.block.state.properties.BlockStateProperties;
import net.minecraft.world.phys.AABB;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.HitResult;
import net.minecraft.world.phys.Vec3;

/** Uses real outline faces and vanilla item placement; navigation can search for a visible stance first. */
public final class NativePlacement {
    public record Attempt(BlockHitResult hit,float yaw,float pitch,boolean air,boolean crouch) {
        public Attempt(BlockHitResult hit,float yaw,float pitch) {this(hit,yaw,pitch,false,false);}
        public Attempt(BlockHitResult hit,float yaw,float pitch,boolean air) {this(hit,yaw,pitch,air,false);}
        public void aim() {
            var player=Minecraft.getInstance().player;
            player.setYRot(yaw);player.setXRot(pitch);player.setShiftKeyDown(crouch);
        }
        public boolean visible() {
            var mc=Minecraft.getInstance();
            if(mc.player==null || mc.level==null || !mc.level.hasChunkAt(hit.getBlockPos()))return false;
            var eyes=mc.player.getEyePosition();
            if(!PlacementReach.withinSquared(mc.player.blockInteractionRange(),eyes.distanceToSqr(hit.getLocation())))return false;
            var face=hit.getDirection();var end=hit.getLocation().add(-face.getStepX()*0.0001,-face.getStepY()*0.0001,-face.getStepZ()*0.0001);
            var current=mc.level.clip(new ClipContext(eyes,end,ClipContext.Block.OUTLINE,air?ClipContext.Fluid.SOURCE_ONLY:ClipContext.Fluid.NONE,mc.player));
            return current.getType()==HitResult.Type.BLOCK && current.getBlockPos().equals(hit.getBlockPos()) && current.getDirection()==face
                && PlacementReach.withinSquared(mc.player.blockInteractionRange(),eyes.distanceToSqr(current.getLocation()));
        }
        public void click() {
            Minecraft mc=Minecraft.getInstance();
            if(mc.player==null || mc.level==null || mc.gameMode==null)throw new IllegalStateException("Join a world before placing blocks");
            aim();
            if(!visible())throw new IllegalStateException("Placement surface is outside normal reach or no longer visible");
            if(air)mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);
            else mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,hit);
        }
    }
    private record Surface(BlockPos pos,Direction face,Vec3 point,boolean crouch) { }
    private static final List<Direction> FACES=List.of(Direction.UP,Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST,Direction.DOWN);
    private static final double[] FRACTIONS={0.5,0.2,0.8};
    private NativePlacement() { }
    public static Attempt find(BlockPos target,BlockState desired) {
        return find(target,new PlacementRecipes.Step(desired.getBlock().asItem(),desired,PlacementRecipes.Kind.PLACE,0));
    }
    public static Attempt find(BlockPos target,PlacementRecipes.Step step) {
        Minecraft mc=Minecraft.getInstance();var player=mc.player;
        if(player==null || mc.level==null || !mc.level.hasChunkAt(target))throw new IllegalStateException("Placement target is not loaded");
        var stack=player.getMainHandItem();
        if(!(stack.getItem() instanceof BlockItem item))throw new IllegalStateException("Selected item is not placeable");
        float oldYaw=player.getYRot(),oldPitch=player.getXRot();boolean oldShift=player.isShiftKeyDown();
        Set<String> predictions=new HashSet<>();
        try {
            for(var surface:placementSurfaces(target)) {
                var ray=ray(player.getEyePosition(),surface,ClipContext.Fluid.NONE);
                if(ray==null)continue;
                Vec3 delta=ray.getLocation().subtract(player.getEyePosition());
                float towardYaw=yaw(delta),towardPitch=pitch(delta);
                var yaws=new ArrayList<Float>();yaws.add(towardYaw);yaws.add(oldYaw);
                // Rotatable skulls and signs use sixteen horizontal orientations.
                for(int rotation=0;rotation<16;rotation++)yaws.add(rotation*22.5f);
                player.setShiftKeyDown(surface.crouch());
                for(float candidateYaw:yaws)for(float candidatePitch:new float[]{towardPitch,oldPitch,0,75,-75}) {
                    player.setYRot(candidateYaw);player.setXRot(candidatePitch);
                    BlockPlaceContext context=item.updatePlacementContext(new BlockPlaceContext(player,InteractionHand.MAIN_HAND,stack,ray));
                    if(context==null || !context.canPlace() || !context.getClickedPos().equals(target))continue;
                    BlockState predicted=((dev.aurora.minecraft.mixin.BlockItemPlacementInvoker)item).aurora$placementState(context);
                    predictions.add(String.valueOf(predicted));
                    if(predicted!=null && step.matches(predicted))return new Attempt(ray,candidateYaw,candidatePitch,false,surface.crouch());
                }
            }
        }finally {player.setYRot(oldYaw);player.setXRot(oldPitch);player.setShiftKeyDown(oldShift);}
        dev.aurora.minecraft.AuroraClient.LOG.debug("No placement context: target={} desired={} item={} predictions={}",target,step.state(),stack.getItem(),predictions);
        throw new IllegalStateException("Cannot place "+step.state()+" from this position at "+target.toShortString());
    }

    /** Geometry only: does not move the player, rotate them, or send packets while evaluating path goals. */
    public static boolean visibleFrom(BlockPos target,PlacementRecipes.Step step,Vec3 feet) {
        var mc=Minecraft.getInstance();var player=mc.player;
        if(player==null || mc.level==null || !mc.level.hasChunkAt(target))return false;
        if(step.kind()==PlacementRecipes.Kind.BUCKET)return BucketActions.visibleFrom(target,false,step.state().hasProperty(BlockStateProperties.WATERLOGGED),feet);
        double reach=PlacementReach.limit(player.blockInteractionRange());
        if(feet.distanceToSqr(Vec3.atCenterOf(target))>(reach+2)*(reach+2))return false;
        if(step.kind()==PlacementRecipes.Kind.PLACE) {
            double halfWidth=player.getBbWidth()/2.0;
            var body=new AABB(feet.x-halfWidth,feet.y,feet.z-halfWidth,feet.x+halfWidth,feet.y+player.getDimensions(Pose.STANDING).height(),feet.z+halfWidth);
            for(var box:step.state().getCollisionShape(mc.level,target).toAabbs())if(box.move(target).intersects(body))return false;
        }
        List<Surface> surfaces;
        ClipContext.Fluid fluid=ClipContext.Fluid.NONE;
        if(step.kind()==PlacementRecipes.Kind.AIR_USE) {
            surfaces=surfaces(target.below(),true,false);fluid=ClipContext.Fluid.SOURCE_ONLY;
        }else if(step.kind()==PlacementRecipes.Kind.INTERACT) {
            surfaces=surfaces(target,false,false);
        }else {
            surfaces=placementSurfaces(target);
        }
        for(var surface:surfaces) {
            Vec3 eyes=feet.add(0,player.getEyeHeight(surface.crouch()?Pose.CROUCHING:Pose.STANDING),0);
            if(ray(eyes,surface,fluid)!=null)return true;
        }
        return false;
    }
    public static Attempt interact(BlockPos target) {return rayTo(target,false,null);}
    public static Attempt airUse(BlockPos target,PlacementRecipes.Step step) {return rayTo(target.below(),true,step);}
    private static Attempt rayTo(BlockPos hitPos,boolean air,PlacementRecipes.Step step) {
        var mc=Minecraft.getInstance();var player=mc.player;
        for(var surface:surfaces(hitPos,air,false)) {
            var hit=ray(player.getEyePosition(),surface,air?ClipContext.Fluid.SOURCE_ONLY:ClipContext.Fluid.NONE);
            if(hit==null)continue;
            if(air) {
                if(!(player.getMainHandItem().getItem() instanceof BlockItem item))throw new IllegalStateException("Selected item is not placeable on water");
                var context=item.updatePlacementContext(new BlockPlaceContext(player,InteractionHand.MAIN_HAND,player.getMainHandItem(),hit.withPosition(hitPos.above())));
                if(context==null || !context.getClickedPos().equals(hitPos.above()))continue;
                var predicted=((dev.aurora.minecraft.mixin.BlockItemPlacementInvoker)item).aurora$placementState(context);
                if(predicted==null || !step.matches(predicted))continue;
            }
            var delta=hit.getLocation().subtract(player.getEyePosition());
            return new Attempt(hit,yaw(delta),pitch(delta),air,false);
        }
        throw new IllegalStateException("No visible interaction surface at "+hitPos.toShortString());
    }
    private static List<Surface> placementSurfaces(BlockPos target) {
        var mc=Minecraft.getInstance();var result=new ArrayList<Surface>();
        for(var direction:List.of(Direction.DOWN,Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST,Direction.UP)) {
            var support=target.relative(direction);var state=mc.level.getBlockState(support);
            addSurfaces(result,support,direction.getOpposite(),state.getShape(mc.level,support).toAabbs(),state.hasBlockEntity());
        }
        result.addAll(surfaces(target,false,mc.level.getBlockState(target).hasBlockEntity()));
        return result;
    }
    private static List<Surface> surfaces(BlockPos pos,boolean fluid,boolean crouch) {
        var mc=Minecraft.getInstance();var state=mc.level.getBlockState(pos);
        var boxes=state.getShape(mc.level,pos).toAabbs();
        if(boxes.isEmpty() && fluid && !state.getFluidState().isEmpty())boxes=List.of(new AABB(0,0,0,1,state.getFluidState().getOwnHeight(),1));
        var result=new ArrayList<Surface>();
        for(var face:FACES)addSurfaces(result,pos,face,boxes,crouch);
        return result;
    }
    private static void addSurfaces(List<Surface> out,BlockPos pos,Direction face,List<AABB> boxes,boolean crouch) {
        for(var box:boxes)for(double u:FRACTIONS)for(double v:FRACTIONS) {
            double x=box.minX+(box.maxX-box.minX)*u,y=box.minY+(box.maxY-box.minY)*u,z=box.minZ+(box.maxZ-box.minZ)*v;
            switch(face) {
                case UP -> {y=box.maxY;x=box.minX+(box.maxX-box.minX)*u;}
                case DOWN -> {y=box.minY;x=box.minX+(box.maxX-box.minX)*u;}
                case EAST -> x=box.maxX;
                case WEST -> x=box.minX;
                case NORTH -> {z=box.minZ;x=box.minX+(box.maxX-box.minX)*v;}
                case SOUTH -> {z=box.maxZ;x=box.minX+(box.maxX-box.minX)*v;}
            }
            out.add(new Surface(pos,face,new Vec3(pos.getX()+x,pos.getY()+y,pos.getZ()+z),crouch));
        }
    }
    private static BlockHitResult ray(Vec3 eyes,Surface surface,ClipContext.Fluid fluid) {
        var mc=Minecraft.getInstance();var player=mc.player;
        if(!mc.level.hasChunkAt(surface.pos()) || !PlacementReach.withinSquared(player.blockInteractionRange(),eyes.distanceToSqr(surface.point())))return null;
        var face=surface.face();
        Vec3 end=surface.point().add(-face.getStepX()*0.0001,-face.getStepY()*0.0001,-face.getStepZ()*0.0001);
        var hit=mc.level.clip(new ClipContext(eyes,end,ClipContext.Block.OUTLINE,fluid,player));
        if(hit.getType()!=HitResult.Type.BLOCK || !hit.getBlockPos().equals(surface.pos()) || hit.getDirection()!=face
            || !PlacementReach.withinSquared(player.blockInteractionRange(),eyes.distanceToSqr(hit.getLocation())))return null;
        return hit;
    }
    private static float yaw(Vec3 delta) {return (float)Math.toDegrees(Math.atan2(-delta.x,delta.z));}
    private static float pitch(Vec3 delta) {return (float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z)));}
}
