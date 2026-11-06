package dev.aurora.minecraft.builder;

import dev.aurora.core.builder.BuildPlan;
import dev.aurora.core.builder.BuildSession;
import java.util.*;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
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
    private int initialTotal;
    public boolean failed() { return failed; }
    private int index,phase,ticks,steps,syncedId=-1,sentRevision,beforeTransfer;
    private String status="Idle",transferItem="";
    public RestockJob(RestockChests chests,WalkNavigator navigator) { this.chests=chests;this.navigator=navigator; }
    public boolean busy() { return active; }
    public String status() { return status; }
    public void invalidate() { snapshots.clear(); }
    public void contents(AbstractContainerMenu menu) {
        Minecraft mc=Minecraft.getInstance();
        if(active && phase==1 && mc.player!=null && menu==mc.player.containerMenu && menu instanceof ChestMenu) syncedId=menu.containerId;
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
    public void startScan() { begin(true,Map.of());snapshots.clear(); }
    public void startRestock(Map<String,Integer> wanted) { begin(false,wanted); }
    private void begin(boolean scanning,Map<String,Integer> wanted) {
        if(active)throw new IllegalStateException("A chest operation is already active");
        Minecraft mc=Minecraft.getInstance();
        if(mc.gui.screen()!=null)throw new IllegalStateException("Close other menus first");
        queue=chests.current();
        if(queue.isEmpty())throw new IllegalStateException("Register a restock chest first");
        failed=false;initialTotal=totalInventory();scan=scanning;desired=Map.copyOf(wanted);index=0;phase=0;ticks=0;steps=0;syncedId=-1;level=mc.level;active=true;status=scanning?"Inspecting restock chests":"Restocking";
    }
    public void cancel(String reason) {
        Minecraft mc=Minecraft.getInstance();
        if(active && mc.level==level && phase>0 && mc.player!=null && mc.player.containerMenu instanceof ChestMenu)mc.player.closeContainer();
        failed=active && !reason.equals("Inspection complete") && !reason.equals("Restock complete");
        active=false;phase=0;navigator.stop();status=reason;
    }
    public void tick() {
        Minecraft mc=Minecraft.getInstance();
        if(!active)return;
        if(mc.level!=level || mc.player==null || !mc.player.isAlive()) { cancel("World changed or player unavailable");snapshots.clear();return; }
        if(++steps>2400) { cancel("Chest operation timed out");return; }
        try {
            if(index>=queue.size()) { cancel(scan?"Inspection complete":totalInventory()>initialTotal?"Restock complete":"Required materials are not available in registered chests");return; }
            var chest=queue.get(index);
            if(phase==0) {
                if(!scan && BuildPlan.missing(desired,inventory()).isEmpty()) { cancel("Restock complete");return; }
                if(mc.gui.screen()!=null) { cancel("Another menu interrupted restocking");return; }
                if(!navigator.walkTo(chest.pos(),2.7))return;
                chests.canonical(chest.pos());
                lookAt(Vec3.atCenterOf(chest.pos()));
                syncedId=-1;
                mc.gameMode.useItemOn(mc.player,InteractionHand.MAIN_HAND,new BlockHitResult(Vec3.atCenterOf(chest.pos()),Direction.UP,chest.pos(),false));
                phase=1;ticks=0;return;
            }
            if(++ticks>160) { cancel("Chest did not synchronize; it may be blocked or inaccessible");return; }
            if(!(mc.player.containerMenu instanceof ChestMenu menu)) {
                if(phase>1)cancel("Chest was closed during restock");
                return;
            }
            if(syncedId!=menu.containerId)return;
            if(phase==2) {
                if(menu.getStateId()==sentRevision)return;
                if(inventory().getOrDefault(transferItem,0)<=beforeTransfer) { cancel("Inventory full or chest transfer rejected");return; }
                phase=1;ticks=0;
            }
            Map<String,Integer> stock=new HashMap<>();
            for(int slot=0;slot<menu.getRowCount()*9;slot++)add(stock,menu.getSlot(slot).getItem());
            snapshots.put(chest,Map.copyOf(stock));
            if(scan) { next();return; }
            Map<String,Integer> missing=BuildPlan.missing(desired,inventory());
            if(missing.isEmpty()) { cancel("Restock complete");return; }
            for(int slot=0;slot<menu.getRowCount()*9;slot++) {
                ItemStack stack=menu.getSlot(slot).getItem();
                if(!stack.isEmpty() && missing.containsKey(itemId(stack))) {
                    transferItem=itemId(stack);beforeTransfer=inventory().getOrDefault(transferItem,0);sentRevision=menu.getStateId();
                    mc.gameMode.handleContainerInput(menu.containerId,slot,0,ContainerInput.QUICK_MOVE,mc.player);
                    phase=2;ticks=0;return;
                }
            }
            next();
        }catch(RuntimeException error) { cancel(error.getMessage()==null?"Restock failed":error.getMessage()); }
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
    private static int totalInventory() { return inventory().values().stream().mapToInt(Integer::intValue).sum(); }
    public static void lookAt(Vec3 target) {
        Minecraft mc=Minecraft.getInstance();Vec3 delta=target.subtract(mc.player.getEyePosition());
        mc.player.setYRot((float)Math.toDegrees(Math.atan2(-delta.x,delta.z)));
        mc.player.setXRot((float)-Math.toDegrees(Math.atan2(delta.y,Math.hypot(delta.x,delta.z))));
    }
}
