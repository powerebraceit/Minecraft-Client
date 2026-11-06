package dev.aurora.core.builder;

import java.util.List;
import java.util.Map;
import java.util.HashMap;

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
    }
    private final BuildPlan plan;
    private State state = State.IDLE;
    private String world, status = "Idle";
    private BuildPlan.Block pending;
    private int pendingTicks;
    public BuildSession(BuildPlan plan) { this.plan = plan; }
    public State state() { return state; }
    public String status() { return status; }

    public boolean start(Driver driver) {
        pending = null; pendingTicks = 0;
        var remaining = plan.remaining(driver::placed);
        if (remaining.isEmpty()) { state = State.COMPLETE; status = "Already built"; return true; }
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

    public void pause(String reason) { state = State.PAUSED; pending = null; status = reason; }
    public void tick(Driver driver) {
        if (state != State.BUILDING && state != State.RESTOCKING) return;
        if (!java.util.Objects.equals(world, driver.worldIdentity())) { pause("World changed; start again explicitly"); return; }
        if (pending != null) {
            if (driver.placed(pending)) { pending = null; pendingTicks = 0; }
            else { if (++pendingTicks >= 100) pause("Placement was not confirmed; inspect the target block"); return; }
        }
        List<BuildPlan.Block> remaining = plan.remaining(driver::placed);
        if (remaining.isEmpty()) { state = State.COMPLETE; status = "Build complete"; return; }
        int layer = remaining.getFirst().y();
        var layerBlocks = remaining.stream().filter(block -> block.y() == layer && BuildPlan.fluid(block)==BuildPlan.fluid(remaining.getFirst())).toList();
        BuildPlan.Block next = layerBlocks.getFirst();
        if (driver.inventory().getOrDefault(next.item(), 0) < next.count()) {
            state = State.RESTOCKING; status = "Restocking layer " + layer;
            if (driver.restockFor(next,BuildPlan.materials(layerBlocks)) == Restock.BLOCKED) pause("Restock unavailable or inventory full");
            return;
        }
        state = State.BUILDING; status = "Building layer " + layer;
        try {
            if (!driver.preparePlacement(next)) { status = "Moving to layer " + layer; return; }
        } catch (RuntimeException error) { pause(error.getMessage()); return; }
        pending = next; pendingTicks = 0;
        try { driver.place(next); }
        catch (RuntimeException error) { pause("Placement failed: " + error.getMessage()); }
    }
}
