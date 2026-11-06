import dev.aurora.core.builder.AStarPathfinder;
import dev.aurora.core.builder.AStarPathfinder.*;
import dev.aurora.core.builder.BuildPlan;
import dev.aurora.core.builder.BuildSession;
import java.util.*;

public final class AlgorithmExample {
    public static void main(String[] args) {
        Set<Cell> obstacles = new HashSet<>();
        for (int z = -1; z <= 1; z++) {
            obstacles.add(new Cell(2, 0, z));
            obstacles.add(new Cell(2, 1, z));
        }
        Terrain terrain = new Terrain() {
            // Detta exempel har ett säkert, plant golv under y=0.
            public boolean canStand(Cell feet) {
                return feet.y() == 0 && !obstacles.contains(feet);
            }
            public boolean isClear(Cell cell) {
                return cell.y() >= 0 && !obstacles.contains(cell);
            }
        };
        Result route = AStarPathfinder.search(
            new Cell(0, 0, 0),
            List.of(new Cell(3, 0, -2), new Cell(3, 0, 2)),
            terrain, new Bounds(-2, -1, -3, 6, 2, 3), 512);
        if (!route.reached()) throw new IllegalStateException(route.status().name());
        System.out.println("Ståplats: " + route.reachedGoal().orElseThrow());

        var action = new BuildPlan.Block(4, 0, 0, "minecraft:stone", 1, "minecraft:stone");
        Set<BuildPlan.Block> serverConfirmed = new HashSet<>();
        BuildSession.Driver adapter = new BuildSession.Driver() {
            public String worldIdentity() { return "example-world"; }
            public boolean placed(BuildPlan.Block block) { return serverConfirmed.contains(block); }
            public Map<String, Integer> inventory() { return Map.of("minecraft:stone", 1); }
            public BuildSession.Stock stock() { return new BuildSession.Stock(Map.of(), true); }
            public BuildSession.Restock restock(Map<String, Integer> wanted) {
                return BuildSession.Restock.BLOCKED;
            }
            public boolean preparePlacement(BuildPlan.Block block) {
                // Spelets adapter går till ståplatsen och validerar yta/rotation här.
                return route.reached();
            }
            public void place(BuildPlan.Block block) {
                System.out.println("Skicka ett validerat klick; vänta sedan på servern.");
            }
        };
        BuildSession queue = new BuildSession(new BuildPlan(List.of(action)), 100);
        queue.start(adapter);
        queue.tick(adapter);
        if (queue.phase() != BuildSession.Phase.WAITING_ACK) throw new AssertionError();
        // Endast som demonstration: en riktig adapter använder serverns blockpaket.
        serverConfirmed.add(action);
        queue.tick(adapter);
        if (queue.state() != BuildSession.State.COMPLETE) throw new AssertionError();
    }
}
