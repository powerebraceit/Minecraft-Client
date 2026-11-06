package dev.aurora;

import dev.aurora.core.builder.PlacementReach;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlacementReachJUnitTest {
    @Test void vanillaSurfaceAtExactlyFourAndAHalfBlocksIsReachable() {
        assertEquals(4.5,PlacementReach.limit(4.5));
        assertTrue(PlacementReach.withinSquared(4.5,4.5*4.5));
        assertTrue(PlacementReach.withinSquared(4.5,4.4999*4.4999));
    }
    @Test void surfaceJustOutsideVanillaReachIsRejected() {
        assertFalse(PlacementReach.withinSquared(4.5,4.5001*4.5001));
    }
    @Test void increasedAttributesNeverGrantExtendedBuildingReach() {
        assertEquals(4.5,PlacementReach.limit(8));
        assertTrue(PlacementReach.withinSquared(8,4.5*4.5));
        assertFalse(PlacementReach.withinSquared(8,5*5));
        assertFalse(PlacementReach.withinSquared(8,8*8));
    }
    @Test void reducedAttributeRangeIsRespected() {
        assertEquals(3,PlacementReach.limit(3));
        assertTrue(PlacementReach.withinSquared(3,9));
        assertFalse(PlacementReach.withinSquared(3,3.0001*3.0001));
    }
    @Test void diagonalAndVerticalDistanceUseTheFullThreeDimensionalLength() {
        assertTrue(PlacementReach.withinSquared(4.5,3*3+3*3));
        assertFalse(PlacementReach.withinSquared(4.5,3.2*3.2+3.2*3.2));
        assertTrue(PlacementReach.withinSquared(4.5,4.5*4.5));
        assertFalse(PlacementReach.withinSquared(4.5,4*4+3*3));
    }
    @Test void invalidDistancesAndAttributesFailClosed() {
        assertFalse(PlacementReach.withinSquared(4.5,Double.NaN));
        assertFalse(PlacementReach.withinSquared(4.5,Double.POSITIVE_INFINITY));
        assertFalse(PlacementReach.withinSquared(4.5,-1));
        assertFalse(PlacementReach.withinSquared(Double.NaN,1));
        assertFalse(PlacementReach.withinSquared(-1,1));
        assertEquals(4.5,PlacementReach.limit(Double.POSITIVE_INFINITY));
    }
}
