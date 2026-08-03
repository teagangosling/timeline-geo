package org.github.tess1o.geopulse.gps.merge.model;

import org.github.tess1o.geopulse.shared.gps.GpsSourceType;

import java.time.Instant;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * FORK (WS-2): which GPS sources produced at least one point inside one time bucket.
 *
 * <p>Coverage is presence, not volume: a single Home Assistant ping is real coverage of that
 * bucket. There is deliberately no count threshold, because Google Timeline "visit" points are
 * synthesised at a fixed 5 minute cadence and their count carries no information.
 *
 * <p>{@code bucketStart} is the inclusive lower edge of the bucket, as produced by
 * {@code date_bin('5 minutes', timestamp, TIMESTAMPTZ '2000-01-01')}.
 *
 * @param bucketStart inclusive start of the bucket
 * @param sources     sources present in the bucket; never empty for a bucket that exists
 */
public record CoverageBucket(Instant bucketStart, Set<GpsSourceType> sources) {

    public CoverageBucket {
        sources = sources == null ? Set.of() : Set.copyOf(sources);
    }

    /**
     * Convenience factory for tests and for the loader, which accumulates sources incrementally.
     */
    public static CoverageBucket of(Instant bucketStart, GpsSourceType... sources) {
        Set<GpsSourceType> set = new LinkedHashSet<>();
        if (sources != null) {
            for (GpsSourceType source : sources) {
                if (source != null) {
                    set.add(source);
                }
            }
        }
        return new CoverageBucket(bucketStart, set);
    }
}
