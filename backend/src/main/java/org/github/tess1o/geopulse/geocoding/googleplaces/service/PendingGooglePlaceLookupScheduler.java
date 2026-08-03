package org.github.tess1o.geopulse.geocoding.googleplaces.service;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import jakarta.transaction.Transactional;
import lombok.extern.slf4j.Slf4j;
import org.github.tess1o.geopulse.geocoding.googleplaces.config.GooglePlacesConfiguration;
import org.github.tess1o.geopulse.geocoding.googleplaces.repository.GooglePlaceLookupPendingRepository;
import org.github.tess1o.geopulse.geocoding.googleplaces.repository.GooglePlaceNameRepository;

import java.util.List;
import java.util.Map;

/**
 * Nightly drain of the deferred Google placeID queue.
 * <p>
 * Stays whose placeID could not be resolved when their timeline was generated - because that
 * month's quota was already spent, or the API call failed - were named by GeoPulse's normal
 * geocoder chain and had their placeID queued. This job retries them once quota is available and
 * backfills the better name onto the stays that are still using the fallback.
 * <p>
 * The backfill deliberately skips any stay with a favorite: a favorite is an explicit human
 * override and always outranks a machine-derived POI name (see FORK.md, "Naming precedence").
 */
@ApplicationScoped
@Slf4j
public class PendingGooglePlaceLookupScheduler {

    /**
     * Only stays still carrying a non-favorite name are rewritten. favorite_id IS NULL is the same
     * guard the finalization path applies when Google wins.
     */
    private static final String BACKFILL_STAYS_SQL = """
            UPDATE timeline_stays
               SET location_name = :name,
                   location_source = 'GOOGLE_PLACE',
                   last_updated = now()
             WHERE google_place_id = :placeId
               AND favorite_id IS NULL
            """;

    @Inject
    GooglePlacesConfiguration configuration;

    @Inject
    GooglePlaceNameResolver nameResolver;

    @Inject
    GooglePlaceLookupPendingRepository pendingRepository;

    @Inject
    GooglePlaceNameRepository nameRepository;

    @Inject
    EntityManager entityManager;

    /**
     * Runs nightly at 03:17 UTC. Quota enforcement lives entirely in
     * {@link GooglePlacesQuotaService#reserveRequest()}, which {@link GooglePlaceNameResolver}
     * consults per placeID, so this job cannot overspend however many entries are queued - entries
     * it cannot afford simply stay queued for tomorrow.
     * <p>
     * {@code @Transactional} sits on this method rather than on a private helper: the scheduler
     * invokes it through the CDI proxy, so this is the only place the interceptor actually fires.
     * The transaction does span the HTTP calls, which is why this is a nightly job over a bounded
     * queue and not something on a request path.
     */
    @Scheduled(cron = "0 17 3 * * ?", identity = "google-place-pending-lookup")
    @Transactional
    void drainPendingLookups() {
        if (!configuration.isUsable()) {
            log.debug("Google Places pending lookup drain skipped - integration disabled or no API key");
            return;
        }

        try {
            List<String> pending = pendingRepository.findAllPlaceIds();
            if (pending.isEmpty()) {
                log.debug("Google Places pending lookup drain: queue is empty");
                return;
            }

            log.info("Draining {} pending Google placeID lookups", pending.size());

            // The resolver checks the cache before spending quota, so anything queued by a
            // transient failure that has since been resolved elsewhere costs nothing here.
            Map<String, String> names = nameResolver.resolveNames(pending);

            int backfilled = 0;
            for (Map.Entry<String, String> entry : names.entrySet()) {
                backfilled += entityManager.createNativeQuery(BACKFILL_STAYS_SQL)
                        .setParameter("name", entry.getValue())
                        .setParameter("placeId", entry.getKey())
                        .executeUpdate();
            }

            // Dequeue everything that now has a cache row - that includes confirmed misses, which
            // resolveNames does not return but which are settled for good. Leaving those queued
            // would make this job re-scan a growing pile of permanently unresolvable IDs.
            int dequeued = 0;
            for (String placeId : nameRepository.findCachedNames(pending).keySet()) {
                pendingRepository.delete(placeId);
                dequeued++;
            }

            log.info("Google Places pending lookup drain complete: {} placeIDs settled, {} stays renamed, {} still queued",
                    dequeued, backfilled, pending.size() - dequeued);

        } catch (Exception e) {
            log.error("Google Places pending lookup drain failed", e);
        }
    }
}
