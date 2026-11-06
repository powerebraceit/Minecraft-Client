package dev.aurora.minecraft.builder;

import dev.aurora.core.builder.AStarPathfinder;
import dev.aurora.core.builder.AStarPathfinder.Cell;
import dev.aurora.core.builder.PlacementReach;
import java.util.*;
import java.util.function.Predicate;
import net.minecraft.client.Minecraft;
import net.minecraft.core.BlockPos;
import net.minecraft.world.phys.Vec3;
import net.minecraft.world.phys.AABB;

/**
 * Minecraft movement adapter for the loader-independent A* engine.
 *
 * It plans to safe standing positions, never to the solid block being built. A placement goal
 * additionally checks real reach and line of sight through the predicate supplied by the builder.
 * Movement is ordinary forward/jump input; this class never sets velocity or teleports the player.
 */
public final class WalkNavigator {
    private static final int MAX_REPLANS=6;
    private static final int STALLED_TICKS=35;
    private static final int MAX_EXPANDED=8192;
    private record Edge(Cell from,Cell to) { }

    private BlockPos target;
    private List<Cell> path=List.of();
    private final Set<Edge> stalledEdges=new HashSet<>();
    private final Set<Cell> rejectedGoals=new HashSet<>();
    private int index,replans,stuckTicks,landingTicks;
    private double bestWaypointDistance=Double.POSITIVE_INFINITY;
    private boolean forward,jump,placement,terrainChanged;
    private double goalRange,goalMinimum;
    private float movementYaw;

    public boolean forward() {return forward;}
    public boolean jump() {return jump;}
    public float movementYaw() {return movementYaw;}

    /** Keep placement aiming from redirecting the next physics step's forward input. */
    public void applyMovementLook() {
        var player=Minecraft.getInstance().player;
        if(forward && player!=null)player.setYRot(movementYaw);
    }

    public void stop() {
        target=null;
        path=List.of();
        stalledEdges.clear();
        rejectedGoals.clear();
        index=0;replans=0;stuckTicks=0;landingTicks=0;
        terrainChanged=false;
        bestWaypointDistance=Double.POSITIVE_INFINITY;
        releaseInput();
    }

    /** Block packets invalidate routes locally, including blocks placed by this builder. */
    public void invalidate(BlockPos changed) {
        if(target==null)return;
        if(Math.abs(changed.getX()-target.getX())<=2 && Math.abs(changed.getY()-target.getY())<=2
            && Math.abs(changed.getZ()-target.getZ())<=2)terrainChanged=true;
        for(int i=index;i<path.size() && !terrainChanged;i++) {
            Cell cell=path.get(i);
            if(Math.abs(changed.getX()-cell.x())<=1 && Math.abs(changed.getY()-cell.y())<=2
                && Math.abs(changed.getZ()-cell.z())<=1)terrainChanged=true;
        }
    }

    public boolean walkTo(BlockPos destination,double range) {return walkTo(destination,range,0);}

    public boolean walkTo(BlockPos destination,double range,double minimumRange) {
        if(range<0 || minimumRange<0 || minimumRange>range)throw new IllegalArgumentException("Invalid walking range");
        if(placement || range!=goalRange || minimumRange!=goalMinimum)stop();
        placement=false;goalRange=range;goalMinimum=minimumRange;
        Predicate<Vec3> goal=feet->{
            double distance=feet.distanceTo(Vec3.atBottomCenterOf(destination));
            return distance<=range && distance>=minimumRange;
        };
        return walk(destination,goal,(int)Math.ceil(range)+1);
    }

    /** The predicate must validate an actual placement face within ordinary interaction reach. */
    public boolean walkToPlacement(BlockPos destination,Predicate<Vec3> acceptableFeet) {
        if(!placement)stop();
        placement=true;
        var player=Minecraft.getInstance().player;
        double reach=player==null?PlacementReach.SURVIVAL_MAX:PlacementReach.limit(player.blockInteractionRange());
        return walk(destination,acceptableFeet,(int)Math.ceil(reach)+1);
    }

