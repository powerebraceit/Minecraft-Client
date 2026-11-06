package dev.aurora.core.builder;

import java.util.List;
import java.util.Map;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.ArrayList;

/** Placement scheduling only. A game driver must provide observed inventory and server-confirmed blocks. */
public final class BuildSession {
    public enum State { IDLE, MISSING_MATERIALS, BUILDING, RESTOCKING, PAUSED, COMPLETE }
    public enum Restock { PENDING, COMPLETE, BLOCKED }
    public record Stock(Map<String, Integer> items, boolean inspected) {
        public Stock { items = Map.copyOf(items); }
    }
    public interface Driver {
        String worldIdentity();
        boolean placed(BuildPlan.Block block);
        Map<String, Integer> inventory();
        Stock stock();
        Restock restock(Map<String, Integer> layerRequirements);
        default Restock restockFor(BuildPlan.Block next,Map<String,Integer> requirements) {return restock(requirements);}
        default boolean preparePlacement(BuildPlan.Block block) { return true; }
        void place(BuildPlan.Block block);
        default double placementPriority(BuildPlan.Block block) {return 0;}
        default boolean busyRestocking() {return false;}
        default String placementBlocker(BuildPlan.Block block) {return "";}
        default boolean completionReady() {return true;}
    }
    private final BuildPlan plan;
    private State state = State.IDLE;
    private String world, status = "Idle";
    private BuildPlan.Block pending,preparing;
    private int pendingTicks;
    private final Map<BuildPlan.Block,String> deferred=new LinkedHashMap<>();
    public BuildSession(BuildPlan plan) { this.plan = plan; }
    public State state() { return state; }
    public String status() { return status; }

    public boolean start(Driver driver) {
        pending = null;preparing=null; pendingTicks = 0;deferred.clear();
        var remaining = plan.remaining(driver::placed);
        if (remaining.isEmpty()) {world=driver.worldIdentity();state=driver.completionReady()?State.COMPLETE:State.BUILDING;status=state==State.COMPLETE?"Already built":"Checking final schematic states";return true;}
        var required = BuildPlan.materials(remaining);
        Stock stock = driver.stock();
        if (!BuildPlan.missing(required, driver.inventory()).isEmpty() && !stock.inspected) {
            state = State.MISSING_MATERIALS; status = "Inspect all registered restock chests first"; return false;
        }
        Map<String, Integer> available = new HashMap<>(driver.inventory());
        stock.items.forEach((item, count) -> available.merge(item, Math.max(0, count), Math::addExact));
        var missing = BuildPlan.missing(required, available);
        if (!missing.isEmpty()) { state = State.MISSING_MATERIALS; status = "Missing materials: " + missing; return false; }
        world = driver.worldIdentity(); state = State.BUILDING; status = "Materials verified"; return true;
    }

    public void pause(String reason) { state = State.PAUSED; pending = null;preparing=null; status = reason; }
    public void tick(Driver driver) {
        if (state != State.BUILDING && state != State.RESTOCKING) return;
        if (!java.util.Objects.equals(world, driver.worldIdentity())) { pause("World changed; start again explicitly"); return; }
        if (pending != null) {
            if (driver.placed(pending)) { pending = null; pendingTicks = 0;deferred.clear(); }
            else { if (++pendingTicks >= 100) pause("Placement was not confirmed; inspect the target block"); return; }
        }
        List<BuildPlan.Block> remaining = plan.remaining(driver::placed);
        if (remaining.isEmpty()) {
            try {if(driver.completionReady()){state=State.COMPLETE;status="Build complete";}else {state=State.BUILDING;status="Waiting for final schematic states";}}
            catch(RuntimeException error){pause(error.getMessage());}return;
        }
        if(driver.busyRestocking()) {state=State.RESTOCKING;status="Fetching the next inventory batch";return;}
        int layer = remaining.getFirst().y();
        var layerBlocks = remaining.stream().filter(block -> block.y() == layer && block.phase()==remaining.getFirst().phase()).toList();
        var eligible=new ArrayList<BuildPlan.Block>();String blocker="";
        for(var block:layerBlocks) {
            String reason=driver.placementBlocker(block);
            if(reason.isBlank())eligible.add(block);else if(blocker.isBlank())blocker=reason;
        }
        var inventory=driver.inventory();
        BuildPlan.Block next=preparing;
        if(next==null || !eligible.contains(next) || deferred.containsKey(next) || inventory.getOrDefault(next.item(),0)<next.count())
            next=eligible.stream().filter(b->!deferred.containsKey(b) && inventory.getOrDefault(b.item(),0)>=b.count()).min(java.util.Comparator.comparingDouble(driver::placementPriority)).orElse(null);
        if(next==null) {
            preparing=null;
            next=eligible.stream().filter(b->!deferred.containsKey(b) && inventory.getOrDefault(b.item(),0)<b.count()).findFirst().orElse(null);
            if(next!=null) {
                state=State.RESTOCKING;status="Restocking part of layer "+layer;
                try {if(driver.restockFor(next,BuildPlan.materials(eligible))==Restock.BLOCKED)pause("Cannot fetch the next item for layer "+layer+"; free an inventory slot or check the restock chest");}
                catch(RuntimeException error){pause(error.getMessage());}return;
            }
            String reason=deferred.isEmpty()?blocker:deferred.values().iterator().next();
            pause("Layer "+layer+" blocked: "+(reason.isBlank()?"check support and recipe prerequisites":reason));return;
        }
        preparing=next;
        state = State.BUILDING; status = "Building layer " + layer;
        try {
            if (!driver.preparePlacement(next)) { status = "Finding a placement for layer " + layer; return; }
        } catch (RuntimeException error) {
            preparing=null;
            deferred.put(next,error.getMessage()==null?"Placement unavailable":error.getMessage());
            status="Trying another block in layer "+layer;return;
        }
        pending = next;preparing=null; pendingTicks = 0;
        try { driver.place(next); }
        catch (RuntimeException error) { pause("Placement failed: " + error.getMessage()); }
    }
}
