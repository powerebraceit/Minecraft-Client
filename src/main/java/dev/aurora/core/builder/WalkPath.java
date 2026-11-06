package dev.aurora.core.builder;

import java.util.*;
import java.util.function.Predicate;

/** Bounded, loaded-terrain-only walking search: no digging, teleportation or unverified jumps. */
public final class WalkPath {
    public record Point(int x, int y, int z) {
        public Point offset(int dx, int dy, int dz) { return new Point(x+dx,y+dy,z+dz); }
    }
    private WalkPath() { }
    public static List<Point> find(Point start, Predicate<Point> walkable, Predicate<Point> clear, Predicate<Point> goal, int maxNodes) {
        ArrayDeque<Point> queue = new ArrayDeque<>();
        Map<Point,Point> parent = new HashMap<>();
        queue.add(start); parent.put(start,start);
        int visited=0;
        while (!queue.isEmpty() && visited++ < maxNodes) {
            Point point=queue.removeFirst();
            if (goal.test(point)) {
                List<Point> path=new ArrayList<>();
                while (!point.equals(start)) { path.add(point); point=parent.get(point); }
                Collections.reverse(path);return path;
            }
            for (int[] dir : new int[][]{{1,0},{-1,0},{0,1},{0,-1}}) {
                for (int dy : new int[]{0,1,-1}) {
                    Point next=point.offset(dir[0],dy,dir[1]);
                    if (Math.abs(next.x-start.x)>48 || Math.abs(next.z-start.z)>48 || Math.abs(next.y-start.y)>16) continue;
                    // Steps up also require head clearance above the current cell.
                    if (dy==1 && !clear.test(point.offset(0,2,0))) continue;
                    if (walkable.test(next)) {
                        if (parent.putIfAbsent(next,point)==null) queue.addLast(next);
                        break;
                    }
                }
            }
        }
        throw new IllegalStateException("No loaded walking route within the 8192-node search budget");
    }
}
