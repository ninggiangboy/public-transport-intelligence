package dev.pti.api.insight.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.pti.api.insight.application.port.DispatchFeedbackStore;
import dev.pti.api.insight.domain.DispatchSuggestion;
import dev.pti.api.insight.domain.Feedback;
import dev.pti.api.insight.domain.InsightFixtures;
import dev.pti.api.platform.domain.NotFoundException;
import dev.pti.apitest.DirectTransactions;
import dev.pti.apitest.InMemoryInsight;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** {@code POST /insights/dispatch-suggestions/{id}/feedback} (DOC-32 E-18, EP-14). */
class SubmitDispatchFeedbackTest {

    private final InMemoryInsight insight = new InMemoryInsight();
    private final List<String> writes = new ArrayList<>();
    private final List<String> metrics = new ArrayList<>();
    private SubmitDispatchFeedback submit;

    @BeforeEach
    void setUp() {
        insight.suggestions.put(
                InsightFixtures.SUGGESTION_ID,
                InsightFixtures.suggestion(
                        InsightFixtures.SUGGESTION_ID,
                        InsightFixtures.BUNCHING_ID,
                        "18",
                        Instant.parse("2026-09-29T21:12:47Z"),
                        "0.820"));
        DispatchFeedbackStore counting = new DispatchFeedbackStore() {
            @Override
            public Optional<DispatchSuggestion> find(UUID id) {
                return insight.feedbackStore.find(id);
            }

            @Override
            public Optional<DispatchSuggestion> record(UUID id, Feedback feedback, String actor) {
                writes.add(actor + " " + feedback.wireName());
                return insight.feedbackStore.record(id, feedback, actor);
            }
        };
        submit = new SubmitDispatchFeedback(
                counting, (operation, outcome) -> metrics.add(operation + ":" + outcome), new DirectTransactions());
    }

    @Test
    @DisplayName("Feedback is recorded with the operator and the time, and counted as created")
    void records() {
        DispatchSuggestion result = submit.execute("user:operator", InsightFixtures.SUGGESTION_ID, Feedback.ACCEPTED);

        assertThat(result.operatorFeedback()).isEqualTo(Feedback.ACCEPTED);
        assertThat(result.feedbackBy()).isEqualTo("user:operator");
        assertThat(result.feedbackAt()).isEqualTo(InMemoryInsight.FEEDBACK_TIME);
        assertThat(metrics).containsExactly("feedback:created");
    }

    @Test
    @DisplayName("EP-14 accepted and then ignored by another operator: the last answer and the last operator win")
    void lastOperatorWins() {
        submit.execute("user:operator", InsightFixtures.SUGGESTION_ID, Feedback.ACCEPTED);
        DispatchSuggestion result = submit.execute("user:other", InsightFixtures.SUGGESTION_ID, Feedback.IGNORED);

        assertThat(result.operatorFeedback()).isEqualTo(Feedback.IGNORED);
        assertThat(result.feedbackBy()).isEqualTo("user:other");
        assertThat(writes).containsExactly("user:operator accepted", "user:other ignored");
    }

    @Test
    @DisplayName("The same answer from the same operator is answered with the row as it is and writes nothing")
    void sameAnswerAgain() {
        submit.execute("user:operator", InsightFixtures.SUGGESTION_ID, Feedback.ACCEPTED);
        DispatchSuggestion again = submit.execute("user:operator", InsightFixtures.SUGGESTION_ID, Feedback.ACCEPTED);

        assertThat(again.operatorFeedback()).isEqualTo(Feedback.ACCEPTED);
        assertThat(writes).hasSize(1);
        assertThat(metrics).containsExactly("feedback:created", "feedback:idempotent");
    }

    @Test
    @DisplayName("The same answer from another operator is a write: the later operator is recorded")
    void sameAnswerOtherOperator() {
        submit.execute("user:operator", InsightFixtures.SUGGESTION_ID, Feedback.ACCEPTED);
        DispatchSuggestion result = submit.execute("user:other", InsightFixtures.SUGGESTION_ID, Feedback.ACCEPTED);

        assertThat(result.feedbackBy()).isEqualTo("user:other");
        assertThat(writes).hasSize(2);
    }

    @Test
    @DisplayName("An unknown suggestion is a 404 and nothing is counted")
    void unknown() {
        assertThatThrownBy(() -> submit.execute("user:operator", UUID.randomUUID(), Feedback.ACCEPTED))
                .isInstanceOf(NotFoundException.class);
        assertThat(metrics).isEmpty();
    }
}
