package org.github.tess1o.geopulse.gps.integrations.googletimeline;

import org.github.tess1o.geopulse.gps.integrations.googletimeline.model.GoogleTimelineGpsPoint;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests that Google's per-visit placeID survives parsing in every export format the parser accepts.
 * <p>
 * The two export generations disagree on casing - the legacy array format writes "placeID", the
 * semantic-segments format writes "placeId" - which is exactly the kind of difference that silently
 * produces a null column rather than an error.
 */
@Tag("unit")
class StreamingGoogleTimelineParserTest {

    @Test
    void shouldCarryPlaceIdOntoVisitPoints_LegacyArrayFormat() throws IOException {
        String json = """
                [
                  {
                    "startTime": "2025-08-04T13:00:00Z",
                    "endTime": "2025-08-04T14:00:00Z",
                    "visit": {
                      "probability": "0.98",
                      "topCandidate": {
                        "placeLocation": "geo:50.4560,30.5290",
                        "semanticType": "TYPE_HOME",
                        "placeID": "ChIJlegacy123",
                        "probability": "0.98"
                      }
                    }
                  }
                ]
                """;

        List<GoogleTimelineGpsPoint> points = parse(json);
        List<GoogleTimelineGpsPoint> visits = ofType(points, "visit");

        assertFalse(visits.isEmpty(), "Expected interpolated visit points");
        for (GoogleTimelineGpsPoint point : visits) {
            assertEquals("ChIJlegacy123", point.getPlaceId());
            assertTrue(point.isSyntheticVisitPoint());
        }
    }

    @Test
    void shouldCarryPlaceIdOntoVisitPoints_SemanticSegmentsFormat() throws IOException {
        String json = """
                {
                  "semanticSegments": [
                    {
                      "startTime": "2015-09-20T12:18:37.000Z",
                      "endTime": "2015-09-20T12:29:17.000Z",
                      "visit": {
                        "hierarchyLevel": 0,
                        "probability": 0.69,
                        "topCandidate": {
                          "placeId": "ChIJsemantic456",
                          "semanticType": "UNKNOWN",
                          "probability": 0.15,
                          "placeLocation": {
                            "latLng": "40.7130°, -74.0062°"
                          }
                        }
                      }
                    }
                  ]
                }
                """;

        List<GoogleTimelineGpsPoint> points = parse(json);
        List<GoogleTimelineGpsPoint> visits = ofType(points, "visit");

        assertFalse(visits.isEmpty(), "Expected interpolated visit points");
        for (GoogleTimelineGpsPoint point : visits) {
            assertEquals("ChIJsemantic456", point.getPlaceId());
            assertTrue(point.isSyntheticVisitPoint());
        }
    }

    @Test
    void shouldAcceptEitherCasing_InEitherFormat() throws IOException {
        // Defensive: exports in the wild have been seen mixing the two spellings.
        String legacyWithNewCasing = """
                [
                  {
                    "startTime": "2025-08-04T13:00:00Z",
                    "endTime": "2025-08-04T13:10:00Z",
                    "visit": {
                      "topCandidate": {
                        "placeLocation": "geo:50.4560,30.5290",
                        "semanticType": "TYPE_HOME",
                        "placeId": "ChIJmixed789"
                      }
                    }
                  }
                ]
                """;

        List<GoogleTimelineGpsPoint> visits = ofType(parse(legacyWithNewCasing), "visit");

        assertFalse(visits.isEmpty());
        assertEquals("ChIJmixed789", visits.getFirst().getPlaceId());
    }

