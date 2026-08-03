package org.github.tess1o.geopulse.gps.merge;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.github.tess1o.geopulse.admin.service.SystemSettingsService;
import org.github.tess1o.geopulse.shared.gps.GpsSourceType;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;

/**
 * FORK (WS-2): source precedence for the cross-source GPS merge.
 *
 * <p>Reads the {@code gps.source-priority} system setting as CSV, highest priority first. Follows
 * the same DB-first / env-fallback pattern as {@link org.github.tess1o.geopulse.geocoding.config.GeocodingConfigurationService}
 * by delegating to {@link SystemSettingsService}.
 *
 * <p>The returned list is always a total order over every {@link GpsSourceType}: entries named in
 * the setting come first in the order given, and anything unknown or absent sorts last, preserving
 * the enum declaration order among themselves. That means a new upstream source type can never
 * make the reconciler throw or behave non-deterministically - it just ranks below everything the
 * operator explicitly listed.
 */
@ApplicationScoped
@Transactional(Transactional.TxType.REQUIRES_NEW)
@Slf4j
public class SourcePriorityConfig {

    /** System settings key holding the CSV priority list. */
    public static final String PRIORITY_SETTING_KEY = "gps.source-priority";

    /** System settings key holding the hysteresis window, in 5 minute buckets. */
    public static final String MIN_SEGMENT_BUCKETS_SETTING_KEY = "gps.merge.min-segment-buckets";

    /**
     * Google Timeline first: its visits are Google's own already-clustered stay decisions, which is
     * exactly the behaviour this fork wants to reproduce. Home Assistant next: real fixes, and the
     * gap-filler for everything Google has not exported yet.
     */
    public static final String DEFAULT_PRIORITY_CSV =
            "GOOGLE_TIMELINE,HOME_ASSISTANT,MOBILE_APP,OWNTRACKS,GPSLOGGER,OVERLAND,TRACCAR,DAWARICH,GPX,GEOJSON,CSV,COLOTA,MANUAL";

    /** 3 buckets = 15 minutes. Shorter runs than this are treated as edge flap, not as a real move. */
    public static final int DEFAULT_MIN_SEGMENT_BUCKETS = 3;

    private final SystemSettingsService settingsService;

    @Inject
    public SourcePriorityConfig(SystemSettingsService settingsService) {
        this.settingsService = settingsService;
    }

    /**
     * @return every {@link GpsSourceType}, highest priority first.
     */
    public List<GpsSourceType> getPriority() {
        return parsePriority(settingsService.getString(PRIORITY_SETTING_KEY));
    }

    /**
     * @return the minimum length, in buckets, of a segment that is allowed to survive hysteresis.
     */
    public int getMinSegmentBuckets() {
        int configured = settingsService.getInteger(MIN_SEGMENT_BUCKETS_SETTING_KEY);
        if (configured < 1) {
            log.debug("Invalid {} = {}, falling back to {}",
                    MIN_SEGMENT_BUCKETS_SETTING_KEY, configured, DEFAULT_MIN_SEGMENT_BUCKETS);
            return DEFAULT_MIN_SEGMENT_BUCKETS;
        }
        return configured;
    }

    /**
     * Pure CSV parser, exposed static so it can be unit tested without CDI or a database.
     *
     * <p>Blank, null, or entirely unrecognised input yields {@link #DEFAULT_PRIORITY_CSV}. Unknown
     * tokens inside an otherwise valid list are skipped with a warning rather than failing the
     * whole reconciliation.
     *
     * @param csv comma separated {@link GpsSourceType} names, highest priority first
     * @return a total order over every source type, highest priority first, no duplicates
     */
    public static List<GpsSourceType> parsePriority(String csv) {
        LinkedHashSet<GpsSourceType> ordered = new LinkedHashSet<>();
        appendCsv(ordered, csv, true);

        if (ordered.isEmpty()) {
            appendCsv(ordered, DEFAULT_PRIORITY_CSV, false);
        }

        // Anything the operator did not mention ranks last, in enum declaration order.
        for (GpsSourceType type : GpsSourceType.values()) {
            ordered.add(type);
        }

        return List.copyOf(ordered);
    }

    private static void appendCsv(LinkedHashSet<GpsSourceType> target, String csv, boolean warnOnUnknown) {
        if (csv == null || csv.isBlank()) {
            return;
        }
        for (String token : csv.split(",")) {
            String name = token.trim().toUpperCase(Locale.ROOT);
            if (name.isEmpty()) {
                continue;
            }
            try {
                target.add(GpsSourceType.valueOf(name));
            } catch (IllegalArgumentException e) {
                if (warnOnUnknown) {
                    log.warn("Ignoring unknown GPS source type '{}' in {}", name, PRIORITY_SETTING_KEY);
                }
            }
        }
    }
}
