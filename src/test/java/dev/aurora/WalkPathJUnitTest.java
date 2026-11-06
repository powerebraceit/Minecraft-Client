package dev.aurora;
import dev.aurora.core.builder.WalkPath;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;
public final class WalkPathJUnitTest {
    @Test void routesAroundObstacleOnLoadedFloor() {
        var path=WalkPath.find(new WalkPath.Point(0,0,0),p->p.y()==0 && !(p.x()==1 && p.z()==0),p->true,
            p->p.equals(new WalkPath.Point(2,0,0)),100);
        assertEquals(new WalkPath.Point(2,0,0),path.getLast());
        assertFalse(path.contains(new WalkPath.Point(1,0,0)));
    }
    @Test void oneBlockStepRequiresHeadClearance() {
        var start=new WalkPath.Point(0,0,0);var target=new WalkPath.Point(1,1,0);
        assertThrows(IllegalStateException.class,()->WalkPath.find(start,target::equals,p->false,target::equals,30));
        assertEquals(java.util.List.of(target),WalkPath.find(start,target::equals,p->true,target::equals,30));
    }
    @Test void unreachableGoalTerminates() {
        assertThrows(IllegalStateException.class,()->WalkPath.find(new WalkPath.Point(0,0,0),p->false,p->true,p->p.x()==2,10));
    }
    @Test void equallyShortRoutesPreferTheDirectionOfTheBuildTarget() {
        var start=new WalkPath.Point(0,0,0);var target=new WalkPath.Point(-8,0,0);
        var path=WalkPath.findToward(start,p->p.y()==0,p->true,p->Math.abs(p.x())+Math.abs(p.z())==1,100,target,4);
        assertEquals(java.util.List.of(new WalkPath.Point(-1,0,0)),path);
    }
    @Test void directedSearchKeepsShortestDetourAroundAnObstacle() {
        var start=new WalkPath.Point(0,0,0);var target=new WalkPath.Point(2,0,0);
        var path=WalkPath.findToward(start,p->p.y()==0 && !(p.x()==1 && p.z()==0),p->true,target::equals,100,target,2);
        assertEquals(4,path.size());assertEquals(target,path.getLast());
    }
    @Test void placementSearchDoesNotWanderFarOutsideTheTargetCorridor() {
        var start=new WalkPath.Point(0,0,0);var target=new WalkPath.Point(2,0,0);
        assertThrows(IllegalStateException.class,()->WalkPath.findToward(start,p->p.y()==0,p->true,p->p.z()==5,1000,target,2));
    }
}
