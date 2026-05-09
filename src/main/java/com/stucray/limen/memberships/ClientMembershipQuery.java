package com.stucray.limen.memberships;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * Narrow read-side module for the JWT customizer and the (slice 5)
 * /oauth2/authorize gate. Two methods, one SQL shape per method.
 *
 * The {@code tenant_id} predicate is redundant — the FK chain
 * {@code client_membership → client_metadata → tenant} already enforces
 * containment — but it is kept explicit as defence-in-depth, matching the
 * existing TenantAware* decorator pattern. A stray cross-tenant join cannot
 * leak Roles even if a future bug feeds the wrong tenant id.
 */
@Component
public class ClientMembershipQuery {

    private final JdbcTemplate jdbcTemplate;

    ClientMembershipQuery(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<String> rolesFor(Long userId, String registeredClientId, Long tenantId) {
        if (userId == null || registeredClientId == null || tenantId == null) {
            return List.of();
        }
        return jdbcTemplate.query(
            """
            SELECT r.name
              FROM client_membership cm
              JOIN client_membership_role cmr ON cmr.client_membership_id = cm.id
              JOIN role r                     ON r.id = cmr.role_id
              JOIN client_metadata m          ON m.id = cm.client_metadata_id
             WHERE cm.user_id = ?
               AND m.registered_client_id = ?
               AND m.tenant_id = ?
             ORDER BY r.name
            """,
            (rs, i) -> rs.getString("name"),
            userId, registeredClientId, tenantId
        );
    }

    public boolean hasMembership(Long userId, String registeredClientId, Long tenantId) {
        if (userId == null || registeredClientId == null || tenantId == null) {
            return false;
        }
        Integer count = jdbcTemplate.queryForObject(
            """
            SELECT COUNT(*)
              FROM client_membership cm
              JOIN client_metadata m ON m.id = cm.client_metadata_id
             WHERE cm.user_id = ?
               AND m.registered_client_id = ?
               AND m.tenant_id = ?
            """,
            Integer.class, userId, registeredClientId, tenantId
        );
        return count != null && count > 0;
    }
}
