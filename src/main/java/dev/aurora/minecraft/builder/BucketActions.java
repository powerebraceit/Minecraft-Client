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
    public static Aim find(BlockPos target,boolean pickup) {
        var mc=Minecraft.getInstance();var player=mc.player;var stack=player.getMainHandItem();
        if(!(stack.getItem() instanceof BucketItem bucket))throw new IllegalStateException("Select a bucket first");
        if(!pickup && stack.is(Items.WATER_BUCKET) && mc.level.environmentAttributes().getValue(EnvironmentAttributes.WATER_EVAPORATES,target))
            throw new IllegalStateException("Water evaporates here; move the build and water pool to another dimension");
        List<Vec3> points=new ArrayList<>();
        if(pickup) {
            if(!mc.level.getFluidState(target).isSource())throw new IllegalStateException("Water pool is not a source yet");
            for(double x:new double[]{0.5,0.25,0.75})for(double z:new double[]{0.5,0.25,0.75})points.add(new Vec3(target.getX()+x,target.getY()+0.8,target.getZ()+z));
        }else for(Direction side:List.of(Direction.DOWN,Direction.NORTH,Direction.SOUTH,Direction.WEST,Direction.EAST,Direction.UP)) {
            BlockPos support=target.relative(side);var state=mc.level.getBlockState(support);
            var face=side.getOpposite();
            if(!state.isFaceSturdy(mc.level,support,face) || state.getBlock() instanceof LiquidBlockContainer)continue;
            var center=Vec3.atCenterOf(support);
            points.add(center.add(face.getStepX()*0.501,face.getStepY()*0.501,face.getStepZ()*0.501));
        }
        for(var point:points) {
            Vec3 delta=point.subtract(player.getEyePosition());
            if(delta.length()>player.blockInteractionRange())continue;
            Vec3 end=player.getEyePosition().add(delta.normalize().scale(player.blockInteractionRange()));
            var hit=mc.level.clip(new ClipContext(player.getEyePosition(),end,ClipContext.Block.OUTLINE,bucket.getFluidContext(),player));
            if(hit.getType()!=HitResult.Type.BLOCK)continue;
            BlockPos predicted=pickup?hit.getBlockPos():hit.getBlockPos().relative(hit.getDirection());
            if(!predicted.equals(target))continue;
            if(!pickup && mc.level.getBlockState(hit.getBlockPos()).getBlock() instanceof LiquidBlockContainer)continue;
            return new Aim((float)Math.toDegrees(Math.atan2(-delta.x,delta.z)),(float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z))));
        }
        throw new IllegalStateException("No reachable bucket ray reaches "+target.toShortString());
    }
}
