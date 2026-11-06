package dev.aurora.core.builder;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Tick-driven placement queue, independent of Minecraft, Fabric and Forge.
 *
 * The adapter owns pathfinding, rotation and inventory interaction. This class
 * owns ordering and acknowledgements. A successful click is never proof that a
 * block was placed: the pending action stays pending until the adapter observes
 * the server-confirmed block state.
 */
public final class BuildSession {
    public enum State { IDLE, MISSING_MATERIALS, BUILDING, RESTOCKING, PAUSED, COMPLETE }
    public enum Phase { SELECTING, PREPARING, WAITING_ACK, FETCHING_MATERIALS, STOPPED }
    public enum Restock { PENDING, COMPLETE, BLOCKED }

    public record Stock(Map<String, Integer> items, boolean inspected) {
        public Stock { items = Map.copyOf(items); }
    }

    public interface Driver {
        String worldIdentity();

        /** For a pending action, return true only after authoritative server confirmation. */
        boolean placed(BuildPlan.Block block);
        Map<String, Integer> inventory();
        Stock stock();
        Restock restock(Map<String, Integer> requirements);

        default Restock restockFor(BuildPlan.Block next, Map<String, Integer> requirements) {
            return restock(requirements);
        }

        /** Walk to a safe stance, then aim; return false while either is still in progress. */
        default boolean preparePlacement(BuildPlan.Block block) { return true; }

        /** Send exactly one validated native interaction. Do not advance the queue here. */
        void place(BuildPlan.Block block);
        default double placementPriority(BuildPlan.Block block) { return 0; }
        default boolean busyRestocking() { return false; }
        default String confirmationFailure(BuildPlan.Block block) {
            return "Placement was not confirmed; inspect the target block";
        }
        default String placementBlocker(BuildPlan.Block block) { return ""; }

        /** Check exact final states, including states updated later by neighboring blocks. */
        default boolean completionReady() { return true; }
    }

    private final BuildPlan plan;
    private final int acknowledgementTimeoutTicks;
    private final Map<BuildPlan.Block, String> deferred = new LinkedHashMap<>();
    private State state = State.IDLE;
    private Phase phase = Phase.STOPPED;
    private String world;
    private String status = "Idle";
    private BuildPlan.Block pending;
    private BuildPlan.Block preparing;
    private int pendingTicks;

    public BuildSession(BuildPlan plan) { this(plan, 100); }

    /** The timeout pauses; it never blindly repeats a possibly successful interaction. */
    public BuildSession(BuildPlan plan, int acknowledgementTimeoutTicks) {
        if (acknowledgementTimeoutTicks < 1 || acknowledgementTimeoutTicks > 1200) {
            throw new IllegalArgumentException("Acknowledgement timeout must be 1..1200 ticks");
        }
        this.plan = Objects.requireNonNull(plan);
        this.acknowledgementTimeoutTicks = acknowledgementTimeoutTicks;
    }

    public State state() { return state; }
    public Phase phase() { return phase; }
    public String status() { return status; }

    public boolean start(Driver driver) {
        pending = null;
        preparing = null;
        pendingTicks = 0;
        deferred.clear();
        world = driver.worldIdentity();
        List<BuildPlan.Block> remaining = plan.remaining(driver::placed);
        if (remaining.isEmpty()) {
            state = driver.completionReady() ? State.COMPLETE : State.BUILDING;
            phase = state == State.COMPLETE ? Phase.STOPPED : Phase.SELECTING;
            status = state == State.COMPLETE ? "Already built" : "Checking final schematic states";
            return true;
        }

        Map<String, Integer> required = BuildPlan.materials(remaining);
        Map<String, Integer> inventory = driver.inventory();
        Stock stock = driver.stock();
        if (!BuildPlan.missing(required, inventory).isEmpty() && !stock.inspected()) {
            return missingMaterials("Inspect all registered restock chests first");
        }
        Map<String, Integer> available = new HashMap<>(inventory);
        stock.items().forEach((item, count) -> available.merge(item, Math.max(0, count), Math::addExact));
        Map<String, Integer> missing = BuildPlan.missing(required, available);
        if (!missing.isEmpty()) return missingMaterials("Missing materials: " + missing);

        state = State.BUILDING;
        phase = Phase.SELECTING;
        status = "Materials verified";
        return true;
    }

    public void pause(String reason) {
        state = State.PAUSED;
        phase = Phase.STOPPED;
        pending = null;
        preparing = null;
        status = reason;
    }

