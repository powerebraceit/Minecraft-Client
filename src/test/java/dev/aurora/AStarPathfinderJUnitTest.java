package dev.aurora;

import dev.aurora.core.builder.AStarPathfinder;
import dev.aurora.core.builder.AStarPathfinder.*;
import java.util.*;
import java.util.function.Predicate;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

/** Tests the pure planning engine without starting Minecraft or depending on loader mappings. */
public final class AStarPathfinderJUnitTest {
    private static final Cell START = new Cell(0, 0, 0);
    private static final Bounds FLAT = new Bounds(-8, 0, -8, 8, 0, 8);
    private static Terrain terrain(Predicate<Cell> safe, Predicate<Cell> clear) {
        return new Terrain() {
            @Override public boolean canStand(Cell cell) { return safe.test(cell); }
            @Override public boolean isClear(Cell cell) { return clear.test(cell); }
        };
    }
    private static Terrain flat(Set<Cell> blocked) {
        return terrain(cell -> cell.y() == 0 && !blocked.contains(cell), cell -> true);
    }
    private static void noExecutablePath(Result result, Status expected) {
        assertEquals(expected, result.status());
        assertFalse(result.reached());
        assertTrue(result.path().isEmpty());
        assertTrue(result.reachedGoal().isEmpty());
        assertEquals(Long.MAX_VALUE, result.cost());
    }

    @Test void routesAroundAlreadyPlacedBlocksAtOptimalCost() {
        Cell goal = new Cell(3, 0, 0);
        Set<Cell> placed = Set.of(new Cell(1, 0, 0), new Cell(2, 0, 0));
        Result result = AStarPathfinder.search(START, List.of(goal), flat(placed), FLAT, 100);
        assertTrue(result.reached());
        assertEquals(goal, result.path().getLast());
        assertEquals(50, result.cost());
        assertEquals(5, result.path().size());
        assertTrue(Collections.disjoint(placed, result.path()));
    }

    @Test void choosesReachableStandingGoalWhenClosestCandidateIsEnclosed() {
        Cell enclosed = new Cell(2, 0, 0), reachable = new Cell(0, 0, 4);
        Set<Cell> walls = Set.of(new Cell(1, 0, 0), new Cell(3, 0, 0), new Cell(2, 0, 1), new Cell(2, 0, -1));
        Result result = AStarPathfinder.search(START, List.of(enclosed, reachable), flat(walls), FLAT, 200);
        assertEquals(Optional.of(reachable), result.reachedGoal());
        assertEquals(40, result.cost());
    }

    @Test void neverUsesAirOrHazardCellsSuppliedByTheWorldAdapter() {
        Set<Cell> hazards = Set.of(new Cell(1, 0, 0), new Cell(1, 0, 1), new Cell(1, 0, -1));
        Result result = AStarPathfinder.search(START, List.of(new Cell(2, 0, 0)), flat(hazards), FLAT, 200);
        assertTrue(result.reached());
        assertTrue(result.path().stream().allMatch(cell -> cell.y() == 0 && !hazards.contains(cell)));
        assertEquals(60, result.cost());
    }

    @Test void risingRequiresClearanceAboveTheCurrentHead() {
        Cell goal = new Cell(1, 1, 0);
        Bounds bounds = new Bounds(0, 0, 0, 1, 1, 0);
        Terrain blocked = terrain(cell -> cell.equals(START) || cell.equals(goal), cell -> !cell.equals(new Cell(0, 2, 0)));
        noExecutablePath(AStarPathfinder.search(START, List.of(goal), blocked, bounds, 20), Status.UNREACHABLE);
        Result result = AStarPathfinder.search(START, List.of(goal), terrain(cell -> cell.equals(START) || cell.equals(goal), cell -> true), bounds, 20);
        assertEquals(List.of(goal), result.path());
        assertEquals(14, result.cost());
    }

    @Test void descendingRequiresHeadroomWhileCrossingAboveTheLanding() {
        Cell start = new Cell(0, 1, 0), goal = new Cell(1, 0, 0);
        Bounds bounds = new Bounds(0, 0, 0, 1, 1, 0);
        Terrain blocked = terrain(cell -> cell.equals(start) || cell.equals(goal), cell -> !cell.equals(new Cell(1, 2, 0)));
        noExecutablePath(AStarPathfinder.search(start, List.of(goal), blocked, bounds, 20), Status.UNREACHABLE);
        Result result = AStarPathfinder.search(start, List.of(goal), terrain(cell -> cell.equals(start) || cell.equals(goal), cell -> true), bounds, 20);
        assertEquals(List.of(goal), result.path());
        assertEquals(14, result.cost());
    }

