package dev.aurora.core.builder;

import java.util.*;

/**
 * Loader-independent A* search over safe integer foot positions.
 *
 * <p>The Minecraft adapter supplies safety and collision queries. This class never changes the
 * world, moves the player, or assumes that air is walkable. Call it again when terrain changes.
 * Goals are valid standing positions from which the placement adapter has already checked reach
 * and line of sight; the block being built does not itself need to be walkable.
 */
public final class AStarPathfinder {
    public static final int FLAT_COST = 10;
    public static final int STEP_COST = 14;

    public record Cell(int x, int y, int z) {
        public Cell offset(int dx, int dy, int dz) {
            return new Cell(Math.addExact(x, dx), Math.addExact(y, dy), Math.addExact(z, dz));
        }
    }

    /** Both methods must read the current world without changing it. Unloaded terrain is unsafe. */
    public interface Terrain {
        /** Requires foot/head clearance, a supporting floor and no lava, fire or other hazards. */
        boolean canStand(Cell feet);
        /** Requires empty collision space; used to check the swept space of a one-block step. */
        boolean isClear(Cell cell);
        /** An adapter may reject a temporarily stalled directed edge without blocking its cell. */
        default boolean canTraverse(Cell from, Cell to) { return true; }
    }

    public record Bounds(int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        public Bounds {
            if (minX > maxX || minY > maxY || minZ > maxZ)
                throw new IllegalArgumentException("Inverted search bounds");
        }
        public boolean contains(Cell cell) {
            return cell.x >= minX && cell.x <= maxX && cell.y >= minY && cell.y <= maxY
                && cell.z >= minZ && cell.z <= maxZ;
        }
    }

    public enum Status { REACHED, UNREACHABLE, NODE_BUDGET_EXHAUSTED, INVALID_START, NO_VALID_GOALS }

    /**
     * A reached path excludes the starting cell and includes the selected standing goal.
     * Failure always returns an empty path and goal: an unfinished search is never a movement plan.
     * The cost is {@link Long#MAX_VALUE} on failure; expanded counts nodes whose neighbors were read.
     */
    public record Result(List<Cell> path, Optional<Cell> reachedGoal, int expanded, long cost, Status status) {
        public Result {
            path = List.copyOf(path);
            Objects.requireNonNull(reachedGoal);
            Objects.requireNonNull(status);
            if (status == Status.REACHED != reachedGoal.isPresent()
                || status != Status.REACHED && !path.isEmpty())
                throw new IllegalArgumentException("Only reached goals may provide an executable path");
        }
        public boolean reached() { return status == Status.REACHED; }
    }

    private record Entry(Cell cell, long g, long h, long order) { }
    private static final int[][] DIRECTIONS = {{1, 0}, {-1, 0}, {0, 1}, {0, -1}};
    private static final int[] HEIGHT_CHANGES = {0, 1, -1};
    private AStarPathfinder() { }

