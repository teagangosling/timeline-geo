package org.github.tess1o.geopulse.geocoding.googleplaces.service;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.transaction.Transactional;
import jakarta.ws.rs.WebApplicationException;
import lombok.extern.slf4j.Slf4j;
import org.eclipse.microprofile.rest.client.inject.RestClient;
import org.github.tess1o.geopulse.geocoding.googleplaces.client.GooglePlacesRestClient;
import org.github.tess1o.geopulse.geocoding.googleplaces.config.GooglePlacesConfiguration;
import org.github.tess1o.geopulse.geocoding.googleplaces.model.GooglePlaceDetailsResponse;
import org.github.tess1o.geopulse.geocoding.googleplaces.model.GooglePlaceNameEntity;
import org.github.tess1o.geopulse.geocoding.googleplaces.repository.GooglePlaceLookupPendingRepository;
import org.github.tess1o.geopulse.geocoding.googleplaces.repository.GooglePlaceNameRepository;

import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

/**
 * Turns Google placeIDs into POI names, spending as little as possible doing it.
 * <p>
 * Three outcomes per placeID:
 * <ul>
 *   <li><b>Cache hit</b> - returned immediately, no API call. This includes a cached NULL, which is
 *   a confirmed miss ("Google was asked, Google had nothing"). Re-billing a known miss is the most
 *   expensive bug this class can have, so presence of the cache row, not nullness of the name, is
 *   the hit test.</li>
 *   <li><b>Miss with quota available</b> - one Place Details call, result cached either way, name
 *   returned if there was one.</li>
 *   <li><b>Miss with quota exhausted</b> - queued in {@code google_place_lookup_pending} and
 *   omitted from the result. The caller falls back to GeoPulse's normal geocoder chain, and
 *   {@link PendingGooglePlaceLookupScheduler} backfills the real name later. Nothing is cached, so
 *   the lookup is not lost.</li>
 * </ul>
 * <p>
 * Unlike the retired Python app this does <em>not</em> fall back to Nominatim on a Google miss;
 * GeoPulse already has a full geocoder chain behind this, and duplicating it here would mean two
 * competing sources of low-confidence names.
 */
@ApplicationScoped
@Slf4j
public class GooglePlaceNameResolver {

    private static final String FIELD_MASK = "displayName";

    private final GooglePlacesRestClient placesClient;
    private final GooglePlacesConfiguration configuration;
    private final GooglePlacesQuotaService quotaService;
    private final GooglePlaceNameRepository nameRepository;
    private final GooglePlaceLookupPendingRepository pendingRepository;

    @Inject
    public GooglePlaceNameResolver(@RestClient GooglePlacesRestClient placesClient,
                                   GooglePlacesConfiguration configuration,
                                   GooglePlacesQuotaService quotaService,
                                   GooglePlaceNameRepository nameRepository,
                                   GooglePlaceLookupPendingRepository pendingRepository) {
        this.placesClient = placesClient;
        this.configuration = configuration;
        this.quotaService = quotaService;
        this.nameRepository = nameRepository;
        this.pendingRepository = pendingRepository;
    }