    @Test void unreachableOrBudgetLimitedSearchNeverReturnsAnExecutablePartialRoute() {
        Bounds bounds = new Bounds(0, 0, 0, 4, 0, 4);
        Cell goal = new Cell(4, 0, 4);
        Terrain floor = flat(Set.of());
        noExecutablePath(AStarPathfinder.search(START, List.of(goal), floor, bounds, 1), Status.NODE_BUDGET_EXHAUSTED);
        Set<Cell> barrier = Set.of(new Cell(1, 0, 0), new Cell(0, 0, 1));
        noExecutablePath(AStarPathfinder.search(START, List.of(goal), flat(barrier), bounds, 100), Status.UNREACHABLE);
    }

    @Test void invalidStandingCellsAndGoalsAreRejected() {
        Cell goal = new Cell(2, 0, 0);
        noExecutablePath(AStarPathfinder.search(START, List.of(goal), flat(Set.of(START)), FLAT, 20), Status.INVALID_START);
        noExecutablePath(AStarPathfinder.search(START, List.of(goal), flat(Set.of(goal)), FLAT, 20), Status.NO_VALID_GOALS);
        noExecutablePath(AStarPathfinder.search(START, List.of(new Cell(100, 0, 0)), flat(Set.of()), FLAT, 20), Status.NO_VALID_GOALS);
    }

    @Test void anAlreadyReachedStandingGoalNeedsNoMovement() {
        Result result = AStarPathfinder.search(START, List.of(START), flat(Set.of()), FLAT, 1);
        assertTrue(result.reached());
        assertEquals(Optional.of(START), result.reachedGoal());
        assertTrue(result.path().isEmpty());
        assertEquals(0, result.cost());
        assertEquals(0, result.expanded());
    }

    @Test void repeatedAndReorderedGoalSetsProduceTheSameRoute() {
        Cell first = new Cell(2, 0, 2), second = new Cell(-2, 0, -2);
        Terrain floor = flat(Set.of());
        Result expected = AStarPathfinder.search(START, List.of(first, second), floor, FLAT, 200);
        for (int run = 0; run < 10; run++) {
            Result actual = AStarPathfinder.search(START, List.of(second, first, second), floor, FLAT, 200);
            assertEquals(expected.path(), actual.path());
            assertEquals(expected.cost(), actual.cost());
        }
    }

    @Test void aTemporarilyStalledDirectedEdgeCanBeAvoidedWithoutBlockingTheCell() {
        Cell goal = new Cell(2, 0, 0), avoid = new Cell(1, 0, 0);
        Terrain floor = new Terrain() {
            @Override public boolean canStand(Cell cell) { return cell.y() == 0; }
            @Override public boolean isClear(Cell cell) { return true; }
            @Override public boolean canTraverse(Cell from, Cell to) { return !(from.equals(START) && to.equals(avoid)); }
        };
        Result result = AStarPathfinder.search(START, List.of(goal), floor, FLAT, 100);
        assertTrue(result.reached());
        assertEquals(40, result.cost());
        assertNotEquals(avoid, result.path().getFirst());
    }

    @Test void aStarMatchesIndependentDijkstraCostsAcrossDeterministicMazes() {
        Bounds bounds = new Bounds(0, 0, 0, 7, 0, 7);
        Cell goal = new Cell(7, 0, 7);
        for (int seed = 0; seed < 40; seed++) {
            Random random = new Random(seed);
            Set<Cell> walls = new HashSet<>();
            for (int x = 0; x <= 7; x++) for (int z = 0; z <= 7; z++) {
                Cell cell = new Cell(x, 0, z);
                if (!cell.equals(START) && !cell.equals(goal) && random.nextDouble() < 0.28) walls.add(cell);
            }
            long reference = dijkstra(START, goal, walls, bounds);
            Result result = AStarPathfinder.search(START, List.of(goal), flat(walls), bounds, 200);
            assertEquals(reference, result.cost(), "maze seed " + seed);
            assertEquals(reference != Long.MAX_VALUE, result.reached(), "maze seed " + seed);
            assertTrue(result.expanded() <= 200);
        }
    }

    private static long dijkstra(Cell start, Cell goal, Set<Cell> walls, Bounds bounds) {
        record Visit(Cell cell, long cost) { }
        PriorityQueue<Visit> queue = new PriorityQueue<>(Comparator.comparingLong(Visit::cost));
        Map<Cell, Long> costs = new HashMap<>();
        costs.put(start, 0L);queue.add(new Visit(start, 0));
        while (!queue.isEmpty()) {
            Visit current = queue.remove();
            if (current.cost() != costs.get(current.cell())) continue;
            if (current.cell().equals(goal)) return current.cost();
            for (int[] direction : new int[][]{{1, 0}, {-1, 0}, {0, 1}, {0, -1}}) {
                Cell next = current.cell().offset(direction[0], 0, direction[1]);
                long nextCost = current.cost() + 10;
                if (bounds.contains(next) && !walls.contains(next) && nextCost < costs.getOrDefault(next, Long.MAX_VALUE)) {
                    costs.put(next, nextCost);queue.add(new Visit(next, nextCost));
                }
            }
        }
        return Long.MAX_VALUE;
    }
}
