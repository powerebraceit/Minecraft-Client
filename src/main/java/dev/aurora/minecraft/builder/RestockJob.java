package dev.aurora.minecraft.builder;

import dev.aurora.core.builder.BuildPlan;
import dev.aurora.core.builder.BuildSession;
import dev.aurora.core.builder.RestockBatch;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.Direction;
import net.minecraft.core.registries.BuiltInRegistries;
import net.minecraft.world.InteractionHand;
import net.minecraft.world.inventory.AbstractContainerMenu;
import net.minecraft.world.inventory.ChestMenu;
import net.minecraft.world.inventory.ContainerInput;
import net.minecraft.world.item.ItemStack;
import net.minecraft.world.phys.BlockHitResult;
import net.minecraft.world.phys.Vec3;

/** Chest contents are trusted only after a server initializeContents packet, never block-entity guesses. */
public final class RestockJob {
    private final RestockChests chests;
    private final WalkNavigator navigator;
    private final Map<RestockChests.Chest,Map<String,Integer>> snapshots=new HashMap<>();
    private List<RestockChests.Chest> queue=List.of();
    private Map<String,Integer> desired=Map.of();
    private Object level;
    private boolean active,scan,failed;
    private int verifiedTransferred,minimumCount,expectedTransfer,openingDelay;
    private String priorityItem;
    public boolean failed() { return failed; }
    private int index,phase,ticks,steps,syncedId=-1,beforeTransfer;
    private String status="Idle",transferItem="";
    public RestockJob(RestockChests chests,WalkNavigator navigator) { this.chests=chests;this.navigator=navigator; }
    public boolean busy() { return active; }
    public String status() { return status; }
    public void invalidate() { snapshots.clear(); }
    public void contents(AbstractContainerMenu menu) {
        Minecraft mc=Minecraft.getInstance();
        if(active && (phase==1 || phase==4) && mc.player!=null && menu==mc.player.containerMenu && menu instanceof ChestMenu) syncedId=menu.containerId;
    }
    public BuildSession.Stock stock() {
        Minecraft mc=Minecraft.getInstance();
        if(level!=mc.level) { snapshots.clear();level=mc.level; }
        Map<String,Integer> all=new HashMap<>();
        boolean inspected=true;
        for(var chest:chests.current()) {
            var snapshot=snapshots.get(chest);
            if(snapshot==null) { inspected=false;continue; }
            snapshot.forEach((id,count)->all.merge(id,count,Math::addExact));
        }
        return new BuildSession.Stock(all,inspected);
    }
    public void startScan() { begin(true,Map.of(),null,0);snapshots.clear(); }
    public void startRestock(Map<String,Integer> wanted) { begin(false,wanted,null,0); }
    /** Requests a layer batch while reserving space for the action that triggered this restock. */
    public void startRestock(Map<String,Integer> wanted,String priority,int minimum) {begin(false,wanted,priority,minimum);}
    private void begin(boolean scanning,Map<String,Integer> wanted,String priority,int minimum) {
        if(active)throw new IllegalStateException("A chest operation is already active");
        Minecraft mc=Minecraft.getInstance();
        if(mc.gui.screen()!=null)throw new IllegalStateException("Close other menus first");
        queue=chests.current();
        if(queue.isEmpty())throw new IllegalStateException("Register a restock chest first");
        if(mc.player!=null)mc.player.setShiftKeyDown(false);
        failed=false;openingDelay=0;verifiedTransferred=0;priorityItem=priority;minimumCount=minimum;scan=scanning;desired=Map.copyOf(wanted);index=0;phase=0;ticks=0;steps=0;syncedId=-1;level=mc.level;active=true;status=scanning?"Inspecting restock chests":"Restocking";
    }
    public void cancel(String reason) {
        Minecraft mc=Minecraft.getInstance();
        if(active && mc.level==level && phase>0 && mc.player!=null && mc.player.containerMenu instanceof ChestMenu menu) {
            if(!menu.getCarried().isEmpty()) {
                returnHeldStack(menu);snapshots.clear();
                if(!menu.getCarried().isEmpty()) {
                    active=false;failed=true;phase=0;navigator.stop();status="Restock paused: put down the cursor stack before closing the chest";return;
                }
            }
            mc.player.closeContainer();
        }
        if(active)dev.aurora.minecraft.AuroraClient.LOG.info("Restock ended: {}",reason);
        failed=active && !reason.equals("Inspection complete") && !reason.equals("Restock complete");
        active=false;phase=0;navigator.stop();status=reason;
    }
    public void tick() {
        Minecraft mc=Minecraft.getInstance();
        if(!active)return;
        if(mc.level!=level || mc.player==null || !mc.player.isAlive()) { cancel("World changed or player unavailable");snapshots.clear();return; }
        // Let the ordinary input packet clear crouching on the server before using a chest.
        if(++openingDelay<=2) {mc.player.setShiftKeyDown(false);return;}
        if(++steps>2400) { cancel("Chest operation timed out");return; }
        try {
            if(index>=queue.size()) { cancel(scan?"Inspection complete":verifiedTransferred>0 && RestockBatch.minimumSatisfied(inventory(),priorityItem,minimumCount)?"Restock complete":"Required material unavailable or no inventory space; free a slot and retry");return; }
            var chest=queue.get(index);
            if(phase==0) {
                if(!scan && BuildPlan.missing(desired,inventory()).isEmpty()) { cancel("Restock complete");return; }
                if(mc.gui.screen()!=null) { cancel("Another menu interrupted restocking");return; }
                if(!navigator.walkTo(chest.pos(),2.7))return;
                if(mc.player.isShiftKeyDown()) {mc.player.setShiftKeyDown(false);return;}
                chests.canonical(chest.pos());
                lookAt(Vec3.atCenterOf(chest.pos()));
                syncedId=-1;
                mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(chest.pos()),Direction.UP,chest.pos(),false));
                phase=1;ticks=0;return;
            }
            if(++ticks>160) { cancel("Chest did not synchronize; it may be blocked or inaccessible");return; }
            // A correct vanilla predicted click may produce no revision packet at all.
            // Reopening obtains authoritative contents for both the chest and player slots.
            if(phase==2) {
                if(!(mc.player.containerMenu instanceof ChestMenu)) {cancel("Chest was closed during restock");return;}
                if(ticks<3)return;
                mc.player.closeContainer();phase=3;ticks=0;syncedId=-1;return;
            }
            if(phase==3) {
                if(ticks<3)return;
                if(mc.gui.screen()!=null) {cancel("Another menu interrupted verification");return;}
                if(mc.player.isShiftKeyDown()) {mc.player.setShiftKeyDown(false);return;}
                for(int i=0;i<9;i++)if(mc.player.getInventory().getItem(i).isEmpty()){mc.player.getInventory().setSelectedSlot(i);break;}
                lookAt(Vec3.atCenterOf(chest.pos()));
                mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(chest.pos()),Direction.UP,chest.pos(),false));
                phase=4;ticks=0;return;
            }
            if(!(mc.player.containerMenu instanceof ChestMenu menu)) {
                if(phase>1 && mc.gui.screen()!=null)cancel("Chest was closed during restock");
                return;
            }
            if(syncedId!=menu.containerId)return;
            if(phase==4) {
                if(inventory().getOrDefault(transferItem,0)!=beforeTransfer+expectedTransfer || !menu.getCarried().isEmpty()) {cancel("Chest transfer was not confirmed; inspect the inventory before retrying");return;}
                verifiedTransferred+=expectedTransfer;
                phase=1;ticks=0;
            }
            Map<String,Integer> stock=new HashMap<>();
            for(int slot=0;slot<menu.getRowCount()*9;slot++)add(stock,menu.getSlot(slot).getItem());
            snapshots.put(chest,Map.copyOf(stock));
            if(scan) { next();return; }
            Map<String,Integer> missing=BuildPlan.missing(desired,inventory());
            if(missing.isEmpty()) { cancel("Restock complete");return; }
            var sources=new ArrayList<RestockBatch.Source>();int chestSlots=menu.getRowCount()*9;
            for(int slot=0;slot<chestSlots;slot++) {
                ItemStack source=menu.getSlot(slot).getItem();
                if(source.isEmpty() || !missing.containsKey(itemId(source)))continue;
                var spaces=new ArrayList<RestockBatch.Space>();
                // Fill matching partial stacks before occupying another empty slot.
                for(boolean empty:new boolean[]{false,true})for(int destination=chestSlots;destination<menu.slots.size();destination++) {
                    var target=menu.getSlot(destination);var stack=target.getItem();
                    if(stack.isEmpty()!=empty || !target.mayPlace(source) || !stack.isEmpty() && !ItemStack.isSameItemSameComponents(source,stack))continue;
                    int capacity=Math.min(source.getMaxStackSize(),target.getMaxStackSize(source))-(stack.isEmpty()?0:stack.getCount());
                    if(capacity>0)spaces.add(new RestockBatch.Space(destination,capacity));
                }
                sources.add(new RestockBatch.Source(slot,itemId(source),source.getCount(),spaces));
            }
            var choice=RestockBatch.choose(sources,desired,inventory(),priorityItem,minimumCount);
            if(choice.isPresent()) {
                var transfer=choice.get();transferItem=transfer.item();beforeTransfer=inventory().getOrDefault(transferItem,0);expectedTransfer=transfer.count();
                exactTransfer(menu,transfer);phase=2;ticks=0;return;
            }
            // A full inventory is a completed batch if it contains useful, verified materials.
            // Resume the same build layer; a later restock can fetch the remainder after consumption.
            if(verifiedTransferred>0 && RestockBatch.minimumSatisfied(inventory(),priorityItem,minimumCount)
                && !hasRoomForMissing(menu,missing)) {cancel("Restock complete");return;}
            next();
        }catch(RuntimeException error) { cancel(error.getMessage()==null?"Restock failed":error.getMessage()); }
    }
    private static boolean hasRoomForMissing(ChestMenu menu,Map<String,Integer> missing) {
        for(int slot=menu.getRowCount()*9;slot<menu.slots.size();slot++) {
            var stack=menu.getSlot(slot).getItem();
            if(stack.isEmpty() || missing.containsKey(itemId(stack)) && stack.getCount()<stack.getMaxStackSize())return true;
        }
        return false;
    }
    private static void exactTransfer(ChestMenu menu,RestockBatch.Transfer transfer) {
        var mc=Minecraft.getInstance();
        if(!menu.getCarried().isEmpty())throw new IllegalStateException("Put down the item held by the cursor before restocking");
        int sourceCount=menu.getSlot(transfer.source()).getItem().getCount();
        mc.gameMode.handleContainerInput(menu.containerId,transfer.source(),0,ContainerInput.PICKUP,mc.player);
        if(transfer.count()==sourceCount) {
            mc.gameMode.handleContainerInput(menu.containerId,transfer.destination(),0,ContainerInput.PICKUP,mc.player);
        }else {
            // Keep all cursor actions in one tick; cancellation cannot interrupt a partly held stack.
            for(int i=0;i<transfer.count();i++)mc.gameMode.handleContainerInput(menu.containerId,transfer.destination(),1,ContainerInput.PICKUP,mc.player);
            mc.gameMode.handleContainerInput(menu.containerId,transfer.source(),0,ContainerInput.PICKUP,mc.player);
        }
        if(!menu.getCarried().isEmpty())throw new IllegalStateException("Chest transfer left an item on the cursor; return it before retrying");
    }
    private static void returnHeldStack(ChestMenu menu) {
        var mc=Minecraft.getInstance();
        for(int slot=0;slot<menu.slots.size() && !menu.getCarried().isEmpty();slot++) {
            var target=menu.getSlot(slot);var stack=target.getItem();var carried=menu.getCarried();
            if(!target.mayPlace(carried) || !stack.isEmpty() && !ItemStack.isSameItemSameComponents(carried,stack))continue;
            if(stack.isEmpty() || stack.getCount()<Math.min(stack.getMaxStackSize(),target.getMaxStackSize(carried)))
                mc.gameMode.handleContainerInput(menu.containerId,slot,0,ContainerInput.PICKUP,mc.player);
        }
    }
    private void next() {
        Minecraft.getInstance().player.closeContainer();index++;phase=0;ticks=0;syncedId=-1;navigator.stop();
    }
    public static String itemId(ItemStack stack) { return BuiltInRegistries.ITEM.getKey(stack.getItem()).toString(); }
    private static void add(Map<String,Integer> counts,ItemStack stack) {
        if(!stack.isEmpty())counts.merge(itemId(stack),stack.getCount(),Math::addExact);
    }
    public static Map<String,Integer> inventory() {
        Minecraft mc=Minecraft.getInstance();Map<String,Integer> result=new HashMap<>();
        if(mc.player!=null)for(int i=0;i<36;i++)add(result,mc.player.getInventory().getItem(i));
        return Map.copyOf(result);
    }
    public static void lookAt(Vec3 target) {
        Minecraft mc=Minecraft.getInstance();Vec3 delta=target.subtract(mc.player.getEyePosition());
        mc.player.setYRot((float)Math.toDegrees(Math.atan2(-delta.x,delta.z)));
        mc.player.setXRot((float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z))));
    }
}
