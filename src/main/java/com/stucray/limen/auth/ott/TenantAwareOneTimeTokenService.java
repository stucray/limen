package com.stucray.limen.auth.ott;

import com.stucray.limen.tenant.TenantScope;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.ott.GenerateOneTimeTokenRequest;
import org.springframework.security.authentication.ott.OneTimeToken;
import org.springframework.security.authentication.ott.OneTimeTokenAuthenticationToken;
import org.springframework.security.authentication.ott.OneTimeTokenService;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped {@link OneTimeTokenService}. Mirrors the storage shape of
 * Spring Security's {@code JdbcOneTimeTokenService} (token_value PK, username,
 * expires_at) plus the two columns added by the V8 migration: {@code tenant_id}
 * and {@code intent}.
 *
 * <p>We re-implement rather than wrap because Spring's class is {@code final}
 * and {@code consume()} couples SELECT + DELETE in a way that does not compose
 * cleanly with a decorator that needs to read {@code tenant_id} + {@code intent}
 * on the same row before it disappears. The SQL is straightforward and matches
 * the framework's contract.
 *
 * <p>Tenant binding: every call requires an active {@link TenantScope}.
 * {@link #generate(GenerateOneTimeTokenRequest)} stamps the calling tenant on
 * the row. {@link #consume(OneTimeTokenAuthenticationToken)} returns null if
 * the row's tenant does not match the calling tenant — that is the
 * cross-tenant defence in depth that the {@code TenantAwareOAuth2AuthorizationService}
 * pattern at {@code oauth2/TenantAwareOAuth2AuthorizationService.java} already
 * established.
 *
 * <p>Intent: callers that know which flow they are issuing (signup,
 * resend-verification, forgot-password) use {@link #generateForIntent}.
 * The interface method falls back to {@link OttIntent#VERIFY_EMAIL} so a
 * generic {@code /ott/generate} endpoint defaults to the safe interpretation.
 *
 * <p>Cleanup: Spring's {@code JdbcOneTimeTokenService} schedules an hourly
 * task to delete expired rows. We omit that here — the {@code expires_at}
 * index is in place so a future cleanup job (or a scheduled Modulith
 * application event) can run cheaply.
 */
@Component
public class TenantAwareOneTimeTokenService implements OneTimeTokenService {

    private static final String INSERT_SQL =
        "INSERT INTO one_time_tokens (token_value, username, expires_at, tenant_id, intent) "
            + "VALUES (?, ?, ?, ?, ?)";

    private static final String SELECT_SQL =
        "SELECT token_value, username, expires_at, tenant_id, intent "
            + "FROM one_time_tokens WHERE token_value = ?";

    private static final String DELETE_SQL =
        "DELETE FROM one_time_tokens WHERE token_value = ?";

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;

    @Autowired
    public TenantAwareOneTimeTokenService(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, Clock.systemUTC());
    }

    /** Test seam: inject a clock to drive expiry deterministically. */
    public TenantAwareOneTimeTokenService(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * Generate a token for a specific intent. Used by {@code SignupService},
     * the resend-verification controller, and (in slice #126) forgot-password.
     */
    public TenantOneTimeToken generateForIntent(String username, OttIntent intent) {
        Long tenantId = requireTenantId();
        String tokenValue = UUID.randomUUID().toString();
        Instant expiresAt = clock.instant().plus(java.time.Duration.ofMinutes(60));
        jdbcTemplate.update(INSERT_SQL,
            tokenValue, username, Timestamp.from(expiresAt), tenantId, intent.wire());
        return new TenantOneTimeToken(tokenValue, username, expiresAt, tenantId, intent);
    }

    @Override
    public OneTimeToken generate(GenerateOneTimeTokenRequest request) {
        return generateForIntent(request.getUsername(), OttIntent.VERIFY_EMAIL);
    }

    /**
     * Consume by token value. Returns null when the token is missing, was
     * issued under a different tenant than the current {@link TenantScope},
     * or has expired. Always deletes the row when one was matched, so a
     * second consume of the same value returns null even if the first was
     * a cross-tenant rejection — single-use is enforced storage-side.
     */
    @Override
    @Transactional
    public @Nullable OneTimeToken consume(OneTimeTokenAuthenticationToken authenticationToken) {
        Long tenantId = requireTenantId();
        String tokenValue = authenticationToken.getTokenValue();
        if (tokenValue == null || tokenValue.isBlank()) {
            return null;
        }

        List<TenantOneTimeToken> rows = jdbcTemplate.query(SELECT_SQL, (rs, idx) -> {
            String wire = rs.getString("intent");
            OttIntent intent = OttIntent.fromWire(wire);
            if (intent == null) {
                throw new IllegalStateException("Unknown OTT intent in storage: " + wire);
            }
            return new TenantOneTimeToken(
                rs.getString("token_value"),
                rs.getString("username"),
                rs.getTimestamp("expires_at").toInstant(),
                rs.getLong("tenant_id"),
                intent
            );
        }, tokenValue);

        if (rows.isEmpty()) {
            return null;
        }
        TenantOneTimeToken row = rows.get(0);
        // Always delete on a match — single-use even if the consumer is wrong about tenant.
        jdbcTemplate.update(DELETE_SQL, tokenValue);
        if (!row.tenantId().equals(tenantId)) {
            return null;
        }
        if (clock.instant().isAfter(row.expiresAt())) {
            return null;
        }
        return row;
    }

    private static Long requireTenantId() {
        Long tenantId = TenantScope.tenantId();
        if (tenantId == null) {
            throw new IllegalStateException(
                "TenantAwareOneTimeTokenService called without TenantScope");
        }
        return tenantId;
    }
}
