package org.github.tess1o.geopulse.geocoding.googleplaces.client;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import org.eclipse.microprofile.rest.client.inject.RegisterRestClient;
import org.github.tess1o.geopulse.geocoding.googleplaces.model.GooglePlaceDetailsResponse;

/**
 * Places API (New) Place Details endpoint.
 * <p>
 * Note this is <em>not</em> the legacy {@code maps.googleapis.com/maps/api/place/details/json}
 * endpoint used by {@link org.github.tess1o.geopulse.geocoding.client.GoogleMapsRestClient}. That
 * is a separate API product and is commonly left disabled on a project; the New API is the one that
 * accepts the placeIDs a Timeline export carries.
 * <p>
 * The base URI is pinned on the annotation rather than in {@code application.properties} so this
 * fork adds no upstream configuration file edit. {@code X-Goog-FieldMask} is mandatory - the New
 * API rejects a request without one - and asking only for {@code displayName} keeps this on the
 * cheapest SKU.
 */
@Path("/v1/places")
@RegisterRestClient(configKey = "google-places-api", baseUri = "https://places.googleapis.com")
public interface GooglePlacesRestClient {

    @GET
    @Path("/{placeId}")
    @Produces(MediaType.APPLICATION_JSON)
    GooglePlaceDetailsResponse getPlaceDetails(
            @PathParam("placeId") String placeId,
            @HeaderParam("X-Goog-Api-Key") String apiKey,
            @HeaderParam("X-Goog-FieldMask") String fieldMask);
}
