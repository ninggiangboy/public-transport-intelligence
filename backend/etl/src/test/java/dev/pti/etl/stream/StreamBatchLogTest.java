package dev.pti.etl.stream;

import static dev.pti.etl.testing.EtlFixtures.NOW;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.pti.etl.core.EtlSource;
import dev.pti.etl.core.InboundMessage;
import dev.pti.etl.write.WriteMode;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.jdbc.core.namedparam.SqlParameterSource;

class StreamBatchLogTest {

    private final NamedParameterJdbcTemplate jdbc = mock(NamedParameterJdbcTemplate.class);
    private final StreamBatchLog log = new StreamBatchLog(jdbc);
    private final StreamChunkRequest request = new StreamChunkRequest(
            UUID.randomUUID(),
            EtlSource.TICKETING_SALES,
            "ticketing-sales",
            "pti-etl-ticketing",
            "pod",
            List.of(new InboundMessage(
                    EtlSource.TICKETING_SALES, "k", new byte[0], "ticketing.sales.cdc", 2, 40L, NOW, Map.of())),
            false);

    private SqlParameterSource captured() {
        ArgumentCaptor<SqlParameterSource> params = ArgumentCaptor.forClass(SqlParameterSource.class);
        verify(jdbc).update(anyString(), params.capture());
        return params.getValue();
    }

    @Test
    void aCommittedBatch() {
        StreamChunkResult result = new StreamChunkResult(
                request.batchId(), EtlSource.TICKETING_SALES, WriteMode.SCAN, 1, 0, 1, 0, null, null, NOW, Set.of());
        log.insert(request, result, NOW, NOW);

        SqlParameterSource p = captured();
        assertThat(p.getValue("status")).isEqualTo("COMPLETED_WITH_SKIPS");
        assertThat(p.getValue("write_mode")).isEqualTo("SCAN");
        assertThat(p.getValue("offsets")).isEqualTo("{\"ticketing.sales.cdc-2\":[40,40]}");
        assertThat(p.getValue("error_class")).isNull();
    }

    @Test
    void aFailedBatchIsRecordedBestEffort() {
        log.insertFailedBestEffort(request, WriteMode.BATCH, new IllegalStateException("x".repeat(5000)), NOW, NOW);
        SqlParameterSource p = captured();
        assertThat(p.getValue("status")).isEqualTo("FAILED");
        assertThat((String) p.getValue("error_message")).hasSize(4000);

        when(jdbc.update(anyString(), any(SqlParameterSource.class)))
                .thenThrow(new DataAccessResourceFailureException("down"));
        assertThatCode(() -> log.insertFailedBestEffort(request, WriteMode.BATCH, new RuntimeException(), NOW, NOW))
                .doesNotThrowAnyException();
    }
}
