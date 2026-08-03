package org.github.tess1o.geopulse.gps.merge;

import org.github.tess1o.geopulse.gps.merge.model.CoverageBucket;
import org.github.tess1o.geopulse.gps.merge.model.SourceSegment;
import org.github.tess1o.geopulse.shared.gps.GpsSourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Stream;

import static org.github.tess1o.geopulse.shared.gps.GpsSourceType.GOOGLE_TIMELINE;
import static org.github.tess1o.geopulse.shared.gps.GpsSourceType.GPX;
import static org.github.tess1o.geopulse.shared.gps.GpsSourceType.HOME_ASSISTANT;
import static org.github.tess1o.geopulse.shared.gps.GpsSourceType.OWNTRACKS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link GpsSourceMergeService#assignSegments}, the pure decision layer of the
 * cross-source GPS merge. No database, no CDI.
 *
 * <p>Buckets are described with a compact spec: one token per 5 minute bucket, {@code +} separating
 * co-present sources inside one bucket. {@code "G G H+G H"} means four consecutive buckets, the
 * third of which was covered by both Google and Home Assistant.
 */
@Tag("unit")
class GpsSourceMergeSegmentAssignmentTest {

    private static final Instant FIRST_BUCKET = Instant.parse("2026-08-01T00:00:00Z");
    private static final List<GpsSourceType> DEFAULT_PRIORITY =
            SourcePriorityConfig.parsePriority(SourcePriorityConfig.DEFAULT_PRIORITY_CSV);
    private static final int MIN_SEGMENT_BUCKETS = SourcePriorityConfig.DEFAULT_MIN_SEGMENT_BUCKETS; // 3

