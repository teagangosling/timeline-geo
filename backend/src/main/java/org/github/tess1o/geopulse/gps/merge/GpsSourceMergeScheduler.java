package org.github.tess1o.geopulse.gps.merge;

import io.quarkus.scheduler.Scheduled;
import io.smallrye.common.annotation.Blocking;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.github.tess1o.geopulse.importdata.service.ImportJobService;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * FORK (WS-2): nightly cross-source GPS reconciliation for the live feeds.
 *
 * <p>Google Timeline imports reconcile their own range at import completion. This job exists for
 * the other direction: Home Assistant keeps posting into windows that a past Google export already
 * claimed, so the trailing window has to be re-decided periodically.
 *
 * <p>It is deliberately NOT wired to the Home Assistant ingest endpoint. {@code POST /api/homeassistant}
 * fires roughly every minute; reconciling per point would re-scan the whole window 1440 times a day
 * to change almost nothing.
 *
 * <p>Schedule: 04:20 daily by default. Existing cron-scheduled work sits at 03:30
 * ({@code WeatherTargetCleanupJob}); the interval-based jobs (coverage 2h, badges, weather
 * discovery, boat maintenance) are spread by their own delays, so an early-morning slot with no
 * fixed neighbour keeps this off their peaks.
 */
@ApplicationScoped
@Slf4j
public class GpsSourceMergeScheduler {

    @Inject
    GpsSourceMergeService mergeService;

    @Inject
    ImportJobService importJobService;

    @Inject
    EntityManager entityManager;

    @ConfigProperty(name = "geopulse.gps.merge.enabled", defaultValue = "true")
    boolean enabled;

    @ConfigProperty(name = "geopulse.gps.merge.scheduler.trailing-hours", defaultValue = "48")
    int trailingHours;

    @Scheduled(cron = "${geopulse.gps.merge.scheduler.cron:0 20 4 * * ?}", identity = "gps-source-merge")
    @Blocking
    public void reconcileRecentWindow() {
        if (!enabled) {
            return;
        }

        Instant to = Instant.now();
        Instant from = to.minus(Duration.ofHours(Math.max(1, trailingHours)));

        List<UUID> userIds = findUsersWithMixedSources(from);
        if (userIds.isEmpty()) {
            return;
        }

        log.info("Reconciling GPS sources for {} users over the trailing {}h", userIds.size(), trailingHours);

        for (UUID userId : userIds) {
            try {
                // An in-flight import is still writing into this window; reconciling now would only
                // be redone by the import's own completion call.
                if (importJobService.hasActiveImportJob(userId)) {
                    log.debug("Skipping GPS source merge for user {} - import in progress", userId);
                    continue;
                }
                mergeService.reconcile(userId, from, to);
            } catch (Exception e) {
                // One bad user must not abort the run for everyone else.
                log.warn("GPS source merge failed for user {}: {}", userId, e.getMessage(), e);
            }
        }
    }

    /**
     * Only users whose trailing window actually contains more than one source need reconciling.
     * Single-source users are the overwhelming majority and there is nothing to decide for them.
     */
    @SuppressWarnings("unchecked")
    private List<UUID> findUsersWithMixedSources(Instant from) {
        List<Object> rows = entityManager.createNativeQuery("""
                        SELECT gp.user_id
                        FROM gps_points gp
                        WHERE gp.timestamp >= :from
                          AND gp.source_type IS NOT NULL
                        GROUP BY gp.user_id
                        HAVING COUNT(DISTINCT gp.source_type) > 1
                        """)
                .setParameter("from", from)
                .getResultList();

        return rows.stream()
                .map(UUID.class::cast)
                .toList();
    }
}