    private boolean walk(BlockPos destination,Predicate<Vec3> goal,int radius) {
        var mc=Minecraft.getInstance();
        if(mc.player==null || mc.level==null) {stop();return false;}
        if(mc.gui.screen()!=null) {releaseInput();return false;}
        if(target!=null && !target.equals(destination))stop();
        Vec3 actual=mc.player.position();
        boolean safeCurrent=mc.player.onGround() && safeActual(actual);

        // Do not invent an integer-grid detour when a safe real stance (including a slab) already works.
        if(safeCurrent && goal.test(actual)) {stop();return true;}
        var terrain=terrain();
        if(terrainChanged) {
            terrainChanged=false;
            if(!routeStillSafe(terrain,goal))requestReplan(null);
        }
        if(index<path.size() && !terrain.canStand(path.get(index)))requestReplan(null);
        if(path.isEmpty() || index>=path.size()) {
            releaseInput();
            // An airborne start is not a valid standing node. Let an ordinary jump/fall land first.
            if(!safeCurrent) {
                if(++landingTicks>40 || unsafeOccupiedCell())throw new IllegalStateException("Move onto safe solid footing before continuing");
                return false;
            }
            landingTicks=0;
            target=destination.immutable();
            plan(actual,destination,goal,radius,terrain);
        }

        // Consume ordinary intermediate waypoints without inserting idle ticks between them.
        while(index<path.size()-1 && actual.distanceTo(feet(path.get(index)))<0.3) {
            index++;resetProgress();
        }
        Cell next=path.get(index);
        if(!terrain.canStand(next)) {requestReplan(null);return false;}
        Vec3 center=feet(next);
        double distance=actual.distanceTo(center);
        if(index==path.size()-1 && distance<0.08 && safeCurrent) {
            if(goal.test(actual)) {stop();return true;}
            // The cell's center looked valid during planning, but its actual view no longer is.
            rejectedGoals.add(next);requestReplan(null);return false;
        }

        // Progress toward the waypoint matters, not arbitrary movement around it in a circle.
        if(distance+0.025<bestWaypointDistance) {bestWaypointDistance=distance;stuckTicks=0;}
        else if(++stuckTicks>=STALLED_TICKS) {
            requestReplan(new Edge(cell(mc.player.blockPosition()),next));return false;
        }
        Vec3 delta=center.subtract(actual);
        movementYaw=(float)Math.toDegrees(Math.atan2(-delta.x,delta.z));
        mc.player.setYRot(movementYaw);
        forward=true;
        jump=center.y>mc.player.getY()+0.35;
        return false;
    }

    private void plan(Vec3 actual,BlockPos destination,Predicate<Vec3> goal,int radius,AStarPathfinder.Terrain terrain) {
        Cell start=projectStart(actual,terrain);
        if(start==null)throw new IllegalStateException("No safe adjacent standing cell is reachable from the current footing");
        var bounds=bounds(start,destination,radius);
        List<Cell> goals=new ArrayList<>();
        for(int x=destination.getX()-radius;x<=destination.getX()+radius;x++)
            for(int z=destination.getZ()-radius;z<=destination.getZ()+radius;z++)
                for(int y=destination.getY()-radius;y<=destination.getY()+radius;y++) {
                    Cell candidate=new Cell(x,y,z);
                    if(bounds.contains(candidate) && !rejectedGoals.contains(candidate) && terrain.canStand(candidate)
                        && goal.test(feet(candidate)))goals.add(candidate);
                }
        if(goals.isEmpty())throw new IllegalStateException("No safe standing position has the required reach and visible placement face");
        var result=AStarPathfinder.search(start,goals,terrain,bounds,MAX_EXPANDED);
        if(!result.reached())throw new IllegalStateException("No safe route to an in-range standing position: "+result.status());
        var route=new ArrayList<Cell>();
        // A projected start is approached with ordinary input, not skipped or teleported onto.
        if((!start.equals(cell(BlockPos.containing(actual))) || result.path().isEmpty())
            && actual.distanceTo(feet(start))>0.08)route.add(start);
        route.addAll(result.path());
        if(route.isEmpty()) {
            rejectedGoals.add(start);
            throw new IllegalStateException("The current standing position has no usable placement face");
        }
        path=List.copyOf(route);index=0;resetProgress();
    }

    private AStarPathfinder.Bounds bounds(Cell start,BlockPos destination,int radius) {
        // Loaded local terrain only; room for a bounded detour instead of a search across the world.
        int margin=Math.max(4,radius);
        return new AStarPathfinder.Bounds(
            Math.max(start.x()-48,Math.min(start.x(),destination.getX())-margin),
            Math.max(start.y()-16,Math.min(start.y(),destination.getY())-radius-2),
            Math.max(start.z()-48,Math.min(start.z(),destination.getZ())-margin),
            Math.min(start.x()+48,Math.max(start.x(),destination.getX())+margin),
            Math.min(start.y()+16,Math.max(start.y(),destination.getY())+radius+2),
            Math.min(start.z()+48,Math.max(start.z(),destination.getZ())+margin));
    }

