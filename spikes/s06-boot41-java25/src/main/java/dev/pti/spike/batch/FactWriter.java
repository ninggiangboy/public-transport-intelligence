package dev.pti.spike.batch;

import java.util.List;
import org.springframework.batch.infrastructure.item.Chunk;
import org.springframework.batch.infrastructure.item.ItemWriter;
import org.springframework.jdbc.core.JdbcTemplate;

/** Batch upsert inside the chunk transaction, like FactChunkWriter (DOC-19 §4.3). */
public class FactWriter implements ItemWriter<Item> {

    private final JdbcTemplate jdbc;
    private final FaultPlan faults;
    private final String variant;

    public FactWriter(JdbcTemplate jdbc, FaultPlan faults, String variant) {
        this.jdbc = jdbc;
        this.faults = faults;
        this.variant = variant;
    }

    @Override
    public void write(Chunk<? extends Item> chunk) {
        for (Item item : chunk) {
            faults.beforeWrite(item.id(), chunk.size() == 1);
        }
        List<Object[]> rows = chunk.getItems().stream()
                .map(i -> new Object[] {i.id(), i.value(), variant})
                .toList();
        jdbc.batchUpdate(
                "INSERT INTO spike_fact (id, value, written_by) VALUES (?, ?, ?) "
                        + "ON CONFLICT (id) DO UPDATE SET value = EXCLUDED.value, written_by = EXCLUDED.written_by",
                rows);
    }
}
