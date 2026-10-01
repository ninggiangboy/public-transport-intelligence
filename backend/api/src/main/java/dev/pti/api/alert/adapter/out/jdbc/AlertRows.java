package dev.pti.api.alert.adapter.out.jdbc;

import dev.pti.api.alert.domain.Alert;
import dev.pti.api.alert.domain.AlertType;
import dev.pti.api.platform.adapter.out.jdbc.ResultSets;
import dev.pti.common.events.Audience;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Map;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

/** Maps a row of {@code ops.alert_event}; {@code body} is read as text and parsed here. */
final class AlertRows {

    private static final TypeReference<Map<String, Object>> BODY = new TypeReference<>() {};

    private AlertRows() {}

    static Alert map(ResultSet rs, JsonMapper json) throws SQLException {
        return new Alert(
                ResultSets.uuid(rs, "id"),
                AlertType.valueOf(rs.getString("type")),
                rs.getInt("severity"),
                Audience.valueOf(rs.getString("audience")),
                rs.getString("route_id"),
                rs.getString("ref_table"),
                rs.getString("ref_id"),
                rs.getString("title"),
                json.readValue(rs.getString("body"), BODY),
                ResultSets.instant(rs, "created_at"),
                rs.getString("acknowledged_by"),
                ResultSets.nullableInstant(rs, "acknowledged_at"),
                ResultSets.nullableInstant(rs, "resolved_at"));
    }
}
