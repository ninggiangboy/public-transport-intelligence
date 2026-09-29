package dev.pti.etl.write;

import dev.pti.etl.core.EtlSource;
import java.util.Collection;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Payload hashes seen recently (DR-16, {@code ops.dedup_registry}). An optimization in front of the upsert guards:
 * a message whose hash is already registered is dropped before it costs a write. Runs in the chunk transaction, so
 * a rolled-back chunk registers nothing. Never used on replay.
 */
public final class DedupRegistry {

    private static final String REGISTER = """
            INSERT INTO ops.dedup_registry (source, payload_hash, batch_id)
            SELECT ?, h, ? FROM unnest(?::text[]) AS h
            ON CONFLICT (source, payload_hash) DO NOTHING
            RETURNING payload_hash
            """;

    private final JdbcTemplate jdbc;

    public DedupRegistry(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    /** Registers the hashes and returns those that were new; the others were seen within the TTL. */
    public Set<String> registerNew(EtlSource source, Collection<String> hashes, UUID batchId) {
        if (hashes.isEmpty()) {
            return Set.of();
        }
        Set<String> fresh = new HashSet<>();
        jdbc.query(
                REGISTER,
                ps -> {
                    ps.setString(1, source.name());
                    ps.setObject(2, batchId);
                    ps.setArray(
                            3,
                            ps.getConnection()
                                    .createArrayOf(
                                            "text", hashes.stream().distinct().toArray()));
                },
                rs -> {
                    fresh.add(rs.getString(1).trim());
                });
        return fresh;
    }
}
