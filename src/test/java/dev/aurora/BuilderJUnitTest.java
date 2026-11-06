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
        boolean ready=true,busy;
        int restockRequests;
        BuildSession.Restock restockResult=BuildSession.Restock.PENDING;
        Runnable restockAction=()->{};
        java.util.function.Function<BuildPlan.Block,String> blocker=block->"";
        java.util.function.ToDoubleFunction<BuildPlan.Block> priority=block->0;
        final Map<BuildPlan.Block,String> preparationErrors=new HashMap<>();
        final List<BuildPlan.Block> preparations=new ArrayList<>();
        final List<Map<String,Integer>> restockRequirements=new ArrayList<>();
        public boolean preparePlacement(BuildPlan.Block block) {
            preparations.add(block);
            if(preparationErrors.containsKey(block))throw new IllegalStateException(preparationErrors.get(block));
            return ready;
        }
        public String placementBlocker(BuildPlan.Block block) {return blocker.apply(block);}
        public double placementPriority(BuildPlan.Block block) {return priority.applyAsDouble(block);}
        public boolean busyRestocking() {return busy;}
        public String worldIdentity() { return world; }
        public boolean placed(BuildPlan.Block b) { return placed.contains(b); }
        public Map<String,Integer> inventory() { return inventory; }
        public BuildSession.Stock stock() { return stock; }
        public BuildSession.Restock restock(Map<String,Integer> layer) {
            restocked=true;restockRequests++;restockRequirements.add(Map.copyOf(layer));restockAction.run();return restockResult;
        }
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
    @Test void movementDoesNotConsumeTheServerAcknowledgementTimeout() {
        var driver=new Driver();driver.inventory.put("minecraft:stone",1);driver.ready=false;
        var session=new BuildSession(new BuildPlan(List.of(block(0))));
        assertTrue(session.start(driver));
        for(int i=0;i<150;i++)session.tick(driver);
        assertEquals(BuildSession.State.BUILDING,session.state());assertTrue(driver.requests.isEmpty());
        driver.ready=true;session.tick(driver);assertEquals(1,driver.requests.size());
    }
    @Test void fullPlayerInventoryDoesNotRequireChestInspection() {
        var driver=new Driver();driver.inventory.put("minecraft:stone",1);
        driver.stock=new BuildSession.Stock(Map.of(),false);
        assertTrue(new BuildSession(new BuildPlan(List.of(block(0)))).start(driver));
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

    @Test void usesEligibleInventoryMaterialBeforeFetchingSortedFirstMissingMaterial() {
        var stone=new BuildPlan.Block(0,0,0,"minecraft:stone",1,"stone");
        var glass=new BuildPlan.Block(1,0,0,"minecraft:glass",1,"glass");
        var driver=new Driver();driver.inventory.put("minecraft:glass",1);driver.stock=new BuildSession.Stock(Map.of("minecraft:stone",1),true);
        var session=new BuildSession(new BuildPlan(List.of(stone,glass)));assertTrue(session.start(driver));
        session.tick(driver);assertEquals(List.of(glass),driver.requests);assertEquals(0,driver.restockRequests);
        session.tick(driver);assertEquals(List.of(glass),driver.requests);assertEquals(0,driver.restockRequests);
        driver.placed.add(glass);driver.inventory.clear();session.tick(driver);
        assertEquals(1,driver.restockRequests);assertEquals(List.of(Map.of("minecraft:stone",1)),driver.restockRequirements);
    }
    @Test void deferredPlacementRetriesAfterAnotherBlockInTheSameLayerIsConfirmed() {
        var unsupported=new BuildPlan.Block(0,0,0,"minecraft:stone",1,"needs side support");
        var support=new BuildPlan.Block(1,0,0,"minecraft:stone",1,"support");
        var driver=new Driver();driver.inventory.put("minecraft:stone",2);driver.preparationErrors.put(unsupported,"Missing side support");
        var session=new BuildSession(new BuildPlan(List.of(unsupported,support)));assertTrue(session.start(driver));
        session.tick(driver);assertTrue(driver.requests.isEmpty());assertEquals(BuildSession.State.BUILDING,session.state());
        session.tick(driver);assertEquals(List.of(support),driver.requests);
        session.tick(driver);assertEquals(List.of(unsupported,support),driver.preparations);
        driver.placed.add(support);driver.preparationErrors.remove(unsupported);session.tick(driver);
        assertEquals(List.of(support,unsupported),driver.requests);
        assertEquals(List.of(unsupported,support,unsupported),driver.preparations);
    }
    @Test void unconfirmedLowerLayerForbidsAnyHigherLayerPlacement() {
        var lower=block(0);var higher=block(1);var driver=new Driver();driver.inventory.put("minecraft:stone",2);
        var session=new BuildSession(new BuildPlan(List.of(higher,lower)));assertTrue(session.start(driver));session.tick(driver);
        for(int tick=0;tick<80;tick++)session.tick(driver);
        assertEquals(List.of(lower),driver.requests);assertEquals(BuildSession.State.BUILDING,session.state());
        driver.placed.add(lower);session.tick(driver);assertEquals(List.of(lower,higher),driver.requests);
    }
    @Test void recipeBlockerPreventsPlantingUntilThePotIsServerConfirmed() {
        var pot=new BuildPlan.Block(0,0,0,"minecraft:flower_pot",1,"empty pot",0,0);
        var mushroom=new BuildPlan.Block(0,0,0,"minecraft:red_mushroom",1,"potted mushroom",1,0);
        var higher=new BuildPlan.Block(0,1,0,"minecraft:stone",1,"higher layer");
        var driver=new Driver();driver.inventory.put("minecraft:red_mushroom",1);driver.inventory.put("minecraft:stone",1);
        driver.stock=new BuildSession.Stock(Map.of("minecraft:flower_pot",1),true);
        driver.blocker=action->action.equals(mushroom) && !driver.placed.contains(pot)?"Place pot first":"";
        var session=new BuildSession(new BuildPlan(List.of(pot,mushroom,higher)));assertTrue(session.start(driver));session.tick(driver);
        assertTrue(driver.requests.isEmpty());assertEquals(List.of(Map.of("minecraft:flower_pot",1)),driver.restockRequirements);
        driver.inventory.put("minecraft:flower_pot",1);session.tick(driver);assertEquals(List.of(pot),driver.requests);
        session.tick(driver);assertEquals(List.of(pot),driver.requests);
        driver.placed.add(pot);driver.inventory.remove("minecraft:flower_pot");session.tick(driver);
        assertEquals(List.of(pot,mushroom),driver.requests);
        session.tick(driver);assertEquals(List.of(pot,mushroom),driver.requests);
        driver.placed.add(mushroom);driver.inventory.remove("minecraft:red_mushroom");session.tick(driver);
        assertEquals(List.of(pot,mushroom,higher),driver.requests);
    }
    @Test void partialRestockCompleteResumesBuildingWithoutDemandingTheWholeLayer() {
        var actions=List.of(new BuildPlan.Block(0,0,0,"minecraft:stone",1,"stone"),new BuildPlan.Block(1,0,0,"minecraft:stone",1,"stone"),new BuildPlan.Block(2,0,0,"minecraft:stone",1,"stone"));
        var driver=new Driver();driver.stock=new BuildSession.Stock(Map.of("minecraft:stone",3),true);
        driver.restockResult=BuildSession.Restock.COMPLETE;driver.restockAction=()->driver.inventory.put("minecraft:stone",1);
        var session=new BuildSession(new BuildPlan(actions));assertTrue(session.start(driver));
        for(int index=0;index<actions.size();index++) {
            session.tick(driver);assertEquals(index+1,driver.restockRequests); // fetch only one usable item
            session.tick(driver);assertEquals(actions.subList(0,index+1),driver.requests);
            driver.placed.add(actions.get(index));driver.inventory.clear();
        }
        session.tick(driver);assertEquals(BuildSession.State.COMPLETE,session.state());assertEquals(3,driver.restockRequests);
        assertEquals(List.of(Map.of("minecraft:stone",3),Map.of("minecraft:stone",2),Map.of("minecraft:stone",1)),driver.restockRequirements);
    }
    @Test void fullInventoryWithoutUsableMaterialPausesWithActionableReason() {
        var driver=new Driver();driver.inventory.put("minecraft:cobblestone",36*64);driver.stock=new BuildSession.Stock(Map.of("minecraft:stone",1),true);
        driver.restockResult=BuildSession.Restock.BLOCKED;
        var session=new BuildSession(new BuildPlan(List.of(block(0))));assertTrue(session.start(driver));session.tick(driver);
        assertEquals(BuildSession.State.PAUSED,session.state());assertTrue(session.status().contains("free an inventory slot"));assertTrue(driver.requests.isEmpty());
        for(int tick=0;tick<5;tick++)session.tick(driver);assertEquals(1,driver.restockRequests);
    }
    @Test void inventoryPredictionDoesNotStartPlacementBeforeRestockSynchronizationFinishes() {
        var driver=new Driver();driver.inventory.put("minecraft:stone",1);driver.busy=true;
        var session=new BuildSession(new BuildPlan(List.of(block(0))));assertTrue(session.start(driver));session.tick(driver);
        assertEquals(BuildSession.State.RESTOCKING,session.state());assertTrue(driver.requests.isEmpty());
        driver.busy=false;session.tick(driver);assertEquals(List.of(block(0)),driver.requests);
    }
    @Test void choosesTheNearestEligibleInventoryActionWithinTheCurrentLayer() {
        var sortedFirst=new BuildPlan.Block(0,0,0,"minecraft:stone",1,"far stone");
        var nearby=new BuildPlan.Block(8,0,0,"minecraft:stone",1,"near stone");
        var driver=new Driver();driver.inventory.put("minecraft:stone",2);
        driver.priority=action->action.equals(nearby)?1:64;
        var session=new BuildSession(new BuildPlan(List.of(sortedFirst,nearby)));
        assertTrue(session.start(driver));session.tick(driver);
        assertEquals(List.of(nearby),driver.requests);
        assertEquals(List.of(nearby),driver.preparations);
        assertEquals(0,driver.restockRequests);
    }
    @Test void keepsTheChosenWalkingTargetWhenDistancesChangeUntilItsPlacementIsConfirmed() {
        var original=new BuildPlan.Block(0,0,0,"minecraft:stone",1,"original target");
        var alternative=new BuildPlan.Block(8,0,0,"minecraft:stone",1,"other target");
        var driver=new Driver();driver.inventory.put("minecraft:stone",2);driver.ready=false;
        driver.priority=action->action.equals(original)?1:64;
        var session=new BuildSession(new BuildPlan(List.of(original,alternative)));
        assertTrue(session.start(driver));session.tick(driver);
        driver.priority=action->action.equals(alternative)?0:100;
        for(int tick=0;tick<8;tick++)session.tick(driver);
        assertTrue(driver.requests.isEmpty());
        assertEquals(Collections.nCopies(9,original),driver.preparations);
        driver.ready=true;session.tick(driver);
        assertEquals(List.of(original),driver.requests);
        session.tick(driver);assertEquals(List.of(original),driver.requests);
        driver.placed.add(original);driver.inventory.put("minecraft:stone",1);session.tick(driver);
        assertEquals(List.of(original,alternative),driver.requests);
    }
    @Test void nearerUpperLayerNeverOverridesAnUnfinishedLowerLayer() {
        var lower=block(0);var upper=block(1);
        var driver=new Driver();driver.inventory.put("minecraft:stone",2);driver.ready=false;
        driver.priority=action->action.equals(upper)?0:10000;
        var session=new BuildSession(new BuildPlan(List.of(upper,lower)));
        assertTrue(session.start(driver));session.tick(driver);session.tick(driver);
        assertEquals(List.of(lower,lower),driver.preparations);
        driver.ready=true;session.tick(driver);session.tick(driver);
        assertEquals(List.of(lower),driver.requests);
        driver.placed.add(lower);session.tick(driver);
        assertEquals(List.of(lower,upper),driver.requests);
    }

}
