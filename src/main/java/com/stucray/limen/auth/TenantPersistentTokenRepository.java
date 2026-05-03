package com.stucray.limen.auth;

import org.jspecify.annotations.Nullable;
import org.springframework.dao.IncorrectResultSizeDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.authentication.rememberme.PersistentRememberMeToken;

import java.util.Date;

/**
 * Tenant-scoped persistent-login storage. All four operations key on
 * {@code (tenant_id, series)} so that a series collision (or a replay) across
 * tenants is impossible at the storage layer.
 *
 * Does NOT implement {@link org.springframework.security.web.authentication.rememberme.PersistentTokenRepository}:
 * that interface omits the tenant parameter, and adapting it via thread-local
 * state would re-introduce the carrier-thread coupling that issue #33 removed.
 * Callers (the custom {@link TenantPersistentTokenBasedRememberMeServices})
 * pass the tenant explicitly on every call.
 */
public class TenantPersistentTokenRepository {

    private static final String INSERT_SQL =
        "INSERT INTO persistent_logins (tenant_id, email, series, token, last_used) "
        + "VALUES (?, ?, ?, ?, ?)";

    private static final String UPDATE_SQL =
        "UPDATE persistent_logins SET token = ?, last_used = ? "
        + "WHERE tenant_id = ? AND series = ?";

    private static final String SELECT_SQL =
        "SELECT email, series, token, last_used, tenant_id FROM persistent_logins "
        + "WHERE tenant_id = ? AND series = ?";

    private static final String DELETE_USER_SQL =
        "DELETE FROM persistent_logins WHERE tenant_id = ? AND email = ?";

    private final JdbcTemplate jdbcTemplate;

    public TenantPersistentTokenRepository(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public void createNewToken(PersistentRememberMeToken token, Long tenantId) {
        jdbcTemplate.update(INSERT_SQL,
            tenantId, token.getUsername(), token.getSeries(), token.getTokenValue(), token.getDate());
    }

    public void updateToken(String series, Long tenantId, String tokenValue, Date lastUsed) {
        jdbcTemplate.update(UPDATE_SQL, tokenValue, lastUsed, tenantId, series);
    }

    /** Returns the token row scoped to the given tenant, or null when absent. */
    public @Nullable TenantPersistentRememberMeToken getTokenForSeries(String series, Long tenantId) {
        try {
            return jdbcTemplate.queryForObject(SELECT_SQL, (rs, rowNum) ->
                new TenantPersistentRememberMeToken(
                    rs.getString("email"),
                    rs.getString("series"),
                    rs.getString("token"),
                    rs.getTimestamp("last_used"),
                    rs.getLong("tenant_id")
                ),
                tenantId, series
            );
        } catch (IncorrectResultSizeDataAccessException e) {
            return null;
        }
    }

    public void removeUserTokens(String email, Long tenantId) {
        jdbcTemplate.update(DELETE_USER_SQL, tenantId, email);
    }
}