    private AStarPathfinder.Terrain terrain() {
        return new AStarPathfinder.Terrain() {
            @Override public boolean canStand(Cell stance) {
                var level=Minecraft.getInstance().level;
                return withinBorder(feet(stance)) && loaded(stance) && loaded(stance.offset(0,1,0)) && loaded(stance.offset(0,-1,0))
                    && TerrainSafety.canStand(level,stance);
            }
            @Override public boolean isClear(Cell cell) {
                return loaded(cell) && TerrainSafety.isClear(Minecraft.getInstance().level,cell);
            }
            @Override public boolean canTraverse(Cell from,Cell to) {return !stalledEdges.contains(new Edge(from,to));}
        };
    }

    private boolean loaded(Cell cell) {
        var level=Minecraft.getInstance().level;
        var pos=new BlockPos(cell.x(),cell.y(),cell.z());
        return !level.isOutsideBuildHeight(pos) && level.hasChunkAt(pos) && level.getWorldBorder().isWithinBounds(pos);
    }

    private boolean routeStillSafe(AStarPathfinder.Terrain terrain,Predicate<Vec3> goal) {
        if(index>=path.size())return true;
        for(int i=index;i<path.size();i++)if(!terrain.canStand(path.get(i)))return false;
        return goal.test(feet(path.getLast()));
    }

    private Cell projectStart(Vec3 actual,AStarPathfinder.Terrain terrain) {
        Cell current=cell(BlockPos.containing(actual));
        if(terrain.canStand(current))return current;
        var candidates=new ArrayList<Cell>();
        for(int[] direction:new int[][]{{0,0},{1,0},{-1,0},{0,1},{0,-1}})
            for(int dy:new int[]{0,1,-1}) {
                Cell candidate=current.offset(direction[0],dy,direction[1]);
                if(terrain.canStand(candidate) && clearTransition(actual,feet(candidate)))candidates.add(candidate);
            }
        return candidates.stream().min(Comparator.comparingDouble(c->actual.distanceToSqr(feet(c)))).orElse(null);
    }

    private boolean clearTransition(Vec3 from,Vec3 to) {
        var mc=Minecraft.getInstance();
        if(Math.abs(to.y-from.y)>1.05 || Math.hypot(to.x-from.x,to.z-from.z)>1.8)return false;
        double travelY=Math.max(from.y,to.y);
        for(int i=0;i<=8;i++) {
            double fraction=i/8.0;
            Vec3 sample=new Vec3(from.x+(to.x-from.x)*fraction,travelY,from.z+(to.z-from.z)*fraction);
            if(!TerrainSafety.bodyClearAt(mc.level,sample,mc.player.getBbWidth(),mc.player.getBbHeight()))return false;
        }
        return true;
    }

    private boolean safeActual(Vec3 feet) {
        var mc=Minecraft.getInstance();
        Cell current=cell(BlockPos.containing(feet));
        return withinBorder(feet) && loaded(current) && loaded(current.offset(0,1,0))
            && TerrainSafety.canStandAt(mc.level,feet,mc.player.getBbWidth(),mc.player.getBbHeight());
    }

    private boolean withinBorder(Vec3 feet) {
        var mc=Minecraft.getInstance();
        double half=mc.player.getBbWidth()/2;
        return mc.level.getWorldBorder().isWithinBounds(new AABB(feet.x-half,feet.y,feet.z-half,
            feet.x+half,feet.y+mc.player.getBbHeight(),feet.z+half));
    }

    private boolean unsafeOccupiedCell() {
        var mc=Minecraft.getInstance();
        var pos=mc.player.blockPosition();
        var feet=mc.level.getBlockState(pos);
        var head=mc.level.getBlockState(pos.above());
        return TerrainSafety.hazardous(feet) || TerrainSafety.hazardous(head)
            || !feet.getFluidState().isEmpty() || !head.getFluidState().isEmpty();
    }

    private void requestReplan(Edge stalled) {
        if(stalled!=null)stalledEdges.add(stalled);
        releaseInput();path=List.of();index=0;resetProgress();
        if(++replans>MAX_REPLANS)throw new IllegalStateException("Route repeatedly blocked; check terrain and placement support");
    }
    private void resetProgress() {stuckTicks=0;bestWaypointDistance=Double.POSITIVE_INFINITY;}
    private void releaseInput() {forward=false;jump=false;}
    private static Cell cell(BlockPos pos) {return new Cell(pos.getX(),pos.getY(),pos.getZ());}
    private static Vec3 feet(Cell cell) {return new Vec3(cell.x()+0.5,cell.y(),cell.z()+0.5);}
}
