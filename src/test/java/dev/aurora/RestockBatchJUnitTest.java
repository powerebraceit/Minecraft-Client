package dev.aurora;

import dev.aurora.core.builder.RestockBatch;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RestockBatchJUnitTest {
    private static RestockBatch.Source source(int slot,String item,int amount,int destination,int capacity) {
        return new RestockBatch.Source(slot,item,amount,List.of(new RestockBatch.Space(destination,capacity)));
    }
    @Test void nextActionReservesTheOnlyEmptySlotBeforeOtherLayerMaterials() {
        var sources=List.of(source(0,"dirt",64,27,64),source(1,"stone",64,27,64));
        var transfer=RestockBatch.choose(sources,Map.of("stone",70,"dirt",2),Map.of(),"stone",1).orElseThrow();
        assertEquals("stone",transfer.item());assertEquals(64,transfer.count());
    }
    @Test void layerLargerThanInventoryCompletesThroughSeveralBoundedTrips() {
        var available=new LinkedHashMap<>(Map.of("stone",70,"glass",2));
        var remaining=new HashMap<>(available);Map<String,Integer> own=new HashMap<>();int trips=0,totalTransferred=0;
        while(!remaining.isEmpty()) {
            String next=remaining.containsKey("stone")?"stone":"glass";
            var sources=new ArrayList<RestockBatch.Source>();int slot=0;
            for(var entry:available.entrySet())if(entry.getValue()>0)sources.add(source(slot++,entry.getKey(),Math.min(64,entry.getValue()),27,64));
            var transfer=RestockBatch.choose(sources,remaining,own,next,1).orElseThrow();
            own.put(transfer.item(),transfer.count());available.merge(transfer.item(),-transfer.count(),Integer::sum);
            assertTrue(own.size()<=1,"Only one free inventory slot is available");
            remaining.compute(transfer.item(),(item,count)->count==transfer.count()?null:count-transfer.count());
            totalTransferred+=transfer.count();trips++;own.clear(); // survival placement consumes this batch before returning
        }
        assertEquals(3,trips);assertEquals(72,totalTransferred);assertTrue(available.values().stream().allMatch(n->n==0));
    }
    @Test void takesOnlyTheShortfallAndLeavesTheRestInTheChest() {
        var transfer=RestockBatch.choose(List.of(source(0,"stone",64,27,64)),Map.of("stone",9),Map.of("stone",6),"stone",1).orElseThrow();
        assertEquals(3,transfer.count());
        assertEquals(61,64-transfer.count());
    }
    @Test void usesPartialCompatibleStackEvenWhenEverySlotIsOccupied() {
        var source=new RestockBatch.Source(0,"stone",64,List.of(new RestockBatch.Space(28,4)));
        var transfer=RestockBatch.choose(List.of(source),Map.of("stone",70),Map.of("stone",60),"stone",1).orElseThrow();
        assertEquals(28,transfer.destination());assertEquals(4,transfer.count());
    }
    @Test void cannotOverfillUnstackableBucketsOrZeroCapacitySlots() {
        var sources=List.of(source(0,"lava_bucket",1,27,1),source(1,"stone",64,28,0));
        var transfer=RestockBatch.choose(sources,Map.of("lava_bucket",8,"stone",10),Map.of(),"lava_bucket",1).orElseThrow();
        assertEquals(1,transfer.count());assertEquals("lava_bucket",transfer.item());
        assertTrue(RestockBatch.choose(List.of(sources.get(1)),Map.of("stone",10),Map.of(),"stone",1).isEmpty());
    }
    @Test void neverFillsTheLastSlotWithAnUnrelatedItemWhileTheNextActionIsMissing() {
        var sources=List.of(source(0,"glass",64,27,64));
        assertTrue(RestockBatch.choose(sources,Map.of("stone",70,"glass",2),Map.of(),"stone",1).isEmpty());
        assertFalse(RestockBatch.minimumSatisfied(Map.of(),"stone",1));
        assertTrue(RestockBatch.minimumSatisfied(Map.of("stone",1),"stone",1));
    }
}
