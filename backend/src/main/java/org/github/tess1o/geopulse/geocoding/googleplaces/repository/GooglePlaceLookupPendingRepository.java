package org.github.tess1o.geopulse.geocoding.googleplaces.repository;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;

/**
 * Queue of placeIDs that could not be resolved because the month's Google quota was already spent.
 * Drained by
 * {@link org.github.tess1o.geopulse.geocoding.googleplaces.service.PendingGooglePlaceLookupScheduler}.
 * <p>
 * Deliberately a plain native-SQL repository rather than a JPA entity: the table is a work queue,
 * not part of the domain model, and nothing ever holds a managed reference to a row.
 */
@ApplicationScoped
@Slf4j
public class GooglePlaceLookupPendingRepository {

    private static final String QUEUE_SQL = """
            INSERT INTO google_place_lookup_pending (place_id, latitude, longitude)
            VALUES (:placeId, CAST(:latitude AS double precision), CAST(:longitude AS double precision))
            ON CONFLICT (place_id) DO NOTHING
            """;

    private static final String SELECT_SQL = """
            SELECT place_id FROM google_place_lookup_pending ORDER BY queued_at
            """;

    private static final String DELETE_SQL = """
            DELETE FROM google_place_lookup_pending WHERE place_id = :placeId
            """;

    private static final String COUNT_SQL = """
            SELECT count(*) FROM google_place_lookup_pending
            """;

    private final EntityManager entityManager;

    @Inject
    public GooglePlaceLookupPendingRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Queue a placeID for a later retry. Coordinates are optional - this fork does not port the
     * retired app's Nominatim coordinate fallback (GeoPulse's own geocoder chain is the fallback),
     * so they are recorded only for diagnostics and are normally null.
     */
    public void queue(String placeId, Double latitude, Double longitude) {
        entityManager.createNativeQuery(QUEUE_SQL)
                .setParameter("placeId", placeId)
                .setParameter("latitude", latitude)
                .setParameter("longitude", longitude)
                .executeUpdate();
    }

    /**
     * Every queued placeID, oldest first.
     */
    @SuppressWarnings("unchecked")
    public List<String> findAllPlaceIds() {
        List<Object> rows = entityManager.createNativeQuery(SELECT_SQL).getResultList();
        List<String> placeIds = new ArrayList<>(rows.size());
        for (Object row : rows) {
            if (row != null) {
                placeIds.add(row.toString());
            }
        }
        return placeIds;
    }

    public void delete(String placeId) {
        entityManager.createNativeQuery(DELETE_SQL)
                .setParameter("placeId", placeId)
                .executeUpdate();
    }

    public long count() {
        Object result = entityManager.createNativeQuery(COUNT_SQL).getSingleResult();
        return result instanceof Number number ? number.longValue() : 0L;
    }
}
