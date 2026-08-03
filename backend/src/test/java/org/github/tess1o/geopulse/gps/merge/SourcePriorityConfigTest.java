package org.github.tess1o.geopulse.gps.merge;

import org.github.tess1o.geopulse.shared.gps.GpsSourceType;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.NullAndEmptySource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.HashSet;
import java.util.List;

import static org.github.tess1o.geopulse.shared.gps.GpsSourceType.GOOGLE_TIMELINE;
import static org.github.tess1o.geopulse.shared.gps.GpsSourceType.HOME_ASSISTANT;
import static org.github.tess1o.geopulse.shared.gps.GpsSourceType.MANUAL;
import static org.github.tess1o.geopulse.shared.gps.GpsSourceType.OWNTRACKS;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for {@link SourcePriorityConfig#parsePriority(String)}, the pure half of the
 * precedence config. No CDI, no database.
 */
@Tag("unit")
class SourcePriorityConfigTest {

    @Test
    @DisplayName("the default CSV is parsed in order and covers every source type")
    void defaultCsvIsATotalOrder() {
        List<GpsSourceType> priority = SourcePriorityConfig.parsePriority(SourcePriorityConfig.DEFAULT_PRIORITY_CSV);

        assertEquals(GpsSourceType.values().length, priority.size());
        assertEquals(new HashSet<>(List.of(GpsSourceType.values())), new HashSet<>(priority));
        assertEquals(GOOGLE_TIMELINE, priority.getFirst());
        assertEquals(HOME_ASSISTANT, priority.get(1));
    }

    @ParameterizedTest
    @NullAndEmptySource
    @ValueSource(strings = {"   ", ",,,", " , , ", "NOT_A_SOURCE", "nonsense,garbage", "!!!"})
    @DisplayName("empty or entirely unrecognised config falls back to the default order")
    void garbageFallsBackToDefault(String csv) {
        List<GpsSourceType> priority = SourcePriorityConfig.parsePriority(csv);

        assertEquals(SourcePriorityConfig.parsePriority(SourcePriorityConfig.DEFAULT_PRIORITY_CSV), priority);
    }

    @Test
    @DisplayName("an explicit partial list wins, and everything unmentioned sorts last in enum order")
    void unmentionedSourcesSortLastPreservingEnumOrder() {
        List<GpsSourceType> priority = SourcePriorityConfig.parsePriority("HOME_ASSISTANT,GOOGLE_TIMELINE");

        assertEquals(HOME_ASSISTANT, priority.getFirst());
        assertEquals(GOOGLE_TIMELINE, priority.get(1));
        assertEquals(GpsSourceType.values().length, priority.size());

        // Remainder keeps GpsSourceType declaration order among themselves.
        List<GpsSourceType> remainder = priority.subList(2, priority.size());
        List<GpsSourceType> expectedRemainder = List.of(GpsSourceType.values()).stream()
                .filter(t -> t != HOME_ASSISTANT && t != GOOGLE_TIMELINE)
                .toList();
        assertEquals(expectedRemainder, remainder);
    }

    @Test
    @DisplayName("unknown tokens are skipped without discarding the valid ones around them")
    void unknownTokensAreSkippedNotFatal() {
        List<GpsSourceType> priority =
                SourcePriorityConfig.parsePriority("NOPE, HOME_ASSISTANT , WAT ,OWNTRACKS");

        assertEquals(HOME_ASSISTANT, priority.getFirst());
        assertEquals(OWNTRACKS, priority.get(1));
        assertTrue(priority.indexOf(GOOGLE_TIMELINE) > 1, "unmentioned GOOGLE_TIMELINE must rank after listed sources");
    }

    @Test
    @DisplayName("whitespace and casing are tolerated")
    void toleratesWhitespaceAndCasing() {
        assertEquals(
                SourcePriorityConfig.parsePriority("HOME_ASSISTANT,MANUAL"),
                SourcePriorityConfig.parsePriority("  home_assistant ,\tManual  "));
    }

    @Test
    @DisplayName("duplicates collapse to their first occurrence")
    void duplicatesCollapse() {
        List<GpsSourceType> priority = SourcePriorityConfig.parsePriority("MANUAL,HOME_ASSISTANT,MANUAL");

        assertEquals(MANUAL, priority.getFirst());
        assertEquals(HOME_ASSISTANT, priority.get(1));
        assertEquals(GpsSourceType.values().length, priority.size());
    }
}
