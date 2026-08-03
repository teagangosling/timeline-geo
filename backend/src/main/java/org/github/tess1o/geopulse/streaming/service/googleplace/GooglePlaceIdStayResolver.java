package org.github.tess1o.geopulse.streaming.service.googleplace;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.github.tess1o.geopulse.streaming.model.domain.Stay;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Works out which Google placeID, if any, a detected stay corresponds to.
 * <p>
 * The import path stamps every synthetic visit point with the placeID Google gave that visit
 * ({@code gps_points.google_place_id}, a generated column over {@code telemetry}). Stay detection
 * runs afterwards and knows nothing about visits, so a stay's time window has to be matched back
 * onto those points here.
 * <p>
 * The match is <b>modal</b>, not first-wins: GeoPulse's stay detector clusters on distance and
 * duration, so a single stay routinely spans two adjacent Google visits at the same address (a shop
 * and the cafe next door, or one visit split by a brief signal loss). Taking the first point's
 * placeID would make the resulting name depend on which visit happened to start first. The most
 * frequent placeID across the window is the one the user actually spent the stay at.
 * <p>
 * Ties break to the lexically lowest placeID purely for determinism - regenerating a timeline twice
 * must not produce two different names.
 */
@ApplicationScoped
@Slf4j
public class GooglePlaceIdStayResolver {

    /**
     * Stays per native query. Each stay contributes two bind parameters, so this keeps a chunk well
     * under any driver parameter limit while still amortising the round trip.
     */
    static final int CHUNK_SIZE = 500;

    private final EntityManager entityManager;

    @Inject
    public GooglePlaceIdStayResolver(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Resolve a placeID for each stay that has one.
     *
     * @param userId owner of the stays and of the GPS points
     * @param stays  stays to resolve, in order
     * @return map of index into {@code stays} to the modal placeID for that stay. Stays with no
     * Google-derived points in their window are absent.
     */
    public Map<Integer, String> resolveForStays(UUID userId, List<Stay> stays) {
        Map<Integer, String> resolved = new HashMap<>();
        if (userId == null || stays == null || stays.isEmpty()) {
            return resolved;
        }

        for (int offset = 0; offset < stays.size(); offset += CHUNK_SIZE) {
            int end = Math.min(offset + CHUNK_SIZE, stays.size());
            resolved.putAll(resolveChunk(userId, stays, offset, end));
        }

        if (!resolved.isEmpty()) {
            log.debug("Resolved Google placeIDs for {} of {} stays", resolved.size(), stays.size());
        }
        return resolved;
    }

    private Map<Integer, String> resolveChunk(UUID userId, List<Stay> stays, int fromIndex, int toIndex) {
        StringBuilder values = new StringBuilder();
        Map<String, Instant> timeParams = new HashMap<>();
        List<Integer> indexes = new ArrayList<>();

        for (int i = fromIndex; i < toIndex; i++) {
            Stay stay = stays.get(i);
            if (stay == null || stay.getStartTime() == null || stay.getDuration() == null) {
                continue;
            }

            Instant start = stay.getStartTime();
            // ts_end is exclusive: a stay's window is [start, start + duration).
            Instant end = start.plusSeconds(stay.getDuration().toSeconds());

            if (!values.isEmpty()) {
                values.append(", ");
            }
            values.append("(").append(i)
                    .append(", CAST(:s").append(i).append(" AS timestamptz)")
                    .append(", CAST(:e").append(i).append(" AS timestamptz))");

            timeParams.put("s" + i, start);
            timeParams.put("e" + i, end);
            indexes.add(i);
        }

        if (indexes.isEmpty()) {
            return Map.of();
        }

        // One pass over the window set: count how many Google-stamped points fall inside each
        // stay's window, grouped by placeID. The modal pick happens in Java so the tie-break rule
        // stays explicit and testable.
        String sql = """
                WITH windows(idx, ts_start, ts_end) AS (VALUES %s)
                SELECT w.idx, g.google_place_id, count(*) AS n
                FROM windows w
                JOIN gps_points g ON g.user_id = :userId
                 AND g.timestamp >= w.ts_start AND g.timestamp < w.ts_end
                 AND g.google_place_id IS NOT NULL
                GROUP BY w.idx, g.google_place_id
                """.formatted(values);

        var query = entityManager.createNativeQuery(sql).setParameter("userId", userId);
        timeParams.forEach(query::setParameter);

        @SuppressWarnings("unchecked")
        List<Object[]> rows = query.getResultList();

        return selectModalPlaceIds(rows);
    }

    /**
     * Reduce {@code (stayIndex, placeId, count)} rows to one placeID per stay.
     * <p>
     * Highest count wins; on a tie the lexically lowest placeID wins, so the same input always
     * produces the same output regardless of row order.
     */
    static Map<Integer, String> selectModalPlaceIds(List<Object[]> rows) {
        Map<Integer, String> best = new HashMap<>();
        Map<Integer, Long> bestCount = new HashMap<>();

        if (rows == null) {
            return best;
        }

        for (Object[] row : rows) {
            if (row == null || row.length < 3 || row[0] == null || row[1] == null) {
                continue;
            }

            int idx = ((Number) row[0]).intValue();
            String placeId = row[1].toString();
            long count = row[2] instanceof Number number ? number.longValue() : 0L;

            Long currentCount = bestCount.get(idx);
            if (currentCount == null
                    || count > currentCount
                    || (count == currentCount && placeId.compareTo(best.get(idx)) < 0)) {
                best.put(idx, placeId);
                bestCount.put(idx, count);
            }
        }

        return best;
    }
}
