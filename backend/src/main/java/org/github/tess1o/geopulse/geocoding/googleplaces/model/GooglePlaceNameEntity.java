package org.github.tess1o.geopulse.geocoding.googleplaces.model;

import io.quarkus.hibernate.orm.panache.PanacheEntityBase;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import lombok.ToString;

import java.time.Instant;

/**
 * Permanent cache of Google placeID to POI name.
 * <p>
 * A row with a NULL {@link #name} is a <em>confirmed miss</em>: Google was asked about this placeID
 * and had no display name for it. The presence of the row - not the nullness of the name - is what
 * counts as a cache hit, so a miss is never re-billed.
 */
@Entity
@Table(name = "google_place_names")
@Getter
@Setter
@ToString
@NoArgsConstructor
public class GooglePlaceNameEntity extends PanacheEntityBase {

    /**
     * Source marker for a name Google itself returned.
     */
    public static final String SOURCE_GOOGLE = "google";

    /**
     * Source marker for a confirmed miss - asked, nothing there.
     */
    public static final String SOURCE_NONE = "none";

    @Id
    @Column(name = "place_id", nullable = false)
    private String placeId;

    /**
     * Resolved display name, or null for a confirmed miss.
     */
    @Column(name = "name")
    private String name;

    @Column(name = "source", nullable = false, length = 16)
    private String source;

    @Column(name = "resolved_at", nullable = false)
    private Instant resolvedAt;

    public GooglePlaceNameEntity(String placeId, String name, String source) {
        this.placeId = placeId;
        this.name = name;
        this.source = source;
        this.resolvedAt = Instant.now();
    }
}
