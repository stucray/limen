package com.stucray.limen.audit;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.sql.Types;

// We construct our own ObjectMapper rather than injecting the
// application-wide bean: there is more than one ObjectMapper bean in this
// context (Spring's default plus SAS's polymorphic-typing-enabled mapper),
// and the autowire would fail. Audit only ever serialises a Map<String,Object>
// so a vanilla mapper is sufficient.

/**
 * Persists an {@link AuditEvent} to the {@code audit_event} table. JDBC-direct
 * rather than Spring Data because the {@code details} column is JSONB — bound
 * via PostgreSQL's {@code OTHER} JDBC type with a JSON-serialised string.
 *
 * <p>Listeners delegate to this class; production code outside the audit
 * package never references it. Cross-cutting writes are pub/sub via
 * {@code ApplicationEventPublisher}.
 */
@Component
public class AuditEventWriter {

    private static final String INSERT_SQL = """
        INSERT INTO audit_event (
            tenant_id, actor_user_id, event_type, target_type, target_id,
            ip_address, user_agent, occurred_at, details
        ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
        """;

    private final JdbcTemplate jdbcTemplate;
    private final ObjectMapper objectMapper = new ObjectMapper();

    AuditEventWriter(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void write(AuditEvent event) {
        String detailsJson;
        try {
            detailsJson = objectMapper.writeValueAsString(event.details());
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to serialise audit details", e);
        }
        jdbcTemplate.update(connection -> {
            var ps = connection.prepareStatement(INSERT_SQL);
            setNullableLong(ps, 1, event.tenantId());
            setNullableLong(ps, 2, event.actorUserId());
            ps.setString(3, event.eventType());
            ps.setString(4, event.targetType());
            ps.setString(5, event.targetId());
            ps.setString(6, event.ipAddress());
            ps.setString(7, event.userAgent());
            ps.setTimestamp(8, java.sql.Timestamp.valueOf(event.occurredAt()));
            ps.setObject(9, detailsJson, Types.OTHER);
            return ps;
        });
    }

    private static void setNullableLong(java.sql.PreparedStatement ps, int idx, @Nullable Long value) throws java.sql.SQLException {
        if (value == null) {
            ps.setNull(idx, Types.BIGINT);
        } else {
            ps.setLong(idx, value);
        }
    }
}
