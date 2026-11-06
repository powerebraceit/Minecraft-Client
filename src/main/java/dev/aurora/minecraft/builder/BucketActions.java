package dev.aurora.minecraft.builder;

import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.*;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.attribute.EnvironmentAttributes;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.*;
import net.minecraft.world.level.ClipContext;
import net.minecraft.world.level.block.LiquidBlockContainer;
import net.minecraft.world.phys.*;

/** Native bucket use with line-of-sight checks and authoritative inventory evidence. */
public final class BucketActions {
    private final Map<String,Long> items=new HashMap<>();
    private long serial;
    private int swapSlot=-1,swapTicks;
    private String swapItem;
    public void confirmedInventory(int slot,ItemStack stack) {
        String id=stack.isEmpty()?"minecraft:air":RestockJob.itemId(stack);
        items.put(id,++serial);
    }
    public long serial() {return serial;}
    public boolean observed(String item,long after) {return items.getOrDefault(item,0L)>after;}
    public void cancel() {swapSlot=-1;swapTicks=0;}
    public boolean select(String item) {
        var mc=Minecraft.getInstance();var inv=mc.player.getInventory();
        if(swapSlot>=0) {
            // Correct predicted inventory swaps may produce no server packet. Use-item results and
            // world changes are checked separately; don't wait for a nonexistent container revision.
            if(++swapTicks<2)return false;
            if(inv.getItem(swapSlot).isEmpty() || !RestockJob.itemId(inv.getItem(swapSlot)).equals(swapItem))
                throw new IllegalStateException("Inventory swap was rejected; inspect the selected item");
            swapSlot=-1;swapTicks=0;
        }
        for(int i=0;i<36;i++) {
            var stack=inv.getItem(i);if(stack.isEmpty() || !RestockJob.itemId(stack).equals(item))continue;
            if(i<9) {
                if(item.equals("minecraft:bucket") && stack.getCount()>1) {
                    boolean space=false;for(int j=0;j<36;j++)if(inv.getItem(j).isEmpty())space=true;
                    if(!space)throw new IllegalStateException("Free an inventory slot before refilling stacked buckets");
                }
                inv.setSelectedSlot(i);return true;
            }
            swapSlot=inv.getSelectedSlot();
            for(int j=0;j<9;j++)if(inv.getItem(j).isEmpty()){swapSlot=j;break;}
            swapItem=item;swapTicks=0;
            mc.gameMode.handleContainerInput(mc.player.inventoryMenu.containerId,i,swapSlot,ContainerInput.SWAP,mc.player);return false;
        }
        throw new IllegalStateException("Required bucket is missing: "+item);
    }
    public record Aim(float yaw,float pitch) {
        public void aim() {var p=Minecraft.getInstance().player;p.setYRot(yaw);p.setXRot(pitch);}
        public void use() {aim();var mc=Minecraft.getInstance();mc.gameMode.useItem(mc.player,InteractionHand.MAIN_HAND);}
    }
    public static Aim find(BlockPos target,boolean pickup) {return find(target,pickup,false);}
    public static Aim find(BlockPos target,boolean pickup,boolean waterlog) {
        var mc=Minecraft.getInstance();var player=mc.player;var stack=player.getMainHandItem();
        if(!(stack.getItem() instanceof BucketItem))throw new IllegalStateException("Select a bucket first");
        if(!pickup && stack.is(Items.WATER_BUCKET) && mc.level.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES,target))
            throw new IllegalStateException("Water evaporates here; move the build and water pool to another dimension");
        if(pickup && !mc.level.getFluidState(target).isSource())throw new IllegalStateException("Water pool is not a source yet");
        Vec3 direction=bucketRay(target,pickup,waterlog,player.getEyePosition());
        if(direction==null)throw new IllegalStateException("No reachable bucket ray reaches "+target.toShortString());
        return new Aim((float)Math.toDegrees(Math.atan2(-direction.x,direction.z)),(float)-Math.toDegrees(Math.atan2(direction.y,Math.hypot(direction.x,direction.z))));
    }
    /** Uses precisely the same points, native fluid clipping and target prediction as actual bucket use. */
    public static boolean visibleFrom(BlockPos target,boolean pickup,boolean waterlog,Vec3 feet) {
        var mc=Minecraft.getInstance();
        if(mc.player==null || mc.level==null || !(mc.player.getMainHandItem().getItem() instanceof BucketItem))return false;
        double reach=mc.player.blockInteractionRange();
        if(feet.distanceToSqr(Vec3.atCenterOf(target))>(reach+2)*(reach+2))return false;
        if(pickup && !mc.level.getFluidState(target).isSource())return false;
        return bucketRay(target,pickup,waterlog,feet.add(0,mc.player.getEyeHeight(net.minecraft.world.entity.Pose.STANDING),0))!=null;
    }
    private static Vec3 bucketRay(BlockPos target,boolean pickup,boolean waterlog,Vec3 eyes) {
        var mc=Minecraft.getInstance();var player=mc.player;var stack=player.getMainHandItem();
        if(!(stack.getItem() instanceof BucketItem bucket))return null;
        double reach=player.blockInteractionRange();
        for(var point:points(target,pickup,waterlog)) {
            Vec3 delta=point.subtract(eyes);
            if(delta.lengthSqr()>reach*reach || delta.lengthSqr()<0.000001)continue;
            Vec3 end=eyes.add(delta.normalize().scale(reach));
            var hit=mc.level.clip(new ClipContext(eyes,end,ClipContext.Block.OUTLINE,bucket.getFluidContext(),player));
            if(hit.getType()!=HitResult.Type.BLOCK)continue;
            boolean container=mc.level.getBlockState(hit.getBlockPos()).getBlock() instanceof LiquidBlockContainer;
            BlockPos predicted=pickup || stack.is(Items.WATER_BUCKET) && container?hit.getBlockPos():hit.getBlockPos().relative(hit.getDirection());
            if(!predicted.equals(target) || !pickup && container!=waterlog)continue;
            return delta;
        }
        return null;
    }
    private static List<Vec3> points(BlockPos target,boolean pickup,boolean waterlog) {
        var mc=Minecraft.getInstance();var points=new ArrayList<Vec3>();
        if(waterlog) {
            // Waterloggable fences and stairs can have holes at their overall bounds' center.
            for(var bounds:mc.level.getBlockState(target).getShape(mc.level,target).toAabbs())
                for(double x:new double[]{0.5,0.25,0.75})for(double y:new double[]{0.5,0.9,0.1})for(double z:new double[]{0.5,0.25,0.75})
                    points.add(new Vec3(target.getX()+bounds.minX+(bounds.maxX-bounds.minX)*x,target.getY()+bounds.minY+(bounds.maxY-bounds.minY)*y,target.getZ()+bounds.minZ+(bounds.maxZ-bounds.minZ)*z));
        }else if(pickup) {
            double height=mc.level.getFluidState(target).getOwnHeight()*0.9;
            for(double x:new double[]{0.5,0.25,0.75})for(double z:new double[]{0.5,0.25,0.75})points.add(new Vec3(target.getX()+x,target.getY()+height,target.getZ()+z));
        }else for(Direction side:List.of(Direction.DOWN,Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST,Direction.UP)) {
            var support=target.relative(side);var state=mc.level.getBlockState(support);var face=side.getOpposite();
            if(!state.isFaceSturdy(mc.level,support,face) || state.getBlock() instanceof LiquidBlockContainer)continue;
            for(double u:new double[]{0.5,0.25,0.75})for(double v:new double[]{0.5,0.25,0.75}) {
                double x=0.5,y=u,z=v;
                switch(face) {
                    case UP -> {x=u;y=1.001;}
                    case DOWN -> {x=u;y=-0.001;}
                    case EAST -> x=1.001;
                    case WEST -> x=-0.001;
                    case NORTH -> {x=v;z=-0.001;}
                    case SOUTH -> {x=v;z=1.001;}
                }
                points.add(new Vec3(support.getX()+x,support.getY()+y,support.getZ()+z));
            }
        }
        return points;
    }
}
