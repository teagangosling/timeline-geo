package org.github.tess1o.geopulse.gps.merge;

import org.github.tess1o.geopulse.streaming.model.domain.GPSPoint;

import java.util.ArrayList;
import java.util.List;

/**
 * FORK (WS-2): removes synthetic Google Timeline visit points from trip geometry.
 *
 * <p>Google Timeline "visit" entries are not GPS fixes. {@code StreamingGoogleTimelineParser}
 * synthesises one point every 5 minutes sitting exactly on the visit centroid, so a stay of two
 * hours becomes 24 identical points. {@code V90.0.0} exposes that as the generated column
 * {@code gps_points.google_visit_synthetic}, surfaced on {@link GPSPoint#isGoogleVisitSynthetic()}.
 *
 * <h2>Keep them in stays, drop them from trips</h2>
 * Those points are exactly what lets GeoPulse reproduce Google's own stay decisions, so stay
 * detection must keep seeing them. Trip <em>path geometry and distance</em> must not: a chain of
 * centroid points between two visits is a straight-line teleport, which is wrong in both
 * directions at once. It understates a real journey (no road geometry, no detours) and overstates
 * a stationary period (centroid jitter accumulating as travel), and it feeds a bogus figure into
 * speed-based travel-mode classification.
 *
 * <p>The filter is pure and allocation-free when there is nothing to remove, so it is safe to put
 * on the hot path of every finalized trip.
 */
public final class GoogleSyntheticPointFilter {

    private GoogleSyntheticPointFilter() {
    }

    /**
     * @param path trip path in chronological order
     * @return the same list instance when it holds no synthetic visit points, otherwise a new list
     * with them removed. Never null.
     */
    public static List<GPSPoint> excludeSyntheticVisitPoints(List<GPSPoint> path) {
        if (path == null || path.isEmpty()) {
            return path == null ? List.of() : path;
        }

        boolean hasSynthetic = false;
        for (GPSPoint point : path) {
            if (isSynthetic(point)) {
                hasSynthetic = true;
                break;
            }
        }
        if (!hasSynthetic) {
            return path;
        }

        List<GPSPoint> filtered = new ArrayList<>(path.size());
        for (GPSPoint point : path) {
            if (!isSynthetic(point)) {
                filtered.add(point);
            }
        }
        return filtered;
    }

    /**
     * Sum of great-circle distances between consecutive points, ignoring synthetic Google visit
     * points entirely - the legs into and out of a synthetic run collapse into one real leg rather
     * than being counted through the centroid.
     *
     * @param path trip path in chronological order
     * @return distance in meters; 0 when fewer than two real points remain
     */
    public static double distanceMetersExcludingSynthetic(List<GPSPoint> path) {
        List<GPSPoint> real = excludeSyntheticVisitPoints(path);
        if (real.size() < 2) {
            return 0.0;
        }
        double total = 0.0;
        for (int i = 1; i < real.size(); i++) {
            total += real.get(i - 1).distanceTo(real.get(i));
        }
        return total;
    }

    private static boolean isSynthetic(GPSPoint point) {
        return point != null && point.isGoogleVisitSynthetic();
    }
}