    /**
     * Finds the least-cost reachable goal within the supplied bounds and expansion budget.
     * Flat cardinal moves cost 10; a one-block rise or descent costs 14. There is no diagonal
     * corner-cutting, flying, digging, or falling through an arbitrary number of blocks.
     */
    public static Result search(Cell start, Collection<Cell> candidates, Terrain terrain,
                                Bounds bounds, int maxExpanded) {
        Objects.requireNonNull(start);
        Objects.requireNonNull(candidates);
        Objects.requireNonNull(terrain);
        Objects.requireNonNull(bounds);
        if (maxExpanded <= 0) throw new IllegalArgumentException("Expansion budget must be positive");

        // Queries are cached only for this synchronous search, never across changing game ticks.
        Map<Cell, Boolean> standing = new HashMap<>();
        Map<Cell, Boolean> clearance = new HashMap<>();
        if (!bounds.contains(start) || !standing.computeIfAbsent(start, terrain::canStand))
            return failure(Status.INVALID_START, 0);

        Set<Cell> goals = new HashSet<>();
        for (Cell candidate : candidates) {
            Objects.requireNonNull(candidate);
            if (bounds.contains(candidate) && standing.computeIfAbsent(candidate, terrain::canStand))
                goals.add(candidate);
        }
        if (goals.isEmpty()) return failure(Status.NO_VALID_GOALS, 0);

        // Stable ties prevent identical replans from choosing arbitrary left/right detours.
        Comparator<Entry> priority = Comparator.comparingLong((Entry e) -> e.g + e.h)
            .thenComparingLong(Entry::h).thenComparingLong(Entry::order);
        PriorityQueue<Entry> open = new PriorityQueue<>(priority);
        Map<Cell, Long> bestCost = new HashMap<>();
        Map<Cell, Cell> parent = new HashMap<>();
        long order = 0;
        bestCost.put(start, 0L);
        open.add(new Entry(start, 0, heuristic(start, goals), order++));
        int expanded = 0;

        while (!open.isEmpty()) {
            Entry current = open.remove();
            // A cheaper route may have queued a replacement before the old entry was popped.
            if (current.g != bestCost.getOrDefault(current.cell, Long.MAX_VALUE)) continue;
            if (goals.contains(current.cell))
                return reached(start, current.cell, parent, expanded, current.g);
            if (expanded >= maxExpanded) return failure(Status.NODE_BUDGET_EXHAUSTED, expanded);
            expanded++;

            for (int[] direction : DIRECTIONS) for (int dy : HEIGHT_CHANGES) {
                Cell next = offsetWithin(current.cell, direction[0], dy, direction[1], bounds);
                if (next == null || !standing.computeIfAbsent(next, terrain::canStand)) continue;

                // The body first rises at the current cell, or crosses above a lower landing.
                // Endpoint clearance alone misses a low ceiling along those swept trajectories.
                Cell overhead = dy > 0 ? overhead(current.cell) : dy < 0 ? overhead(next) : null;
                if (dy != 0 && (overhead == null || !clearance.computeIfAbsent(overhead, terrain::isClear))) continue;

                if (!terrain.canTraverse(current.cell, next)) continue;

                long nextCost = current.g + (dy == 0 ? FLAT_COST : STEP_COST);
                if (nextCost >= bestCost.getOrDefault(next, Long.MAX_VALUE)) continue;
                bestCost.put(next, nextCost);
                parent.put(next, current.cell);
                open.add(new Entry(next, nextCost, heuristic(next, goals), order++));
            }
        }
        return failure(Status.UNREACHABLE, expanded);
    }

    /** A horizontal step may also change height, so adding Manhattan Y would overestimate cost. */
    private static long heuristic(Cell from, Set<Cell> goals) {
        long minimum = Long.MAX_VALUE;
        for (Cell goal : goals) {
            long horizontal = Math.abs((long) from.x - goal.x) + Math.abs((long) from.z - goal.z);
            long vertical = Math.abs((long) from.y - goal.y);
            minimum = Math.min(minimum, FLAT_COST * Math.max(horizontal, vertical)
                + (STEP_COST - FLAT_COST) * vertical);
        }
        return minimum;
    }

    // Bounds restrict foot positions. Headroom queries may legitimately lie above those bounds.
    private static Cell overhead(Cell feet) {
        return feet.y > Integer.MAX_VALUE - 2 ? null : new Cell(feet.x, feet.y + 2, feet.z);
    }
    private static Cell offsetWithin(Cell from, int dx, int dy, int dz, Bounds bounds) {
        long x = (long) from.x + dx, y = (long) from.y + dy, z = (long) from.z + dz;
        if (x < bounds.minX || x > bounds.maxX || y < bounds.minY || y > bounds.maxY
            || z < bounds.minZ || z > bounds.maxZ) return null;
        return new Cell((int) x, (int) y, (int) z);
    }
    private static Result reached(Cell start, Cell goal, Map<Cell, Cell> parent, int expanded, long cost) {
        List<Cell> path = new ArrayList<>();
        for (Cell cell = goal; !cell.equals(start); cell = parent.get(cell)) path.add(cell);
        Collections.reverse(path);
        return new Result(path, Optional.of(goal), expanded, cost, Status.REACHED);
    }
    private static Result failure(Status status, int expanded) {
        return new Result(List.of(), Optional.empty(), expanded, Long.MAX_VALUE, status);
    }
}
