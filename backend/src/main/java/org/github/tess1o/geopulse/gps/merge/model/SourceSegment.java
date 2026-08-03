package org.github.tess1o.geopulse.gps.merge.model;

import org.github.tess1o.geopulse.shared.gps.GpsSourceType;

import java.time.Instant;
import java.util.List;

/**
 * FORK (WS-2): a maximal run of consecutive coverage buckets awarded to one source.
 *
 * <p>Segments are the unit that suppression is written from. They exist because raw per-bucket
 * winners flap at source boundaries - Google emits at :00 and :05, a Home Assistant ping lands at
 * :02:30, and the naive winner sequence becomes GOOGLE / HOME_ASSISTANT / GOOGLE, which the stay
 * detector reads as a phantom trip out and back. Runs shorter than the configured minimum are
 * coalesced into a neighbour before any suppression is written.
 *
 * <p>{@code buckets} holds the bucket start instants in ascending order. They are consecutive in
 * the <em>coverage list</em>, which is not the same as consecutive in time: buckets with no points
 * at all simply do not exist, so a segment may span a quiet gap. That is intentional - a gap has
 * no points to suppress either way.
 *
 * @param source  the winning source for every bucket in this segment
 * @param buckets ascending bucket start instants, at least one
 */
public record SourceSegment(GpsSourceType source, List<Instant> buckets) {

    public SourceSegment {
        buckets = buckets == null ? List.of() : List.copyOf(buckets);
    }

    public Instant startBucket() {
        return buckets.getFirst();
    }

    public Instant endBucket() {
        return buckets.getLast();
    }

    public int bucketCount() {
        return buckets.size();
    }
}