    /**
     * Resolve a batch of placeIDs to names.
     *
     * @return map of placeID to name, containing only the placeIDs that resolved to an actual name.
     * A placeID that is a confirmed miss, that failed, or that ran out of quota is simply absent -
     * callers treat absence as "no Google name, use the normal chain".
     */
    @Transactional
    public Map<String, String> resolveNames(Collection<String> placeIds) {
        Map<String, String> resolved = new HashMap<>();

        Set<String> distinct = distinctNonBlank(placeIds);
        if (distinct.isEmpty()) {
            return resolved;
        }

        if (!configuration.isUsable()) {
            log.debug("Google Places lookup skipped for {} placeIDs - integration disabled or no API key",
                    distinct.size());
            return resolved;
        }

        Map<String, String> cached = nameRepository.findCachedNames(distinct);

        int apiCalls = 0;
        int queued = 0;
        for (String placeId : distinct) {
            if (cached.containsKey(placeId)) {
                // Cache hit. A null value here is a confirmed miss - do NOT call the API.
                String name = cached.get(placeId);
                if (name != null && !name.isBlank()) {
                    resolved.put(placeId, name);
                }
                continue;
            }

            if (!quotaService.reserveRequest()) {
                pendingRepository.queue(placeId, null, null);
                queued++;
                continue;
            }

            apiCalls++;
            LookupOutcome outcome = lookup(placeId);
            switch (outcome.status()) {
                case RESOLVED -> {
                    nameRepository.cache(placeId, outcome.name(), GooglePlaceNameEntity.SOURCE_GOOGLE);
                    resolved.put(placeId, outcome.name());
                }
                // Google answered and had no name for this place. Cache the miss so it is never
                // billed again.
                case CONFIRMED_MISS ->
                        nameRepository.cache(placeId, null, GooglePlaceNameEntity.SOURCE_NONE);
                // Transient failure: the quota slot is spent (the request was made) but we learned
                // nothing, so caching would poison the entry permanently. Queue for retry instead.
                case FAILED -> {
                    pendingRepository.queue(placeId, null, null);
                    queued++;
                }
            }
        }

        log.info("Google Places resolution: {} requested, {} from cache, {} API calls, {} resolved, {} queued for retry",
                distinct.size(), distinct.size() - apiCalls - queued, apiCalls, resolved.size(), queued);

        return resolved;
    }

    /**
     * One Place Details call. Never throws - a lookup failure must not take down timeline
     * generation.
     */
    // No @Retry/@CircuitBreaker here, unlike the sibling geocoding providers: this is a private
    // call within the bean, so an interceptor would never fire, and more importantly every attempt
    // is separately billable. The durable retry path is the pending queue, not an in-process one.
    LookupOutcome lookup(String placeId) {
        try {
            GooglePlaceDetailsResponse response =
                    placesClient.getPlaceDetails(placeId, configuration.getApiKey(), FIELD_MASK);

            String name = response == null ? null : response.getDisplayNameText();
            if (name == null || name.isBlank()) {
                log.debug("Google Places returned no display name for placeID {}", placeId);
                return LookupOutcome.confirmedMiss();
            }
            return LookupOutcome.resolved(name);

        } catch (WebApplicationException e) {
            int status = e.getResponse() == null ? -1 : e.getResponse().getStatus();
            if (status == 400 || status == 404) {
                // Google is telling us this placeID is not a thing. That answer will not change.
                log.debug("Google Places rejected placeID {} with HTTP {} - caching as a miss", placeId, status);
                return LookupOutcome.confirmedMiss();
            }
            log.warn("Google Places lookup for placeID {} failed with HTTP {}: {}", placeId, status, e.getMessage());
            return LookupOutcome.failed();

        } catch (Exception e) {
            log.warn("Google Places lookup for placeID {} failed: {}", placeId, e.getMessage());
            return LookupOutcome.failed();
        }
    }

    private static Set<String> distinctNonBlank(Collection<String> placeIds) {
        Set<String> distinct = new LinkedHashSet<>();
        if (placeIds == null) {
            return distinct;
        }
        for (String placeId : placeIds) {
            if (placeId != null && !placeId.isBlank()) {
                distinct.add(placeId.trim());
            }
        }
        return distinct;
    }

    enum LookupStatus {
        /**
         * Google returned a display name.
         */
        RESOLVED,
        /**
         * Google answered definitively that there is no name here. Safe to cache forever.
         */
        CONFIRMED_MISS,
        /**
         * Something went wrong. We learned nothing, so nothing may be cached.
         */
        FAILED
    }

    record LookupOutcome(LookupStatus status, String name) {

        static LookupOutcome resolved(String name) {
            return new LookupOutcome(LookupStatus.RESOLVED, name);
        }

        static LookupOutcome confirmedMiss() {
            return new LookupOutcome(LookupStatus.CONFIRMED_MISS, null);
        }

        static LookupOutcome failed() {
            return new LookupOutcome(LookupStatus.FAILED, null);
        }
    }
}
