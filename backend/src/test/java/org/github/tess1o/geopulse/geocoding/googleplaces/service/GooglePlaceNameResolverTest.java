package org.github.tess1o.geopulse.geocoding.googleplaces.service;

import org.github.tess1o.geopulse.geocoding.googleplaces.client.GooglePlacesRestClient;
import org.github.tess1o.geopulse.geocoding.googleplaces.config.GooglePlacesConfiguration;
import org.github.tess1o.geopulse.geocoding.googleplaces.model.GooglePlaceDetailsResponse;
import org.github.tess1o.geopulse.geocoding.googleplaces.model.GooglePlaceNameEntity;
import org.github.tess1o.geopulse.geocoding.googleplaces.repository.GooglePlaceLookupPendingRepository;
import org.github.tess1o.geopulse.geocoding.googleplaces.repository.GooglePlaceNameRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Unit tests for GooglePlaceNameResolver.
 * <p>
 * Two properties matter more than anything else here, because both cost real money when wrong:
 * a cached result - including a cached NULL, meaning "asked, nothing there" - must never trigger
 * another API call, and a lookup that cannot be afforded this month must be deferred rather than
 * dropped or forced through.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Tag("unit")
class GooglePlaceNameResolverTest {

    @Mock
    private GooglePlacesRestClient placesClient;
    @Mock
    private GooglePlacesConfiguration configuration;
    @Mock
    private GooglePlacesQuotaService quotaService;
    @Mock
    private GooglePlaceNameRepository nameRepository;
    @Mock
    private GooglePlaceLookupPendingRepository pendingRepository;

    private GooglePlaceNameResolver resolver;

    @BeforeEach
    void setUp() {
        when(configuration.isUsable()).thenReturn(true);
        when(configuration.getApiKey()).thenReturn("test-key");
        when(nameRepository.findCachedNames(any())).thenReturn(Map.of());

        resolver = new GooglePlaceNameResolver(placesClient, configuration, quotaService,
                nameRepository, pendingRepository);
    }

    @Test
    void shouldReturnCachedName_WithoutCallingApi() {
        when(nameRepository.findCachedNames(any())).thenReturn(Map.of("placeA", "The Corner Cafe"));

        Map<String, String> result = resolver.resolveNames(List.of("placeA"));

        assertEquals("The Corner Cafe", result.get("placeA"));
        verifyNoInteractions(placesClient);
        verify(quotaService, never()).reserveRequest();
    }

    @Test
    void shouldNotCallApi_WhenCachedNameIsNull() {
        // A cached NULL is a confirmed miss: Google was already asked and had nothing. Asking again
        // costs money and cannot produce a different answer.
        Map<String, String> confirmedMiss = new HashMap<>();
        confirmedMiss.put("placeA", null);
        when(nameRepository.findCachedNames(any())).thenReturn(confirmedMiss);

        Map<String, String> result = resolver.resolveNames(List.of("placeA"));

        assertTrue(result.isEmpty(), "A confirmed miss must be absent from the result");
        verifyNoInteractions(placesClient);
        verify(quotaService, never()).reserveRequest();
        verify(pendingRepository, never()).queue(anyString(), any(), any());
    }

    @Test
    void shouldQueueToPending_WhenQuotaExhausted() {
        when(quotaService.reserveRequest()).thenReturn(false);

        Map<String, String> result = resolver.resolveNames(List.of("placeA"));

        assertTrue(result.isEmpty(), "An unaffordable lookup must be absent, so the caller falls back");
        verify(pendingRepository).queue(eq("placeA"), isNull(), isNull());
        verifyNoInteractions(placesClient);
        // Nothing is cached - the lookup is deferred, not answered
        verify(nameRepository, never()).cache(anyString(), any(), anyString());
    }

    @Test
    void shouldCallApiAndCache_WhenQuotaAvailable() {
        when(quotaService.reserveRequest()).thenReturn(true);
        when(placesClient.getPlaceDetails(eq("placeA"), eq("test-key"), anyString()))
                .thenReturn(responseNamed("Tesco Metro"));

        Map<String, String> result = resolver.resolveNames(List.of("placeA"));

        assertEquals("Tesco Metro", result.get("placeA"));
        verify(nameRepository).cache("placeA", "Tesco Metro", GooglePlaceNameEntity.SOURCE_GOOGLE);
        verify(pendingRepository, never()).queue(anyString(), any(), any());
    }

    @Test
    void shouldCacheNullMiss_WhenGoogleReturnsNoDisplayName() {
        when(quotaService.reserveRequest()).thenReturn(true);
        when(placesClient.getPlaceDetails(anyString(), anyString(), anyString()))
                .thenReturn(new GooglePlaceDetailsResponse());

        Map<String, String> result = resolver.resolveNames(List.of("placeA"));

        assertTrue(result.isEmpty());
        // The miss is recorded so it is never billed again
        verify(nameRepository).cache("placeA", null, GooglePlaceNameEntity.SOURCE_NONE);
    }

    @Test
    void shouldQueueAndNotCache_WhenLookupThrows() {
        when(quotaService.reserveRequest()).thenReturn(true);
        when(placesClient.getPlaceDetails(anyString(), anyString(), anyString()))
                .thenThrow(new RuntimeException("connection reset"));

        Map<String, String> result = resolver.resolveNames(List.of("placeA"));

        assertTrue(result.isEmpty());
        // Caching a transient failure would poison the entry permanently
        verify(nameRepository, never()).cache(anyString(), any(), anyString());
        verify(pendingRepository).queue(eq("placeA"), isNull(), isNull());
    }

    @Test
    void shouldDoNothing_WhenIntegrationDisabled() {
        when(configuration.isUsable()).thenReturn(false);

        assertTrue(resolver.resolveNames(List.of("placeA")).isEmpty());

        verifyNoInteractions(placesClient, quotaService, nameRepository, pendingRepository);
    }

    @Test
    void shouldDedupeAndIgnoreBlankPlaceIds() {
        when(quotaService.reserveRequest()).thenReturn(true);
        when(placesClient.getPlaceDetails(eq("placeA"), anyString(), anyString()))
                .thenReturn(responseNamed("Somewhere"));

        Map<String, String> result = resolver.resolveNames(java.util.Arrays.asList(
                "placeA", "placeA", "  ", null, " placeA "));

        assertEquals(1, result.size());
        assertFalse(result.containsKey(""));
        // One distinct placeID means exactly one billable request
        verify(quotaService).reserveRequest();
    }

    private static GooglePlaceDetailsResponse responseNamed(String text) {
        GooglePlaceDetailsResponse response = new GooglePlaceDetailsResponse();
        GooglePlaceDetailsResponse.DisplayName displayName = new GooglePlaceDetailsResponse.DisplayName();
        displayName.setText(text);
        response.setDisplayName(displayName);
        return response;
    }
}
