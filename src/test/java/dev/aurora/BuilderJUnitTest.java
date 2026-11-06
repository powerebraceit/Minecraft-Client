package dev.aurora;

import dev.aurora.core.builder.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

public final class BuilderJUnitTest {
    private static BuildPlan.Block block(int y) { return new BuildPlan.Block(0, y, 0, "minecraft:stone", 1, "minecraft:stone"); }
    private static final class Driver implements BuildSession.Driver {
        final Set<BuildPlan.Block> placed = new HashSet<>();
        final List<BuildPlan.Block> requests = new ArrayList<>();
        final Map<String,Integer> inventory = new HashMap<>();
        BuildSession.Stock stock = new BuildSession.Stock(Map.of(), true);
        String world = "world-a";
        boolean restocked;
        public String worldIdentity() { return world; }
        public boolean placed(BuildPlan.Block b) { return placed.contains(b); }
        public Map<String,Integer> inventory() { return inventory; }
        public BuildSession.Stock stock() { return stock; }
        public BuildSession.Restock restock(Map<String,Integer> layer) { restocked = true; return BuildSession.Restock.PENDING; }
        public void place(BuildPlan.Block b) { requests.add(b); }
    }
    @Test void noPlacementUntilEveryMaterialIsAvailable() {
        var driver = new Driver(); driver.inventory.put("minecraft:stone", 1);
        var session = new BuildSession(new BuildPlan(List.of(block(0),block(1))));
        assertFalse(session.start(driver)); session.tick(driver);
        assertEquals(BuildSession.State.MISSING_MATERIALS, session.state());
        assertTrue(driver.requests.isEmpty());
    }
    @Test void unknownChestsCannotSatisfyPreflight() {
        var driver = new Driver(); driver.stock = new BuildSession.Stock(Map.of("minecraft:stone",100),false);
        var session = new BuildSession(new BuildPlan(List.of(block(0))));
        assertFalse(session.start(driver));
    }
    @Test void restockBeforePlacementAndNeverSkipUnconfirmedLayer() {
        var driver = new Driver(); driver.stock = new BuildSession.Stock(Map.of("minecraft:stone",2),true);
        var session = new BuildSession(new BuildPlan(List.of(block(1),block(0))));
        assertTrue(session.start(driver)); session.tick(driver);
        assertTrue(driver.restocked); assertTrue(driver.requests.isEmpty());
        driver.inventory.put("minecraft:stone",2); session.tick(driver);
        assertEquals(List.of(block(0)),driver.requests);
        session.tick(driver); assertEquals(1,driver.requests.size());
        driver.placed.add(block(0)); session.tick(driver);
        assertEquals(List.of(block(0),block(1)),driver.requests);
        driver.placed.add(block(1)); session.tick(driver);
        assertEquals(BuildSession.State.COMPLETE,session.state());
    }
    @Test void timeoutAndWorldChangePauseInsteadOfContinuing() {
        var driver = new Driver(); driver.inventory.put("minecraft:stone",2);
        var session = new BuildSession(new BuildPlan(List.of(block(0))));
        assertTrue(session.start(driver)); session.tick(driver);
        for (int i=0;i<100;i++) session.tick(driver);
        assertEquals(BuildSession.State.PAUSED,session.state());
        assertTrue(session.start(driver)); driver.world="world-b";session.tick(driver);
        assertEquals(BuildSession.State.PAUSED,session.state());
    }
    @Test void existingBlocksRequireNoMoreMaterialsAndOverlapIsRejected() {
        var driver = new Driver(); driver.placed.add(block(0));
        var session = new BuildSession(new BuildPlan(List.of(block(0))));
        assertTrue(session.start(driver));assertEquals(BuildSession.State.COMPLETE,session.state());
        assertThrows(IllegalArgumentException.class,()->new BuildPlan(List.of(block(0),block(0))));
    }
}
