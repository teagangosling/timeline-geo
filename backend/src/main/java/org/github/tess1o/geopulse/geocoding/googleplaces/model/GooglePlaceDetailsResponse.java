package org.github.tess1o.geopulse.geocoding.googleplaces.model;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import io.quarkus.runtime.annotations.RegisterForReflection;
import lombok.Data;

/**
 * Response from the Places API (New) Place Details endpoint, restricted to the {@code displayName}
 * field mask this fork requests. Everything else Google might return is ignored.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
@RegisterForReflection
public class GooglePlaceDetailsResponse {

    @JsonProperty("displayName")
    private DisplayName displayName;

    /**
     * Convenience accessor: the human-readable POI name, or null when Google returned no name.
     */
    public String getDisplayNameText() {
        return displayName == null ? null : displayName.getText();
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    @RegisterForReflection
    public static class DisplayName {

        @JsonProperty("text")
        private String text;

        @JsonProperty("languageCode")
        private String languageCode;
    }
}
