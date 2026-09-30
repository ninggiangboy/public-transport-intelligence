package dev.pti.common.id;

import static org.assertj.core.api.Assertions.assertThat;

import com.github.f4b6a3.uuid.UuidCreator;
import com.github.f4b6a3.uuid.enums.UuidNamespace;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

class InsightIdsTest {

    private static final Instant EPISODE_START = Instant.parse("2026-09-29T21:19:30Z");

    @Test
    @DisplayName("The namespace is UUIDv5(NAMESPACE_URL, \"urn:pti:insight\")")
    void namespaceIsDerivedFromTheUrn() {
        assertThat(InsightIds.NAMESPACE)
                .isEqualTo(UuidCreator.getNameBasedSha1(UuidNamespace.NAMESPACE_URL, "urn:pti:insight"));
    }

    @Test
    @DisplayName("a bunching id is pinned to the value Python's uuid5 computes")
    void bunchingIdIsPinned() {
        UUID id = InsightIds.bunching("18", "1234", "1250", EPISODE_START);

        assertThat(id).hasToString("c69bcc55-ae25-5b9c-931c-e690e8e5220d");
    }

    @Test
    @DisplayName("an alert id is pinned to the value Python's uuid5 computes")
    void alertIdIsPinned() {
        UUID id = InsightIds.alert("bunching:c69bcc55-ae25-5b9c-931c-e690e8e5220d");

        assertThat(id).hasToString("fb899421-12d7-5369-ac3f-51d7b885dfcf");
    }

    @Test
    void theSameNaturalKeyGivesTheSameIdAndADifferentKeyGivesAnother() {
        assertThat(InsightIds.disruption("18", 0, EPISODE_START))
                .isEqualTo(InsightIds.disruption("18", 0, EPISODE_START));
        assertThat(InsightIds.disruption("18", 0, EPISODE_START))
                .isNotEqualTo(InsightIds.disruption("18", 1, EPISODE_START));
        assertThat(InsightIds.ticketingAnomaly("SP-1", EPISODE_START))
                .isNotEqualTo(InsightIds.ticketingAnomaly("SP-2", EPISODE_START));
    }

    @Test
    void idsOfDifferentKindsDoNotCollideOnTheSameText() {
        UUID bunching = InsightIds.bunching("18", "1", "2", EPISODE_START);

        assertThat(InsightIds.dispatchSuggestion(bunching)).isNotEqualTo(bunching);
        assertThat(InsightIds.dispatchSuggestion(bunching)).isNotEqualTo(InsightIds.alert(bunching.toString()));
    }

    @Test
    void fractionOfASecondIsNotPartOfTheKey() {
        assertThat(InsightIds.ticketingAnomaly("SP-1", EPISODE_START.plusMillis(250)))
                .isEqualTo(InsightIds.ticketingAnomaly("SP-1", EPISODE_START));
    }
}
