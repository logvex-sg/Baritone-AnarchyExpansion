package dev.basehunt.bhclient.core.logic;

import java.util.ArrayList;
import java.util.List;

/**
 * Generates the sequence of waypoints for each exploration pattern.
 *
 * <p>Pure coordinate maths, deliberately free of Minecraft types so the patterns can be unit tested: a grid
 * sweep that silently skips a column, or a spiral that never grows, would waste real flight time on a live
 * server before anyone noticed.
 */
public final class ExplorationPlanner {

    /** The patterns the exploration controller can fly. */
    public enum Mode {
        GRID,
        SPIRAL,
        ADAPTIVE,
        COORDINATES,
        HEADING,
        WAYPOINTS
    }

    public record Waypoint(int x, int z, String label) {
    }

    private ExplorationPlanner() {
    }

    /**
     * Boustrophedon ("snake") grid sweep: parallel legs {@code spacing} apart, alternating direction so the
     * route never doubles back across a leg it just flew.
     *
     * @param count number of legs to generate
     */
    public static List<Waypoint> grid(int originX, int originZ, int legLength, int spacing, int count) {
        List<Waypoint> points = new ArrayList<>();
        int step = Math.max(1, spacing);
        int length = Math.max(1, legLength);

        for (int leg = 0; leg < Math.max(0, count); leg++) {
            int laneZ = originZ + leg * step;
            boolean forward = leg % 2 == 0;
            int endX = forward ? originX + length : originX - length;

            points.add(new Waypoint(endX, laneZ, "grid leg " + (leg + 1)));
            // Close the lane at its far end so the next lane starts adjacent to where this one finished.
            points.add(new Waypoint(endX, laneZ + step, "grid turn " + (leg + 1)));
        }

        return points;
    }

    /**
     * Square spiral: each revolution grows the radius by {@code step} until {@code maxRadius} is reached.
     * Waypoints are placed at the corners of each ring so the path is a sequence of straight legs.
     */
    public static List<Waypoint> spiral(int centreX, int centreZ, int step, int maxRadius) {
        List<Waypoint> points = new ArrayList<>();
        int s = Math.max(1, step);
        int limit = Math.max(s, maxRadius);

        int x = centreX;
        int z = centreZ;
        points.add(new Waypoint(x, z, "spiral centre"));

        int ring = 1;
        while ((long) ring * s <= limit) {
            int r = ring * s;
            // Right, down, left, up - a clockwise ring at radius r around the centre.
            points.add(new Waypoint(centreX + r, z, "spiral ring " + ring));
            z = centreZ + r;
            points.add(new Waypoint(x = centreX + r, z, "spiral ring " + ring));
            points.add(new Waypoint(x = centreX - r, z, "spiral ring " + ring));
            points.add(new Waypoint(x, z = centreZ - r, "spiral ring " + ring));
            points.add(new Waypoint(x = centreX + r, z, "spiral ring " + ring));
            ring++;
        }

        return points;
    }

    /**
     * Adaptive sweep: a grid whose lane spacing tightens near known points of interest, so promising terrain
     * is covered densely while empty terrain is crossed quickly.
     *
     * @param tightSpacing lane spacing used within {@code tightRadius} of an interest point
     * @param wideSpacing  lane spacing used elsewhere
     */
    public static List<Waypoint> adaptive(int originX, int originZ, int legLength, int count,
                                          List<int[]> interestPoints, int tightRadius,
                                          int tightSpacing, int wideSpacing) {
        List<Waypoint> points = new ArrayList<>();
        int length = Math.max(1, legLength);
        int z = originZ;

        for (int leg = 0; leg < Math.max(0, count); leg++) {
            boolean near = isNearInterest(originX, z, interestPoints, tightRadius);
            int spacing = Math.max(1, near ? tightSpacing : wideSpacing);

            boolean forward = leg % 2 == 0;
            int endX = forward ? originX + length : originX - length;

            points.add(new Waypoint(endX, z, near ? "adaptive leg " + (leg + 1) + " (dense)" : "adaptive leg " + (leg + 1)));
            points.add(new Waypoint(endX, z + spacing, "adaptive turn " + (leg + 1)));

            z += spacing;
        }

        return points;
    }