    public void tick(Driver driver) {
        if (state != State.BUILDING && state != State.RESTOCKING) return;
        if (!Objects.equals(world, driver.worldIdentity())) {
            pause("World changed; start again explicitly");
            return;
        }
        if (!confirmPending(driver)) return;

        List<BuildPlan.Block> remaining = plan.remaining(driver::placed);
        if (remaining.isEmpty()) {
            finishWhenConfirmed(driver);
            return;
        }
        if (driver.busyRestocking()) {
            state = State.RESTOCKING;
            phase = Phase.FETCHING_MATERIALS;
            status = "Fetching the next inventory batch";
            return;
        }

        // Phase order protects the structure before fluids. Within each phase,
        // finish the lowest layer before considering any higher layer.
        BuildPlan.Block first = remaining.getFirst();
        List<BuildPlan.Block> layer = remaining.stream()
            .filter(block -> block.y() == first.y() && block.phase() == first.phase())
            .toList();
        List<BuildPlan.Block> eligible = new ArrayList<>();
        String firstBlocker = "";
        for (BuildPlan.Block block : layer) {
            String reason = driver.placementBlocker(block);
            if (reason.isBlank()) eligible.add(block);
            else if (firstBlocker.isBlank()) firstBlocker = reason;
        }

        Map<String, Integer> inventory = driver.inventory();
        BuildPlan.Block next = selectNext(driver, eligible, inventory);
        if (next == null) {
            preparing = null;
            BuildPlan.Block missing = eligible.stream()
                .filter(block -> !deferred.containsKey(block) && inventory.getOrDefault(block.item(), 0) < block.count())
                .findFirst().orElse(null);
            if (missing != null) fetchBatch(driver, missing, eligible);
            else {
                String reason = deferred.isEmpty() ? firstBlocker : deferred.values().iterator().next();
                pause("Layer " + first.y() + " blocked: " +
                    (reason.isBlank() ? "check support and recipe prerequisites" : reason));
            }
            return;
        }

        // Pin this action while navigating. Re-sorting every movement tick can
        // alternate between equally close targets and create circular movement.
        preparing = next;
        state = State.BUILDING;
        phase = Phase.PREPARING;
        status = "Building layer " + first.y();
        try {
            if (!driver.preparePlacement(next)) {
                status = "Finding a placement for layer " + first.y();
                return;
            }
        } catch (RuntimeException error) {
            preparing = null;
            deferred.put(next, message(error));
            phase = Phase.SELECTING;
            status = "Trying another block in layer " + first.y();
            return;
        }

        // Install the pending action before sending its interaction so an
        // immediate server callback cannot advance a different action.
        pending = next;
        preparing = null;
        pendingTicks = 0;
        phase = Phase.WAITING_ACK;
        try { driver.place(next); }
        catch (RuntimeException error) { pause("Placement failed: " + message(error)); }
    }

    private boolean confirmPending(Driver driver) {
        if (pending == null) return true;
        phase = Phase.WAITING_ACK;
        if (!driver.placed(pending)) {
            if (++pendingTicks >= acknowledgementTimeoutTicks) {
                pause(driver.confirmationFailure(pending));
            }
            return false;
        }
        pending = null;
        pendingTicks = 0;
        // A new support or cleared route can make a previously blocked action valid.
        deferred.clear();
        phase = Phase.SELECTING;
        return true;
    }

    private BuildPlan.Block selectNext(Driver driver, List<BuildPlan.Block> eligible,
                                       Map<String, Integer> inventory) {
        if (preparing != null && eligible.contains(preparing) && !deferred.containsKey(preparing)
            && inventory.getOrDefault(preparing.item(), 0) >= preparing.count()) return preparing;
        return eligible.stream()
            .filter(block -> !deferred.containsKey(block) && inventory.getOrDefault(block.item(), 0) >= block.count())
            .min(Comparator.comparingDouble(driver::placementPriority)).orElse(null);
    }

    private void fetchBatch(Driver driver, BuildPlan.Block next, List<BuildPlan.Block> eligible) {
        state = State.RESTOCKING;
        phase = Phase.FETCHING_MATERIALS;
        status = "Restocking part of layer " + next.y();
        try {
            if (driver.restockFor(next, BuildPlan.materials(eligible)) == Restock.BLOCKED) {
                pause("Cannot fetch the next item for layer " + next.y()
                    + "; free an inventory slot or check the restock chest");
            }
        } catch (RuntimeException error) { pause(message(error)); }
    }

    private void finishWhenConfirmed(Driver driver) {
        try {
            if (driver.completionReady()) {
                state = State.COMPLETE;
                phase = Phase.STOPPED;
                status = "Build complete";
            } else {
                state = State.BUILDING;
                phase = Phase.SELECTING;
                status = "Waiting for final schematic states";
            }
        } catch (RuntimeException error) { pause(message(error)); }
    }

    private boolean missingMaterials(String reason) {
        state = State.MISSING_MATERIALS;
        phase = Phase.STOPPED;
        status = reason;
        return false;
    }

    private static String message(RuntimeException error) {
        return error.getMessage() == null ? "Placement unavailable" : error.getMessage();
    }
}
