package com.stucray.limen.memberships;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowCallbackHandler;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Read-only portfolio view of every Membership belonging to a single User
 * within a single Tenant. Backs the User detail screen.
 *
 * Two SELECTs (one per membership table), assembled in Java. Each SELECT
 * carries a {@code tenant_id} predicate as defence-in-depth, matching the
 * existing pattern in {@link ClientMembershipQuery} — the FK chains already
 * enforce containment, but the explicit predicate stops a stray cross-tenant
 * join from leaking Roles.
 */
@Component
public class UserMembershipPortfolioQuery {

    private final JdbcTemplate jdbcTemplate;

    UserMembershipPortfolioQuery(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public record AppMembershipView(
        Long applicationMembershipId,
        Long applicationId,
        String applicationName,
        List<String> appRoles,
        List<ClientMembershipView> clientMemberships
    ) {}

    public record ClientMembershipView(
        Long clientMembershipId,
        String registeredClientId,
        String clientDisplayName,
        List<String> clientRoles
    ) {}

    public List<AppMembershipView> portfolioFor(Long userId, Long tenantId) {
        if (userId == null || tenantId == null) {
            return List.of();
        }

        Map<Long, AppBuilder> byAppMembershipId = new LinkedHashMap<>();
        jdbcTemplate.query(
            """
            SELECT am.id   AS app_membership_id,
                   a.id    AS application_id,
                   a.name  AS application_name,
                   r.name  AS role_name
              FROM application_membership am
              JOIN applications a ON a.id = am.application_id
         LEFT JOIN application_membership_role amr ON amr.application_membership_id = am.id
         LEFT JOIN role r ON r.id = amr.role_id
             WHERE am.user_id = ?
               AND a.tenant_id = ?
             ORDER BY lower(a.name), lower(r.name)
            """,
            (RowCallbackHandler) rs -> {
                Long ammId = rs.getLong("app_membership_id");
                Long applicationId = rs.getLong("application_id");
                String applicationName = rs.getString("application_name");
                String roleName = rs.getString("role_name");
                AppBuilder app = byAppMembershipId.computeIfAbsent(ammId,
                    k -> new AppBuilder(ammId, applicationId, applicationName));
                if (roleName != null) app.appRoles.add(roleName);
            },
            userId, tenantId
        );

        jdbcTemplate.query(
            """
            SELECT cm.id                          AS client_membership_id,
                   cm.application_membership_id   AS app_membership_id,
                   m.registered_client_id         AS registered_client_id,
                   m.display_name                 AS client_display_name,
                   r.name                         AS role_name
              FROM client_membership cm
              JOIN client_metadata m ON m.id = cm.client_metadata_id
         LEFT JOIN client_membership_role cmr ON cmr.client_membership_id = cm.id
         LEFT JOIN role r ON r.id = cmr.role_id
             WHERE cm.user_id = ?
               AND m.tenant_id = ?
             ORDER BY lower(m.display_name), lower(r.name)
            """,
            (RowCallbackHandler) rs -> {
                Long ammId = rs.getLong("app_membership_id");
                AppBuilder app = byAppMembershipId.get(ammId);
                if (app == null) return;
                Long cmId = rs.getLong("client_membership_id");
                String registeredClientId = rs.getString("registered_client_id");
                String clientDisplayName = rs.getString("client_display_name");
                String roleName = rs.getString("role_name");
                ClientBuilder client = app.clients.computeIfAbsent(cmId,
                    k -> new ClientBuilder(cmId, registeredClientId, clientDisplayName));
                if (roleName != null) client.roles.add(roleName);
            },
            userId, tenantId
        );

        List<AppMembershipView> out = new ArrayList<>(byAppMembershipId.size());
        for (AppBuilder app : byAppMembershipId.values()) {
            List<ClientMembershipView> clients = new ArrayList<>(app.clients.size());
            for (ClientBuilder client : app.clients.values()) {
                clients.add(new ClientMembershipView(
                    client.id, client.registeredClientId, client.displayName, client.roles
                ));
            }
            out.add(new AppMembershipView(
                app.id, app.applicationId, app.applicationName, app.appRoles, clients
            ));
        }
        return out;
    }

    private static final class AppBuilder {
        final Long id;
        final Long applicationId;
        final String applicationName;
        final List<String> appRoles = new ArrayList<>();
        final Map<Long, ClientBuilder> clients = new LinkedHashMap<>();

        AppBuilder(Long id, Long applicationId, String applicationName) {
            this.id = id;
            this.applicationId = applicationId;
            this.applicationName = applicationName;
        }
    }

    private static final class ClientBuilder {
        final Long id;
        final String registeredClientId;
        final String displayName;
        final List<String> roles = new ArrayList<>();

        ClientBuilder(Long id, String registeredClientId, String displayName) {
            this.id = id;
            this.registeredClientId = registeredClientId;
            this.displayName = displayName;
        }
    }
}