    static Stream<Arguments> segmentCases() {
        return Stream.of(
                Arguments.of(
                        "google-only run stays one Google segment",
                        "G G G G G G", MIN_SEGMENT_BUCKETS,
                        List.of(GOOGLE_TIMELINE), List.of(6)),

                Arguments.of(
                        "home-assistant-only run stays one HA segment",
                        "H H H H H H", MIN_SEGMENT_BUCKETS,
                        List.of(HOME_ASSISTANT), List.of(6)),

                Arguments.of(
                        "a short single-source day is never coalesced away - there is nothing to merge into",
                        "H", MIN_SEGMENT_BUCKETS,
                        List.of(HOME_ASSISTANT), List.of(1)),

                Arguments.of(
                        "co-present sources in one bucket: highest priority wins outright, no blending",
                        "H+G H+G G+H H+G", MIN_SEGMENT_BUCKETS,
                        List.of(GOOGLE_TIMELINE), List.of(4)),

                Arguments.of(
                        "gap fill is emergent: buckets Google does not occupy go to whoever does",
                        "G G G G H H H H", MIN_SEGMENT_BUCKETS,
                        List.of(GOOGLE_TIMELINE, HOME_ASSISTANT), List.of(4, 4)),

                Arguments.of(
                        "one-bucket Google island inside an HA run is swallowed by hysteresis",
                        "H H H H G H H H H", MIN_SEGMENT_BUCKETS,
                        List.of(HOME_ASSISTANT), List.of(9)),

                Arguments.of(
                        "two-bucket Google island is still below the 3-bucket floor and is swallowed",
                        "H H H H G G H H H H", MIN_SEGMENT_BUCKETS,
                        List.of(HOME_ASSISTANT), List.of(10)),

                Arguments.of(
                        "five-bucket Google island survives hysteresis as its own segment",
                        "H H H H G G G G G H H H H", MIN_SEGMENT_BUCKETS,
                        List.of(HOME_ASSISTANT, GOOGLE_TIMELINE, HOME_ASSISTANT), List.of(4, 5, 4)),

                Arguments.of(
                        "three-bucket island sits exactly on the floor and survives",
                        "H H H H G G G H H H H", MIN_SEGMENT_BUCKETS,
                        List.of(HOME_ASSISTANT, GOOGLE_TIMELINE, HOME_ASSISTANT), List.of(4, 3, 4)),

                Arguments.of(
                        "edge flap at a source boundary resolves to the authoritative source",
                        "G H G H G H G H", MIN_SEGMENT_BUCKETS,
                        List.of(GOOGLE_TIMELINE), List.of(8)),

                Arguments.of(
                        "minSegmentBuckets = 1 disables hysteresis and keeps every raw flip",
                        "H G H", 1,
                        List.of(HOME_ASSISTANT, GOOGLE_TIMELINE, HOME_ASSISTANT), List.of(1, 1, 1)),

                Arguments.of(
                        "minSegmentBuckets below 1 is clamped to 1 rather than throwing",
                        "H G H", 0,
                        List.of(HOME_ASSISTANT, GOOGLE_TIMELINE, HOME_ASSISTANT), List.of(1, 1, 1)),

                Arguments.of(
                        "a third source only wins buckets the higher-priority feeds do not cover",
                        "G G G G O O O O G G G G", MIN_SEGMENT_BUCKETS,
                        List.of(GOOGLE_TIMELINE, OWNTRACKS, GOOGLE_TIMELINE), List.of(4, 4, 4))
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("segmentCases")
    void assignsSegments(String description,
                         String bucketSpec,
                         int minSegmentBuckets,
                         List<GpsSourceType> expectedSources,
                         List<Integer> expectedBucketCounts) {
        List<CoverageBucket> buckets = buckets(bucketSpec);

        List<SourceSegment> segments =
                GpsSourceMergeService.assignSegments(buckets, DEFAULT_PRIORITY, minSegmentBuckets);

        assertEquals(expectedSources, segments.stream().map(SourceSegment::source).toList(), description);
        assertEquals(expectedBucketCounts, segments.stream().map(SourceSegment::bucketCount).toList(), description);
        assertCoversEveryBucketExactlyOnce(buckets, segments, description);
    }

    @Test
    @DisplayName("empty and null input produce no segments rather than throwing")
    void handlesEmptyInput() {
        assertEquals(List.of(), GpsSourceMergeService.assignSegments(null, DEFAULT_PRIORITY, 3));
        assertEquals(List.of(), GpsSourceMergeService.assignSegments(List.of(), DEFAULT_PRIORITY, 3));
    }

    @Test
    @DisplayName("buckets with no sources at all are dropped, not awarded to anyone")
    void ignoresEmptyBuckets() {
        List<CoverageBucket> buckets = new ArrayList<>(buckets("H H H"));
        buckets.add(new CoverageBucket(FIRST_BUCKET.plusSeconds(3 * 300), Set.of()));

        List<SourceSegment> segments = GpsSourceMergeService.assignSegments(buckets, DEFAULT_PRIORITY, 3);

        assertEquals(1, segments.size());
        assertEquals(3, segments.getFirst().bucketCount());
    }

    @Test
    @DisplayName("input order does not matter - buckets are sorted before segmentation")
    void sortsBucketsBeforeSegmenting() {
        List<CoverageBucket> shuffled = new ArrayList<>(buckets("G G G G H H H H"));
        Collections.reverse(shuffled);

        List<SourceSegment> segments = GpsSourceMergeService.assignSegments(shuffled, DEFAULT_PRIORITY, 3);

        assertEquals(List.of(GOOGLE_TIMELINE, HOME_ASSISTANT), segments.stream().map(SourceSegment::source).toList());
        assertEquals(FIRST_BUCKET, segments.getFirst().startBucket());
        assertTrue(segments.getFirst().endBucket().isBefore(segments.get(1).startBucket()));
    }

    @Test
    @DisplayName("an empty priority list falls back to the built-in default order")
    void emptyPriorityFallsBackToDefault() {
        List<CoverageBucket> buckets = buckets("H+G H+G H+G H+G");

        List<SourceSegment> fromEmpty = GpsSourceMergeService.assignSegments(buckets, List.of(), 3);
        List<SourceSegment> fromNull = GpsSourceMergeService.assignSegments(buckets, null, 3);

        assertEquals(List.of(GOOGLE_TIMELINE), fromEmpty.stream().map(SourceSegment::source).toList());
        assertEquals(List.of(GOOGLE_TIMELINE), fromNull.stream().map(SourceSegment::source).toList());
    }

    @Test
    @DisplayName("a source missing from the priority list ranks below every listed source")
    void unlistedSourceRanksLast() {
        // GPX is deliberately absent from this partial priority list.
        List<GpsSourceType> partialPriority = List.of(HOME_ASSISTANT, GOOGLE_TIMELINE);

        List<SourceSegment> segments =
                GpsSourceMergeService.assignSegments(buckets("X+H X+H X+H X+H"), partialPriority, 3);

        assertEquals(List.of(HOME_ASSISTANT), segments.stream().map(SourceSegment::source).toList());
    }

    @Test
    @DisplayName("a source missing from the priority list still wins buckets nobody else covers")
    void unlistedSourceStillWinsUncontestedBuckets() {
        List<GpsSourceType> partialPriority = List.of(HOME_ASSISTANT, GOOGLE_TIMELINE);

        List<SourceSegment> segments =
                GpsSourceMergeService.assignSegments(buckets("H H H H X X X X"), partialPriority, 3);

        assertEquals(List.of(HOME_ASSISTANT, GPX), segments.stream().map(SourceSegment::source).toList());
        assertEquals(List.of(4, 4), segments.stream().map(SourceSegment::bucketCount).toList());
    }

    @Test
    @DisplayName("segment bucket instants line up with the 5 minute grid of the input")
    void segmentsCarryTheirBucketInstants() {
        List<SourceSegment> segments =
                GpsSourceMergeService.assignSegments(buckets("G G G G H H H H"), DEFAULT_PRIORITY, 3);

        SourceSegment google = segments.getFirst();
        assertEquals(FIRST_BUCKET, google.startBucket());
        assertEquals(FIRST_BUCKET.plusSeconds(3 * 300), google.endBucket());

        SourceSegment homeAssistant = segments.get(1);
        assertEquals(FIRST_BUCKET.plusSeconds(4 * 300), homeAssistant.startBucket());
        assertEquals(FIRST_BUCKET.plusSeconds(7 * 300), homeAssistant.endBucket());
    }

    @Test
    @DisplayName("Java-side bucket edges match date_bin('5 minutes', ts, TIMESTAMPTZ '2000-01-01')")
    void bucketEdgesMatchDateBin() {
        // 2000-01-01T00:00:00Z is itself on a 5 minute boundary from the epoch, so the two agree
        // everywhere - this pins that fact so a future origin change cannot silently desync the
        // Java range widening from the SQL bucketing.
        assertEquals(0L, GpsSourceMergeService.BUCKET_ORIGIN.getEpochSecond() % 300L);

        Instant onEdge = Instant.parse("2026-08-01T10:05:00Z");
        Instant inside = Instant.parse("2026-08-01T10:07:30Z");

        assertEquals(onEdge, GpsSourceMergeService.floorToBucket(onEdge));
        assertEquals(onEdge, GpsSourceMergeService.ceilToBucket(onEdge));
        assertEquals(onEdge, GpsSourceMergeService.floorToBucket(inside));
        assertEquals(Instant.parse("2026-08-01T10:10:00Z"), GpsSourceMergeService.ceilToBucket(inside));
    }

    @Test
    @DisplayName("bucket flooring works for instants before the alignment origin")
    void bucketFlooringHandlesPreOriginInstants() {
        Instant beforeOrigin = Instant.parse("1999-12-31T23:57:30Z");

        assertEquals(Instant.parse("1999-12-31T23:55:00Z"), GpsSourceMergeService.floorToBucket(beforeOrigin));
        assertEquals(Instant.parse("2000-01-01T00:00:00Z"), GpsSourceMergeService.ceilToBucket(beforeOrigin));
    }

    // ----------------------------------------------------------------------------------------

    private static void assertCoversEveryBucketExactlyOnce(List<CoverageBucket> buckets,
                                                           List<SourceSegment> segments,
                                                           String description) {
        List<Instant> covered = segments.stream().flatMap(s -> s.buckets().stream()).toList();
        List<Instant> expected = buckets.stream().map(CoverageBucket::bucketStart).sorted().toList();
        assertEquals(expected, covered, description + " (segments must partition the coverage list)");
    }

    /**
     * "G G H+G H" -> four consecutive 5 minute buckets starting at {@link #FIRST_BUCKET}.
     */
    private static List<CoverageBucket> buckets(String spec) {
        String[] tokens = spec.trim().split("\\s+");
        List<CoverageBucket> result = new ArrayList<>(tokens.length);
        for (int i = 0; i < tokens.length; i++) {
            GpsSourceType[] sources = Stream.of(tokens[i].split("\\+"))
                    .map(GpsSourceMergeSegmentAssignmentTest::source)
                    .toArray(GpsSourceType[]::new);
            result.add(CoverageBucket.of(FIRST_BUCKET.plusSeconds(i * 300L), sources));
        }
        return result;
    }

    private static GpsSourceType source(String token) {
        return switch (token) {
            case "G" -> GOOGLE_TIMELINE;
            case "H" -> HOME_ASSISTANT;
            case "O" -> OWNTRACKS;
            case "X" -> GPX;
            default -> throw new IllegalArgumentException("Unknown bucket source token: " + token);
        };
    }
}