    @Test
    void shouldLeavePlaceIdNull_ForNonVisitPoints() throws IOException {
        String json = """
                [
                  {
                    "startTime": "2025-08-04T12:00:00Z",
                    "endTime": "2025-08-04T12:30:00Z",
                    "activity": {
                      "start": "geo:50.4501,30.5234",
                      "end": "geo:50.4540,30.5270",
                      "distanceMeters": "4500",
                      "topCandidate": {
                        "type": "WALKING",
                        "probability": "0.95"
                      }
                    }
                  }
                ]
                """;

        List<GoogleTimelineGpsPoint> points = parse(json);

        assertEquals(2, points.size());
        for (GoogleTimelineGpsPoint point : points) {
            assertNull(point.getPlaceId());
            assertFalse(point.isSyntheticVisitPoint());
        }
    }

    @Test
    void shouldLeavePlaceIdNull_RecordsFormat() throws IOException {
        // The Records format has no notion of a visit, so there is no placeID to carry. This test
        // exists to pin that it parses cleanly rather than being skipped by the visit branch.
        String json = """
                {
                  "locations": [
                    {
                      "latitudeE7": 504560000,
                      "longitudeE7": 305290000,
                      "timestamp": "2025-08-04T13:00:00Z",
                      "accuracy": 20
                    }
                  ]
                }
                """;

        List<GoogleTimelineGpsPoint> points = parse(json);

        assertEquals(1, points.size());
        GoogleTimelineGpsPoint point = points.getFirst();
        assertEquals("records_location", point.getRecordType());
        assertNull(point.getPlaceId());
        assertFalse(point.isSyntheticVisitPoint());
    }

    @Test
    void shouldLeavePlaceIdNull_WhenVisitHasNoPlaceId() throws IOException {
        // Matches tests/fixtures/import-samples/google-timeline-sample.json, whose visit carries no
        // placeID at all.
        String json = """
                [
                  {
                    "startTime": "2025-08-04T13:00:00Z",
                    "endTime": "2025-08-04T14:00:00Z",
                    "visit": {
                      "topCandidate": {
                        "placeLocation": "geo:50.4560,30.5290",
                        "semanticType": "TYPE_HOME",
                        "probability": "0.98"
                      }
                    }
                  }
                ]
                """;

        List<GoogleTimelineGpsPoint> visits = ofType(parse(json), "visit");

        assertFalse(visits.isEmpty());
        for (GoogleTimelineGpsPoint point : visits) {
            assertNull(point.getPlaceId());
            // Still synthetic - the flag describes how the point was produced, not whether Google
            // told us where it was.
            assertTrue(point.isSyntheticVisitPoint());
        }
    }

    @Test
    void shouldDetectFormats() throws IOException {
        assertEquals(StreamingGoogleTimelineParser.FormatType.LEGACY_ARRAY, statsFor("[]").formatType);
        assertEquals(StreamingGoogleTimelineParser.FormatType.RECORDS,
                statsFor("{\"locations\": []}").formatType);
        assertEquals(StreamingGoogleTimelineParser.FormatType.SEMANTIC_SEGMENTS,
                statsFor("{\"semanticSegments\": []}").formatType);
    }

    private static List<GoogleTimelineGpsPoint> ofType(List<GoogleTimelineGpsPoint> points, String recordType) {
        List<GoogleTimelineGpsPoint> filtered = new ArrayList<>();
        for (GoogleTimelineGpsPoint point : points) {
            if (recordType.equals(point.getRecordType())) {
                filtered.add(point);
            }
        }
        return filtered;
    }

    private static List<GoogleTimelineGpsPoint> parse(String json) throws IOException {
        List<GoogleTimelineGpsPoint> points = new ArrayList<>();
        parser(json).parseGpsPoints((point, stats) -> points.add(point));
        return points;
    }

    private static StreamingGoogleTimelineParser.ParsingStats statsFor(String json) throws IOException {
        StreamingGoogleTimelineParser.ParsingStats stats =
                parser(json).parseGpsPoints((point, s) -> {
                });
        assertNotNull(stats);
        return stats;
    }

    private static StreamingGoogleTimelineParser parser(String json) {
        return new StreamingGoogleTimelineParser(
                new ByteArrayInputStream(json.getBytes(StandardCharsets.UTF_8)));
    }
}
