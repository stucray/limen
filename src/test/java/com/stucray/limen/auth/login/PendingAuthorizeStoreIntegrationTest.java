package com.stucray.limen.auth.login;

import com.stucray.limen.TestcontainersConfiguration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("PendingAuthorizeStore: opaque, single-use, tenant-bound, TTL-expiring authorize stash")
class PendingAuthorizeStoreIntegrationTest {

    private static final Instant T0 = Instant.parse("2026-01-01T00:00:00Z");
    private static final String AUTHORIZE_URL =
        "/oauth2/authorize?response_type=code&client_id=demo&redirect_uri=http://127.0.0.1/cb";

    @Autowired JdbcTemplate jdbcTemplate;

    PendingAuthorizeStore store;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM pending_authorize");
        store = new PendingAuthorizeStore(jdbcTemplate, Clock.fixed(T0, ZoneOffset.UTC));
    }

    @Test
    @DisplayName("stash then consume under the same tenant returns the authorize URL and deletes the row")
    void stashThenConsumeReturnsUrlAndDeletes() {
        String ref = store.stash("alpha", AUTHORIZE_URL);

        Optional<String> consumed = store.consume(ref, "alpha");

        assertThat(consumed).contains(AUTHORIZE_URL);
        assertThat(rowCount(ref)).isZero();
    }

    @Test
    @DisplayName("second consume of the same reference returns empty (single-use)")
    void secondConsumeReturnsEmpty() {
        String ref = store.stash("alpha", AUTHORIZE_URL);

        assertThat(store.consume(ref, "alpha")).isPresent();
        assertThat(store.consume(ref, "alpha")).isEmpty();
    }

    @Test
    @DisplayName("consume under a different tenant returns empty AND deletes the row (single-use even on mismatch)")
    void crossTenantConsumeReturnsEmptyAndDeletes() {
        String ref = store.stash("alpha", AUTHORIZE_URL);

        assertThat(store.consume(ref, "beta")).isEmpty();
        assertThat(rowCount(ref)).isZero();
    }

    @Test
    @DisplayName("a reference past its TTL returns empty even under the right tenant")
    void expiredReferenceReturnsEmpty() {
        String ref = store.stash("alpha", AUTHORIZE_URL);

        PendingAuthorizeStore future = new PendingAuthorizeStore(
            jdbcTemplate, Clock.fixed(T0.plus(PendingAuthorizeStore.TTL).plus(Duration.ofMinutes(1)), ZoneOffset.UTC));

        assertThat(future.consume(ref, "alpha")).isEmpty();
    }

    @Test
    @DisplayName("deleteExpired removes expired rows and leaves live ones")
    void deleteExpiredRemovesOnlyExpired() {
        String expiredRef = store.stash("alpha", AUTHORIZE_URL);

        // A row stashed 90 minutes later is still live when the sweep runs at T0+120m.
        PendingAuthorizeStore later = new PendingAuthorizeStore(
            jdbcTemplate, Clock.fixed(T0.plus(Duration.ofMinutes(90)), ZoneOffset.UTC));
        String liveRef = later.stash("alpha", AUTHORIZE_URL);

        PendingAuthorizeStore sweepClock = new PendingAuthorizeStore(
            jdbcTemplate, Clock.fixed(T0.plus(Duration.ofMinutes(120)), ZoneOffset.UTC));
        int removed = sweepClock.deleteExpired();

        assertThat(removed).isEqualTo(1);
        assertThat(rowCount(expiredRef)).isZero();
        assertThat(rowCount(liveRef)).isEqualTo(1);
    }

    @Test
    @DisplayName("consume of an unknown or blank reference returns empty")
    void unknownOrBlankReferenceReturnsEmpty() {
        assertThat(store.consume("never-issued", "alpha")).isEmpty();
        assertThat(store.consume("", "alpha")).isEmpty();
        assertThat(store.consume(null, "alpha")).isEmpty();
    }

    private int rowCount(String ref) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM pending_authorize WHERE ref = ?", Integer.class, ref);
        return count == null ? 0 : count;
    }
}
