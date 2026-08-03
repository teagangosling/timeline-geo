package org.github.tess1o.geopulse.streaming.service.googleplace;

import jakarta.persistence.EntityManager;
import jakarta.persistence.Query;
import org.github.tess1o.geopulse.streaming.model.domain.Stay;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Unit tests for GooglePlaceIdStayResolver.
 * <p>
 * The interesting behaviour is the reduction from per-(stay, placeID) counts down to one placeID
 * per stay: a stay routinely spans two adjacent Google visits, so the pick must be modal rather
 * than first-wins, and ties must resolve the same way every run.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@Tag("unit")
class GooglePlaceIdStayResolverTest {

    @Mock
    private EntityManager entityManager;

    @Mock
    private Query query;

    @Test
    void shouldPickModalPlaceId_WhenStaySpansTwoVisits() {
        // 3 points at place A, 2 at place B, same stay window
        Map<Integer, String> result = GooglePlaceIdStayResolver.selectModalPlaceIds(List.of(
                new Object[]{0, "placeA", 3L},
                new Object[]{0, "placeB", 2L}
        ));

        assertEquals(1, result.size());
        assertEquals("placeA", result.get(0));
    }

    @Test
    void shouldPickModalPlaceId_RegardlessOfRowOrder() {
        // Same data, minority row first - the winner must not depend on row order
        Map<Integer, String> result = GooglePlaceIdStayResolver.selectModalPlaceIds(List.of(
                new Object[]{0, "placeB", 2L},
                new Object[]{0, "placeA", 3L}
        ));

        assertEquals("placeA", result.get(0));
    }

    @Test
    void shouldBreakTiesToLexicallyLowestPlaceId() {
        // Equal counts. "aaa" must win in both orderings, otherwise regenerating a timeline twice
        // could produce two different names for the same stay.
        Map<Integer, String> ascending = GooglePlaceIdStayResolver.selectModalPlaceIds(List.of(
                new Object[]{0, "aaa", 4L},
                new Object[]{0, "zzz", 4L}
        ));
        Map<Integer, String> descending = GooglePlaceIdStayResolver.selectModalPlaceIds(List.of(
                new Object[]{0, "zzz", 4L},
                new Object[]{0, "aaa", 4L}
        ));

        assertEquals("aaa", ascending.get(0));
        assertEquals("aaa", descending.get(0));
    }

    @Test
    void shouldResolveEachStayIndependently() {
        Map<Integer, String> result = GooglePlaceIdStayResolver.selectModalPlaceIds(List.of(
                new Object[]{0, "placeA", 3L},
                new Object[]{0, "placeB", 2L},
                new Object[]{1, "placeB", 7L},
                new Object[]{4, "placeC", 1L}
        ));

        assertEquals(3, result.size());
        assertEquals("placeA", result.get(0));
        assertEquals("placeB", result.get(1));
        assertEquals("placeC", result.get(4));
        assertFalse(result.containsKey(2), "Stays with no Google points must be absent, not null-valued");
    }

    @Test
    void shouldIgnoreMalformedRows() {
        Map<Integer, String> result = GooglePlaceIdStayResolver.selectModalPlaceIds(java.util.Arrays.asList(
                null,
                new Object[]{0},
                new Object[]{null, "placeA", 1L},
                new Object[]{0, null, 1L},
                new Object[]{0, "placeA", 1L}
        ));

        assertEquals(1, result.size());
        assertEquals("placeA", result.get(0));
    }

    @Test
    void shouldQueryAndMapStayWindows() {
        UUID userId = UUID.randomUUID();
        Stay stay = Stay.builder()
                .startTime(Instant.parse("2026-01-01T10:00:00Z"))
                .duration(Duration.ofMinutes(45))
                .latitude(49.5)
                .longitude(25.6)
                .build();

        when(entityManager.createNativeQuery(anyString())).thenReturn(query);
        when(query.setParameter(anyString(), any())).thenReturn(query);
        when(query.getResultList()).thenReturn(List.of(
                new Object[]{0, "placeA", 5L},
                new Object[]{0, "placeB", 1L}
        ));

        GooglePlaceIdStayResolver resolver = new GooglePlaceIdStayResolver(entityManager);
        Map<Integer, String> result = resolver.resolveForStays(userId, List.of(stay));

        assertEquals("placeA", result.get(0));
        // ts_end is start + duration, exclusive
        verify(query).setParameter("s0", Instant.parse("2026-01-01T10:00:00Z"));
        verify(query).setParameter("e0", Instant.parse("2026-01-01T10:45:00Z"));
        verify(query).setParameter("userId", userId);
    }

    @Test
    void shouldNotQuery_WhenThereAreNoStays() {
        GooglePlaceIdStayResolver resolver = new GooglePlaceIdStayResolver(entityManager);

        assertTrue(resolver.resolveForStays(UUID.randomUUID(), List.of()).isEmpty());
        assertTrue(resolver.resolveForStays(UUID.randomUUID(), null).isEmpty());
        assertTrue(resolver.resolveForStays(null, List.of(Stay.builder().build())).isEmpty());

        verify(entityManager, never()).createNativeQuery(anyString());
    }
}
