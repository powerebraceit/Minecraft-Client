package dev.aurora.core.builder;

/** Placement uses ordinary survival reach, measured from the eyes to the clicked surface. */
public final class PlacementReach {
    public static final double SURVIVAL_MAX=4.5;
    private PlacementReach() { }
    public static double limit(double attributeRange) {
        if(Double.isNaN(attributeRange) || attributeRange<0)return 0;
        return Math.min(SURVIVAL_MAX,attributeRange);
    }
    public static boolean withinSquared(double attributeRange,double distanceSquared) {
        double reach=limit(attributeRange);
        return Double.isFinite(distanceSquared) && distanceSquared>=0 && distanceSquared<=reach*reach;
    }
}
