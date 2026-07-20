package com.stucray.limen.auth.login;

import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Optional;

/**
 * Durable, short-lived store for pending OAuth2 {@code /authorize} requests
 * (issue #327). Complements the in-session {@code HttpSessionRequestCache} that
 * {@link PostLoginIntents#resumeOAuth2Authorize} consults first: when the
 * session (and its SavedRequest) has been evicted — the classic "sat on the
 * login page past the timeout" case — the login URL still carries an opaque
 * {@code ?ref=} to a row here, so the authorize flow can be reconstructed and
 * replayed.
 *
 * <p>The stashed parameters are non-secret (they were already in the client's
 * query string) and {@code redirect_uri} is re-validated by SAS at
 * {@code /authorize}, so this is a resume-convenience mechanism, not a trust
 * boundary. References are opaque, high-entropy, single-use, and tenant-bound.
 *
 * <p>Storage shape and lifecycle mirror
 * {@code TenantAwareOneTimeTokenService}: a {@link Clock} test seam drives
 * expiry deterministically, {@link #consume} deletes on any match so a
 * reference is single-use even across a tenant-mismatch rejection, and
 * {@link #deleteExpired} backs the scheduled {@link PendingAuthorizeSweep}.
 */
@Component
public class PendingAuthorizeStore {

    /**
     * TTL for a stashed authorize request. Deliberately longer than the
     * 30-minute HTTP session timeout so an idle-then-login user still resumes;
     * short enough to bound a stale row. Expiry degrades softly — a lapsed
     * reference just falls through to the neutral end-user home (#327) — so this
     * is a resume-convenience window, not a safety margin.
     */
    static final Duration TTL = Duration.ofMinutes(60);

    private static final int REF_BYTES = 32;

    private static final String INSERT_SQL =
        "INSERT INTO pending_authorize (ref, tenant_slug, authorize_url, expires_at) VALUES (?, ?, ?, ?)";
    private static final String SELECT_SQL =
        "SELECT tenant_slug, authorize_url, expires_at FROM pending_authorize WHERE ref = ?";
    private static final String DELETE_SQL =
        "DELETE FROM pending_authorize WHERE ref = ?";
    private static final String DELETE_EXPIRED_SQL =
        "DELETE FROM pending_authorize WHERE expires_at <= ?";

    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final SecureRandom secureRandom = new SecureRandom();

    @Autowired
    PendingAuthorizeStore(JdbcTemplate jdbcTemplate) {
        this(jdbcTemplate, Clock.systemUTC());
    }

    /** Test seam: inject a clock to drive expiry deterministically. */
    PendingAuthorizeStore(JdbcTemplate jdbcTemplate, Clock clock) {
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
    }

    /**
     * Stash a pending authorize request and return an opaque, single-use
     * reference to carry on the login URL. {@code authorizeUrl} is the
     * tenant-stripped request as SAS sees it (e.g. {@code /oauth2/authorize?…});
     * {@link #consume} replays it under the authenticated principal's tenant.
     */
    public String stash(String tenantSlug, String authorizeUrl) {
        String ref = newRef();
        Instant expiresAt = clock.instant().plus(TTL);
        jdbcTemplate.update(INSERT_SQL, ref, tenantSlug, authorizeUrl, Timestamp.from(expiresAt));
        return ref;
    }

    /**
     * Consume a stashed authorize URL by reference. Returns empty when the
     * reference is unknown, was stashed under a different tenant than the
     * caller, or has expired. Always deletes the row on a match, so the
     * reference is single-use even across a tenant-mismatch rejection.
     */
    @Transactional
    public Optional<String> consume(@Nullable String ref, String tenantSlug) {
        if (ref == null || ref.isBlank()) {
            return Optional.empty();
        }
        List<Row> rows = jdbcTemplate.query(SELECT_SQL, (rs, i) -> new Row(
            rs.getString("tenant_slug"),
            rs.getString("authorize_url"),
            rs.getTimestamp("expires_at").toInstant()
        ), ref);
        if (rows.isEmpty()) {
            return Optional.empty();
        }
        Row row = rows.get(0);
        // Always delete on a match — single-use even if the tenant is wrong.
        jdbcTemplate.update(DELETE_SQL, ref);
        if (!row.tenantSlug().equals(tenantSlug)) {
            return Optional.empty();
        }
        if (!clock.instant().isBefore(row.expiresAt())) {
            return Optional.empty();
        }
        return Optional.of(row.authorizeUrl());
    }

    /** Delete every expired row. Driven by {@link PendingAuthorizeSweep}. Returns the count removed. */
    public int deleteExpired() {
        return jdbcTemplate.update(DELETE_EXPIRED_SQL, Timestamp.from(clock.instant()));
    }

    private String newRef() {
        byte[] bytes = new byte[REF_BYTES];
        secureRandom.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    private record Row(String tenantSlug, String authorizeUrl, Instant expiresAt) {}
}
