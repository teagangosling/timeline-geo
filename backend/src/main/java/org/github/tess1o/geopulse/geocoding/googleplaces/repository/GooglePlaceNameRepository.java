package org.github.tess1o.geopulse.geocoding.googleplaces.repository;

import io.quarkus.hibernate.orm.panache.PanacheRepositoryBase;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.EntityManager;
import org.github.tess1o.geopulse.geocoding.googleplaces.model.GooglePlaceNameEntity;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Panache repository over the permanent Google place-name cache.
 */
@ApplicationScoped
public class GooglePlaceNameRepository implements PanacheRepositoryBase<GooglePlaceNameEntity, String> {

    /**
     * Cached rows are inserted with ON CONFLICT DO NOTHING: two timeline generations can resolve the
     * same placeID concurrently, and the loser must not blow up with a duplicate-key error.
     */
    private static final String UPSERT_SQL = """
            INSERT INTO google_place_names (place_id, name, source, resolved_at)
            VALUES (:placeId, CAST(:name AS text), :source, now())
            ON CONFLICT (place_id) DO NOTHING
            """;

    private final EntityManager entityManager;

    @Inject
    public GooglePlaceNameRepository(EntityManager entityManager) {
        this.entityManager = entityManager;
    }

    /**
     * Look up every cached placeID in one query.
     *
     * @return map of placeID to cached name. A key present with a null value is a confirmed miss;
     * a key that is absent has never been looked up.
     */
    public Map<String, String> findCachedNames(Collection<String> placeIds) {
        Map<String, String> cached = new HashMap<>();
        if (placeIds == null || placeIds.isEmpty()) {
            return cached;
        }

        List<GooglePlaceNameEntity> rows = list("placeId in ?1", List.copyOf(placeIds));
        for (GooglePlaceNameEntity row : rows) {
            cached.put(row.getPlaceId(), row.getName());
        }
        return cached;
    }

    /**
     * Cache a resolution result. A null name records a confirmed miss so it is never re-billed.
     */
    public void cache(String placeId, String name, String source) {
        entityManager.createNativeQuery(UPSERT_SQL)
                .setParameter("placeId", placeId)
                .setParameter("name", name)
                .setParameter("source", source)
                .executeUpdate();
    }
}
