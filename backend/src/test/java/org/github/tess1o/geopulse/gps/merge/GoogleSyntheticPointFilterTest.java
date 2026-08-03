package org.github.tess1o.geopulse.gps.merge;

import org.github.tess1o.geopulse.streaming.model.domain.GPSPoint;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GoogleSyntheticPointFilter}, which keeps Google's synthetic visit-centroid
 * points out of trip distance while leaving them available to stay detection.
 */
@Tag("unit")
class GoogleSyntheticPointFilterTest {

    private static final Instant START = Instant.parse("2026-08-01T09:00:00Z");

    @Test
    @DisplayName("a path with no synthetic points is returned unchanged, without copying")
    void realPathIsUntouched() {
        List<GPSPoint> path = List.of(real(0, 50.0, 30.0), real(300, 50.001, 30.0));

        assertSame(path, GoogleSyntheticPointFilter.excludeSyntheticVisitPoints(path));
    }

    @Test
    @DisplayName("synthetic visit-centroid points are removed from the path")
    void syntheticPointsAreRemoved() {
        List<GPSPoint> path = List.of(
                real(0, 50.0, 30.0),
                synthetic(300, 50.5, 30.5),
                synthetic(600, 50.5, 30.5),
                real(900, 50.01, 30.0));

        List<GPSPoint> filtered = GoogleSyntheticPointFilter.excludeSyntheticVisitPoints(path);

        assertEquals(2, filtered.size());
        assertTrue(filtered.stream().noneMatch(GPSPoint::isGoogleVisitSynthetic));
    }

    @Test
    @DisplayName("null and empty input never throw")
    void nullAndEmptyAreSafe() {
        assertEquals(List.of(), GoogleSyntheticPointFilter.excludeSyntheticVisitPoints(null));
        assertEquals(List.of(), GoogleSyntheticPointFilter.excludeSyntheticVisitPoints(List.of()));
        assertEquals(0.0, GoogleSyntheticPointFilter.distanceMetersExcludingSynthetic(null));
    }

    @Test
    @DisplayName("distance ignores the detour through a synthetic centroid entirely")
    void distanceSkipsSyntheticDetour() {
        // Two real fixes 1 bucket apart, with a synthetic centroid parked far away in between.
        // Counting through the centroid would report a large round trip that never happened.
        List<GPSPoint> path = List.of(
                real(0, 50.0, 30.0),
                synthetic(300, 51.0, 30.0),
                real(600, 50.0, 30.0));

        double withSynthetic = naiveDistance(path);
        double filtered = GoogleSyntheticPointFilter.distanceMetersExcludingSynthetic(path);

        assertTrue(withSynthetic > 200_000, "sanity: the naive sum should be a large phantom round trip");
        assertEquals(0.0, filtered, 0.001);
    }

    @Test
    @DisplayName("a path of nothing but synthetic points contributes no distance at all")
    void allSyntheticContributesNothing() {
        List<GPSPoint> path = List.of(
                synthetic(0, 50.0, 30.0),
                synthetic(300, 50.5, 30.5),
                synthetic(600, 51.0, 31.0));

        assertEquals(0.0, GoogleSyntheticPointFilter.distanceMetersExcludingSynthetic(path), 0.001);
    }

    @Test
    @DisplayName("real legs are still measured when synthetic points are interleaved")
    void realLegsSurvive() {
        GPSPoint first = real(0, 50.0, 30.0);
        GPSPoint last = real(900, 50.01, 30.0);
        List<GPSPoint> path = List.of(first, synthetic(300, 50.5, 30.5), last);

        assertEquals(first.distanceTo(last),
                GoogleSyntheticPointFilter.distanceMetersExcludingSynthetic(path), 0.001);
    }

    // ----------------------------------------------------------------------------------------

    private static double naiveDistance(List<GPSPoint> path) {
        double total = 0.0;
        for (int i = 1; i < path.size(); i++) {
            total += path.get(i - 1).distanceTo(path.get(i));
        }
        return total;
    }

    private static GPSPoint real(int secondsOffset, double latitude, double longitude) {
        return GPSPoint.builder()
                .timestamp(START.plusSeconds(secondsOffset))
                .latitude(latitude)
                .longitude(longitude)
                .speed(1.0)
                .accuracy(5.0)
                .build();
    }

    private static GPSPoint synthetic(int secondsOffset, double latitude, double longitude) {
        return GPSPoint.builder()
                .timestamp(START.plusSeconds(secondsOffset))
                .latitude(latitude)
                .longitude(longitude)
                .speed(0.0)
                .accuracy(0.0)
                .googleVisitSynthetic(true)
                .build();
    }
}