    private static boolean isNearInterest(int x, int z, List<int[]> interestPoints, int radius) {
        if (interestPoints == null) return false;
        long r2 = (long) radius * radius;

        for (int[] point : interestPoints) {
            if (point == null || point.length < 2) continue;
            long dx = point[0] - x;
            long dz = point[1] - z;
            if (dx * dx + dz * dz <= r2) return true;
        }

        return false;
    }

    /**
     * Extends a heading-based sweep by one leg, returning the next destination. Kept stateless so an
     * interrupted run can be resumed by feeding back the last leg index.
     */
    public static Waypoint headingLeg(int originX, int originZ, double headingDegrees, int legLength, int legIndex, int spacing) {
        double radians = Math.toRadians(headingDegrees);
        double dirX = Math.sin(radians);
        double dirZ = -Math.cos(radians);

        // Offset perpendicular to the heading by whole lanes so successive legs do not overlap.
        double perpX = -dirZ;
        double perpZ = dirX;

        double offset = legIndex * (double) Math.max(1, spacing);
        int baseX = (int) Math.round(originX + perpX * offset);
        int baseZ = (int) Math.round(originZ + perpZ * offset);

        int endX = (int) Math.round(baseX + dirX * legLength);
        int endZ = (int) Math.round(baseZ + dirZ * legLength);

        return new Waypoint(endX, endZ, "heading leg " + (legIndex + 1));
    }

    /**
     * Picks the next exploration origin after a region stops producing discoveries: continue outward along
     * the current heading by {@code pushDistance}, so exploration expands instead of retreading.
     */
    public static Waypoint expandFrom(int lastX, int lastZ, int originX, int originZ, int pushDistance) {
        double dx = lastX - originX;
        double dz = lastZ - originZ;
        double length = Math.sqrt(dx * dx + dz * dz);

        if (length < 1.0) {
            // No meaningful direction: push east so there is at least a deterministic heading.
            return new Waypoint(lastX + pushDistance, lastZ, "expansion (no heading)");
        }

        int x = (int) Math.round(lastX + dx / length * pushDistance);
        int z = (int) Math.round(lastZ + dz / length * pushDistance);
        return new Waypoint(x, z, "expansion");
    }

    /**
     * Greedy nearest-neighbour ordering of raw waypoints, reusing the same idea as the target planner so a
     * custom route does not zig-zag across the map.
     */
    public static List<Waypoint> orderByNearest(List<Waypoint> waypoints, int fromX, int fromZ) {
        List<Waypoint> remaining = new ArrayList<>(waypoints);
        List<Waypoint> ordered = new ArrayList<>(remaining.size());

        double x = fromX;
        double z = fromZ;

        while (!remaining.isEmpty()) {
            int bestIndex = -1;
            double bestDistance = Double.MAX_VALUE;

            for (int i = 0; i < remaining.size(); i++) {
                Waypoint waypoint = remaining.get(i);
                double distance = Math.pow(waypoint.x() - x, 2) + Math.pow(waypoint.z() - z, 2);
                if (distance < bestDistance) {
                    bestDistance = distance;
                    bestIndex = i;
                }
            }

            if (bestIndex < 0) break;

            Waypoint chosen = remaining.remove(bestIndex);
            ordered.add(chosen);
            x = chosen.x();
            z = chosen.z();
        }

        return ordered;
    }

    /** Total straight-line length of a waypoint sequence, used to compare route options. */
    public static double routeLength(List<Waypoint> waypoints, int fromX, int fromZ) {
        double total = 0;
        double x = fromX;
        double z = fromZ;

        for (Waypoint waypoint : waypoints) {
            total += Math.hypot(waypoint.x() - x, waypoint.z() - z);
            x = waypoint.x();
            z = waypoint.z();
        }

        return total;
    }
}
