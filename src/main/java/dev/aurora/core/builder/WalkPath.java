package dev.aurora.core.builder;

import java.util.*;
import java.util.function.Predicate;

/** Bounded, loaded-terrain-only walking search: no digging, teleportation or unverified jumps. */
public final class WalkPath {
    public record Point(int x,int y,int z) {
        public Point offset(int dx,int dy,int dz) {return new Point(x+dx,y+dy,z+dz);}
    }
    private WalkPath() { }
    public static List<Point> find(Point start,Predicate<Point> walkable,Predicate<Point> clear,Predicate<Point> goal,int maxNodes) {
        return search(start,walkable,clear,goal,maxNodes,null,p->true);
    }
    /** Breadth-first search still picks a shortest route; equally short choices face the target. */
    public static List<Point> findToward(Point start,Predicate<Point> walkable,Predicate<Point> clear,Predicate<Point> goal,int maxNodes,Point target,int margin) {
        if(margin<0)throw new IllegalArgumentException("Negative route margin");
        int minX=Math.min(start.x,target.x)-margin,maxX=Math.max(start.x,target.x)+margin;
        int minZ=Math.min(start.z,target.z)-margin,maxZ=Math.max(start.z,target.z)+margin;
        int minY=Math.min(start.y,target.y)-3,maxY=Math.max(start.y,target.y)+3;
        return search(start,walkable,clear,goal,maxNodes,target,p->p.x>=minX && p.x<=maxX && p.z>=minZ && p.z<=maxZ && p.y>=minY && p.y<=maxY);
    }
    private static List<Point> search(Point start,Predicate<Point> walkable,Predicate<Point> clear,Predicate<Point> goal,int maxNodes,Point toward,Predicate<Point> allowed) {
        ArrayDeque<Point> queue=new ArrayDeque<>();Map<Point,Point> parent=new HashMap<>();
        queue.add(start);parent.put(start,start);int visited=0;
        while(!queue.isEmpty() && visited++<maxNodes) {
            Point point=queue.removeFirst();
            if(goal.test(point)) {
                List<Point> path=new ArrayList<>();
                while(!point.equals(start)) {path.add(point);point=parent.get(point);}
                Collections.reverse(path);return path;
            }
            var directions=new ArrayList<>(List.of(new int[]{1,0},new int[]{-1,0},new int[]{0,1},new int[]{0,-1}));
            Point expansion=point;
            if(toward!=null)directions.sort(Comparator.comparingLong(d->distance(expansion.offset(d[0],0,d[1]),toward)));
            for(int[] direction:directions)for(int dy:new int[]{0,1,-1}) {
                Point next=point.offset(direction[0],dy,direction[1]);
                if(Math.abs(next.x-start.x)>48 || Math.abs(next.z-start.z)>48 || Math.abs(next.y-start.y)>16 || !allowed.test(next))continue;
                if(dy==1 && !clear.test(point.offset(0,2,0)))continue;
                if(walkable.test(next)) {
                    if(parent.putIfAbsent(next,point)==null)queue.addLast(next);
                    break;
                }
            }
        }
        throw new IllegalStateException("No loaded walking route within the "+maxNodes+"-node search budget");
    }
    private static long distance(Point a,Point b) {long dx=(long)a.x-b.x,dz=(long)a.z-b.z;return dx*dx+dz*dz;}
}
