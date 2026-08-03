package org.github.tess1o.geopulse.gps.merge;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.github.tess1o.geopulse.gps.merge.model.CoverageBucket;
import org.github.tess1o.geopulse.gps.merge.model.SourceSegment;
import org.github.tess1o.geopulse.shared.gps.GpsSourceType;
import org.github.tess1o.geopulse.shared.service.TimestampUtils;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * FORK (WS-2): cross-source GPS reconciliation.
 *
 * <p>This instance ingests both periodic Google Timeline exports and a live Home Assistant feed.
 * Upstream's {@code GpsPointDuplicateDetectionService.isLocationDuplicate()} only compares points
 * whose {@code sourceType} matches, so cross-source dedup never fires: both point sets land
 * interleaved in one stream, stay detection runs over the mixture, and the result is fragmented
 * stays plus inflated trip distance.
 *
 * <h2>What this does</h2>
 * <ol>
 *   <li>{@link #loadCoverage} buckets the touched time range into 5 minute windows and records
 *       which sources produced at least one point in each.</li>
 *   <li>{@link #assignSegments} elects the highest priority source present in each bucket, then
 *       coalesces short runs so the winner sequence does not flap at source boundaries.</li>
 *   <li>{@link #applySuppression} sets {@code gps_points.suppressed} for every point in the range:
 *       true for points whose source lost its segment, false for the winners.</li>
 * </ol>
 *
 * <h2>Why a persisted flag</h2>
 * Nothing is deleted. Changing {@code gps.source-priority} and re-running fully reverses the
 * previous decision, and a day that looks wrong can be inspected directly in psql. Query-time
 * merging was rejected because it fights the versioned / {@code is_stale} timeline cache and would
 * recompute on every read.
 *
 * <h2>What this deliberately does NOT do</h2>
 * It never averages or blends coordinates across sources. Google "visit" points are not real
 * fixes - {@code StreamingGoogleTimelineParser} synthesises one every 5 minutes at the visit
 * centroid - so blending them with real fixes would be meaningless. One source wins a window
 * outright; the others are hidden from timeline generation only.
 */
@ApplicationScoped
@Slf4j
public class GpsSourceMergeService {

    /**
     * Matches Google's synthetic visit cadence and Home Assistant's typical reporting interval.
     * 288 buckets per day.
     */
    public static final Duration BUCKET = Duration.ofMinutes(5);

    /**
     * Bucket alignment origin. Must match the third argument of {@code date_bin} in the SQL below,
     * so that Java-side and SQL-side bucket edges are identical.
     */
    public static final Instant BUCKET_ORIGIN = Instant.parse("2000-01-01T00:00:00Z");

    private static final String BUCKET_EXPR =
            "date_bin(interval '5 minutes', gp.timestamp, TIMESTAMPTZ '2000-01-01')";

    @Inject
    EntityManager entityManager;

    @Inject
    SourcePriorityConfig priorityConfig;

    /**
     * Reconcile GPS source precedence for one user over one time range.
     *
     * <p>This is the entry point to call after any ingest that could have introduced a second
     * source into an already-covered window - in practice, at Google Timeline import completion.
     * Do NOT call it per Home Assistant point: {@code POST /api/homeassistant} fires every minute
     * and this walks the whole range. The nightly {@link GpsSourceMergeScheduler} covers the live
     * feed.
     *
     * <p>Both bounds are tolerant of null: a null {@code from} means "this user's earliest point",
     * a null {@code to} means "now". The effective range is widened outward to whole bucket edges
     * so a bucket is never half-reconciled.
     *
     * @param userId user to reconcile; no-op if null
     * @param from   inclusive lower bound, or null for the user's earliest point
     * @param to     exclusive upper bound, or null for now
     */
    @Transactional
    public void reconcile(UUID userId, Instant from, Instant to) {
        if (userId == null) {
            return;
        }

        Instant effectiveTo = to == null ? Instant.now() : to;
        Instant effectiveFrom = from == null ? findEarliestPoint(userId) : from;
        if (effectiveFrom == null) {
            log.debug("No GPS points for user {}, nothing to reconcile", userId);
            return;
        }
        if (!effectiveFrom.isBefore(effectiveTo)) {
            log.debug("Empty reconciliation range for user {}: {} .. {}", userId, effectiveFrom, effectiveTo);
            return;
        }

        Instant rangeStart = floorToBucket(effectiveFrom);
        Instant rangeEnd = ceilToBucket(effectiveTo);

        long startNanos = System.nanoTime();

        List<CoverageBucket> coverage = loadCoverage(userId, rangeStart, rangeEnd);
        if (coverage.isEmpty()) {
            log.debug("No coverage for user {} in {} .. {}", userId, rangeStart, rangeEnd);
            return;
        }

        List<GpsSourceType> priority = priorityConfig.getPriority();
        int minSegmentBuckets = priorityConfig.getMinSegmentBuckets();

        List<SourceSegment> segments = assignSegments(coverage, priority, minSegmentBuckets);
        int updated = applySuppression(userId, segments);

        log.info("Reconciled GPS sources for user {} over {} .. {}: {} buckets, {} segments, {} rows changed ({} ms)",
                userId, rangeStart, rangeEnd, coverage.size(), segments.size(), updated,
                Duration.ofNanos(System.nanoTime() - startNanos).toMillis());
    }

    // ------------------------------------------------------------------------------------------
    // 1. coverage
    // ------------------------------------------------------------------------------------------

    /**
     * Load which sources cover which 5 minute bucket, computed on the fly.
     *
     * <p>There is deliberately no materialised coverage table: a second source of truth alongside
     * {@code gps_points} would drift the first time anything wrote points without going through
     * the reconciler. {@code date_bin} + {@code GROUP BY} rides the existing
     * {@code (user_id, timestamp)} index, and the touched range is small (48 hours = 576 buckets
     * for the scheduler, one export's span for an import).
     *
     * <p>Buckets with no points at all do not appear in the result. Sources whose enum name is not
     * recognised (an older row written by a since-renamed integration) are skipped.
     *
     * @return coverage buckets in ascending bucket order
     */
    @SuppressWarnings("unchecked")
    List<CoverageBucket> loadCoverage(UUID userId, Instant rangeStart, Instant rangeEnd) {
        List<Object[]> rows = entityManager.createNativeQuery(
                        "SELECT " + BUCKET_EXPR + " AS bucket_start, gp.source_type " +
                                "FROM gps_points gp " +
                                "WHERE gp.user_id = :userId " +
                                "  AND gp.timestamp >= :rangeStart " +
                                "  AND gp.timestamp < :rangeEnd " +
                                "  AND gp.source_type IS NOT NULL " +
                                "GROUP BY 1, 2 " +
                                "ORDER BY 1, 2")
                .setParameter("userId", userId)
                .setParameter("rangeStart", rangeStart)
                .setParameter("rangeEnd", rangeEnd)
                .getResultList();

        // LinkedHashMap: the query is already ordered by bucket, so insertion order is bucket order.
        Map<Instant, Set<GpsSourceType>> byBucket = new LinkedHashMap<>();
        for (Object[] row : rows) {
            Instant bucketStart = TimestampUtils.getInstantSafe(row[0]);
            GpsSourceType sourceType = parseSourceType(row[1]);
            if (bucketStart == null || sourceType == null) {
                continue;
            }
            byBucket.computeIfAbsent(bucketStart, k -> new LinkedHashSet<>()).add(sourceType);
        }

        List<CoverageBucket> coverage = new ArrayList<>(byBucket.size());
        byBucket.forEach((bucketStart, sources) -> coverage.add(new CoverageBucket(bucketStart, sources)));
        return coverage;
    }

    private static GpsSourceType parseSourceType(Object raw) {
        if (raw == null) {
            return null;
        }
        try {
            return GpsSourceType.valueOf(raw.toString().trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            log.warn("Unrecognised gps_points.source_type '{}', excluded from source merge", raw);
            return null;
        }
    }

    // ------------------------------------------------------------------------------------------
    // 2. segment assignment - PURE, no DB, no CDI
    // ------------------------------------------------------------------------------------------

    /**
     * Elect a winning source per bucket, then coalesce short runs.
     *
     * <p>This method is intentionally {@code static} and free of any database or CDI dependency so
     * the whole decision layer can be unit tested with a hand-written bucket list. Everything that
     * makes the merge subtle - gap fill, tie breaking, hysteresis - happens here.
     *
     * <h3>Winner election</h3>
     * The winner of a bucket is the highest priority source present in it. Gap fill is emergent
     * rather than special-cased: a bucket Google does not occupy is simply won by whoever does.
     *
     * <h3>Hysteresis</h3>
     * Raw per-bucket winners flap at edges. Google emits at :00 and :05; a Home Assistant ping
     * landing at :02:30 produces GOOGLE / HOME_ASSISTANT / GOOGLE, and the stay detector reads that
     * one-bucket island as a trip out and back. So any run shorter than {@code minSegmentBuckets}
     * is absorbed into a neighbour before suppression is written.
     *
     * <p>Absorption is iterative rather than single-pass, so the result does not depend on scan
     * direction. Each round sacrifices the shortest offending run, breaking ties toward the lower
     * priority source and then toward the earlier run; the neighbour that absorbs it is the longer
     * one, breaking ties toward the higher priority source and then toward the earlier run. A
     * single run is never absorbed - there is nothing to absorb it into - so a Google-only or
     * HA-only day survives regardless of its length.
     *
     * @param buckets           coverage buckets, any order; empty and null-ish entries are ignored
     * @param priority          source order, highest priority first; sources absent from the list
     *                          rank below every listed source
     * @param minSegmentBuckets minimum surviving run length, in buckets; values below 1 are treated
     *                          as 1 (no hysteresis)
     * @return segments in ascending bucket order, covering every non-empty input bucket exactly once
     */
    public static List<SourceSegment> assignSegments(List<CoverageBucket> buckets,
                                                     List<GpsSourceType> priority,
                                                     int minSegmentBuckets) {
        if (buckets == null || buckets.isEmpty()) {
            return List.of();
        }

        List<CoverageBucket> ordered = buckets.stream()
                .filter(b -> b != null && b.bucketStart() != null && !b.sources().isEmpty())
                .sorted(Comparator.comparing(CoverageBucket::bucketStart))
                .toList();
        if (ordered.isEmpty()) {
            return List.of();
        }

        List<GpsSourceType> effectivePriority = priority == null || priority.isEmpty()
                ? SourcePriorityConfig.parsePriority(null)
                : priority;

        // Raw per-bucket winners.
        GpsSourceType[] winners = new GpsSourceType[ordered.size()];
        for (int i = 0; i < ordered.size(); i++) {
            winners[i] = pickWinner(ordered.get(i).sources(), effectivePriority);
        }

        int minLength = Math.max(1, minSegmentBuckets);

        // Iteratively absorb the shortest offending run until every run is long enough, or only one
        // run remains. Each pass rebuilds runs from the winner array, which keeps the merge logic to
        // a single well-understood operation (rewrite a slice of `winners`).
        List<int[]> runs = buildRuns(winners);
        while (runs.size() > 1) {
            int weakest = indexOfWeakestRunBelow(runs, minLength, winners, effectivePriority);
            if (weakest < 0) {
                break;
            }

            int[] weak = runs.get(weakest);
            int[] previous = weakest > 0 ? runs.get(weakest - 1) : null;
            int[] next = weakest < runs.size() - 1 ? runs.get(weakest + 1) : null;
            int[] absorber = chooseAbsorber(previous, next, winners, effectivePriority);

            GpsSourceType absorbingSource = winners[absorber[0]];
            for (int i = weak[0]; i <= weak[1]; i++) {
                winners[i] = absorbingSource;
            }

            runs = buildRuns(winners);
        }

        List<SourceSegment> segments = new ArrayList<>(runs.size());
        for (int[] run : runs) {
            List<Instant> bucketStarts = new ArrayList<>(run[1] - run[0] + 1);
            for (int i = run[0]; i <= run[1]; i++) {
                bucketStarts.add(ordered.get(i).bucketStart());
            }
            segments.add(new SourceSegment(winners[run[0]], bucketStarts));
        }
        return segments;
    }

    /**
     * Highest priority source present in the bucket. A source missing from the priority list ranks
     * below every listed source; among such sources the first encountered wins, which is stable
     * because {@link CoverageBucket} preserves insertion order and the loader inserts in the SQL's
     * {@code ORDER BY source_type} order.
     */
    private static GpsSourceType pickWinner(Set<GpsSourceType> sources, List<GpsSourceType> priority) {
        GpsSourceType best = null;
        int bestRank = Integer.MAX_VALUE;
        for (GpsSourceType source : sources) {
            int rank = priority.indexOf(source);
            if (rank < 0) {
                rank = priority.size();
            }
            if (best == null || rank < bestRank) {
                best = source;
                bestRank = rank;
            }
        }
        return best;
    }

    /**
     * Split the winner array into maximal runs of equal source. Each entry is
     * {@code {startIndexInclusive, endIndexInclusive}}.
     */
    private static List<int[]> buildRuns(GpsSourceType[] winners) {
        List<int[]> runs = new ArrayList<>();
        for (int i = 0; i < winners.length; i++) {
            if (!runs.isEmpty() && winners[runs.getLast()[0]] == winners[i]) {
                runs.getLast()[1] = i;
            } else {
                runs.add(new int[]{i, i});
            }
        }
        return runs;
    }

    /**
     * Which too-short run gets absorbed next: the shortest, then the one owned by the LOWER priority
     * source, then the earliest. Sacrificing the low-priority run first matters in the fully
     * interleaved case - Google at :00/:05 and Home Assistant at :02:30 across a whole hour produces
     * nothing but one-bucket runs, and this rule resolves that toward the authoritative source
     * instead of toward whichever run happened to sit at the left edge.
     *
     * @return index into {@code runs}, or -1 when every run is already long enough
     */
    private static int indexOfWeakestRunBelow(List<int[]> runs, int minLength,
                                              GpsSourceType[] winners, List<GpsSourceType> priority) {
        int weakest = -1;
        int weakestLength = Integer.MAX_VALUE;
        int weakestRank = Integer.MIN_VALUE;
        for (int i = 0; i < runs.size(); i++) {
            int length = runs.get(i)[1] - runs.get(i)[0] + 1;
            if (length >= minLength) {
                continue;
            }
            int rank = rankOf(winners[runs.get(i)[0]], priority);
            if (weakest < 0 || length < weakestLength || (length == weakestLength && rank > weakestRank)) {
                weakest = i;
                weakestLength = length;
                weakestRank = rank;
            }
        }
        return weakest;
    }

    /**
     * Pick which neighbouring run swallows a too-short run: the longer neighbour, then the higher
     * priority source, then the earlier run. At least one neighbour always exists, because the
     * caller only reaches here when more than one run is present.
     */
    private static int[] chooseAbsorber(int[] previous, int[] next,
                                        GpsSourceType[] winners, List<GpsSourceType> priority) {
        if (previous == null) {
            return next;
        }
        if (next == null) {
            return previous;
        }

        int previousLength = previous[1] - previous[0] + 1;
        int nextLength = next[1] - next[0] + 1;
        if (previousLength != nextLength) {
            return previousLength > nextLength ? previous : next;
        }

        int previousRank = rankOf(winners[previous[0]], priority);
        int nextRank = rankOf(winners[next[0]], priority);
        if (previousRank != nextRank) {
            return previousRank < nextRank ? previous : next;
        }

        return previous;
    }

    private static int rankOf(GpsSourceType source, List<GpsSourceType> priority) {
        int rank = priority.indexOf(source);
        return rank < 0 ? priority.size() : rank;
    }

    // ------------------------------------------------------------------------------------------
    // 3. suppression
    // ------------------------------------------------------------------------------------------

    /**
     * Write the segment decisions to {@code gps_points.suppressed}.
     *
     * <p>One UPDATE per segment, over the segment's time span rather than over its individual
     * buckets. That is equivalent and far cheaper: the span can only contain buckets belonging to
     * this segment plus buckets that hold no points at all, and the latter have no rows to update.
     *
     * <p>The statement is idempotent and writes only genuine changes - the trailing
     * {@code suppressed IS DISTINCT FROM ...} predicate keeps a re-run from dirtying pages and
     * bloating the table when nothing has actually changed.
     *
     * @return number of rows whose flag actually flipped
     */
    int applySuppression(UUID userId, List<SourceSegment> segments) {
        int updated = 0;
        for (SourceSegment segment : segments) {
            Instant segmentStart = segment.startBucket();
            Instant segmentEnd = segment.endBucket().plus(BUCKET);

            updated += entityManager.createNativeQuery(
                            "UPDATE gps_points " +
                                    "SET suppressed = (source_type <> :winner) " +
                                    "WHERE user_id = :userId " +
                                    "  AND timestamp >= :segmentStart " +
                                    "  AND timestamp < :segmentEnd " +
                                    "  AND suppressed IS DISTINCT FROM (source_type <> :winner)")
                    .setParameter("userId", userId)
                    .setParameter("winner", segment.source().name())
                    .setParameter("segmentStart", segmentStart)
                    .setParameter("segmentEnd", segmentEnd)
                    .executeUpdate();
        }
        return updated;
    }

    // ------------------------------------------------------------------------------------------
    // helpers
    // ------------------------------------------------------------------------------------------

    private Instant findEarliestPoint(UUID userId) {
        Object earliest = entityManager.createNativeQuery(
                        "SELECT MIN(gp.timestamp) FROM gps_points gp WHERE gp.user_id = :userId")
                .setParameter("userId", userId)
                .getSingleResult();
        return TimestampUtils.getInstantSafe(earliest);
    }

    /**
     * Java-side equivalent of {@code date_bin(interval '5 minutes', ts, TIMESTAMPTZ '2000-01-01')}.
     */
    public static Instant floorToBucket(Instant timestamp) {
        long bucketSeconds = BUCKET.getSeconds();
        long offset = timestamp.getEpochSecond() - BUCKET_ORIGIN.getEpochSecond();
        long floored = Math.floorDiv(offset, bucketSeconds) * bucketSeconds;
        return BUCKET_ORIGIN.plusSeconds(floored);
    }

    /**
     * Smallest bucket edge at or after {@code timestamp}.
     */
    public static Instant ceilToBucket(Instant timestamp) {
        Instant floored = floorToBucket(timestamp);
        return floored.equals(timestamp) ? floored : floored.plus(BUCKET);
    }
}
