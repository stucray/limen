package com.stucray.limen.auth.ott;

import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantScope;
import com.stucray.limen.tenant.TenantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.ott.OneTimeToken;
import org.springframework.security.authentication.ott.OneTimeTokenAuthenticationToken;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@Import(TestcontainersConfiguration.class)
@DisplayName("TenantAwareOneTimeTokenService: TenantScope-required generate/consume with cross-tenant isolation")
class TenantAwareOneTimeTokenServiceIntegrationTest {

    @Autowired TenantAwareOneTimeTokenService tokenService;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    Tenant alpha;
    Tenant beta;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM one_time_tokens");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug IN ('ott-alpha', 'ott-beta')");
        alpha = tenantRepository.save(new Tenant(
            null, "ott-alpha", "OTT Alpha", TenantStatus.ACTIVE, LocalDateTime.now()));
        beta = tenantRepository.save(new Tenant(
            null, "ott-beta", "OTT Beta", TenantStatus.ACTIVE, LocalDateTime.now()));
    }

    @Test
    @DisplayName("generateForIntent() outside TenantScope throws IllegalStateException")
    void generateWithoutTenantScopeThrows() {
        assertThatThrownBy(() ->
            tokenService.generateForIntent("alice@example.test", OttIntent.VERIFY_EMAIL))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TenantScope");
    }

    @Test
    @DisplayName("consume() outside TenantScope throws IllegalStateException")
    void consumeWithoutTenantScopeThrows() {
        assertThatThrownBy(() -> tokenService.consume(
            new OneTimeTokenAuthenticationToken("any")))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TenantScope");
    }

    @Test
    @DisplayName("generateForIntent() persists tenant_id and intent on the row")
    void generatePersistsTenantIdAndIntent() {
        TenantOneTimeToken issued = TenantScope.call(alpha.slug(), alpha.id(), () ->
            tokenService.generateForIntent("alice@example.test", OttIntent.VERIFY_EMAIL));

        var row = jdbcTemplate.queryForMap(
            "SELECT tenant_id, intent, username FROM one_time_tokens WHERE token_value = ?",
            issued.tokenValue());
        assertThat(row.get("tenant_id")).isEqualTo(alpha.id());
        assertThat(row.get("intent")).isEqualTo("verify-email");
        assertThat(row.get("username")).isEqualTo("alice@example.test");
    }

    @Test
    @DisplayName("A token issued under alpha consumes successfully under alpha")
    void sameTenantConsumeSucceeds() {
        TenantOneTimeToken issued = TenantScope.call(alpha.slug(), alpha.id(), () ->
            tokenService.generateForIntent("alice@example.test", OttIntent.VERIFY_EMAIL));

        OneTimeToken consumed = TenantScope.call(alpha.slug(), alpha.id(), () ->
            tokenService.consume(new OneTimeTokenAuthenticationToken(issued.tokenValue())));

        assertThat(consumed).isInstanceOf(TenantOneTimeToken.class);
        TenantOneTimeToken tot = (TenantOneTimeToken) consumed;
        assertThat(tot.tenantId()).isEqualTo(alpha.id());
        assertThat(tot.intent()).isEqualTo(OttIntent.VERIFY_EMAIL);
        assertThat(tot.username()).isEqualTo("alice@example.test");
    }

    @Test
    @DisplayName("A token issued under alpha cannot be consumed under beta — and is deleted in the attempt (single-use)")
    void crossTenantConsumeReturnsNullAndDeletes() {
        TenantOneTimeToken issued = TenantScope.call(alpha.slug(), alpha.id(), () ->
            tokenService.generateForIntent("alice@example.test", OttIntent.VERIFY_EMAIL));

        OneTimeToken result = TenantScope.call(beta.slug(), beta.id(), () ->
            tokenService.consume(new OneTimeTokenAuthenticationToken(issued.tokenValue())));

        assertThat(result).isNull();
        Integer remaining = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM one_time_tokens WHERE token_value = ?",
            Integer.class, issued.tokenValue());
        assertThat(remaining).isZero();
    }

    @Test
    @DisplayName("Second consume of the same token returns null — single-use enforced storage-side")
    void secondConsumeReturnsNull() {
        TenantOneTimeToken issued = TenantScope.call(alpha.slug(), alpha.id(), () ->
            tokenService.generateForIntent("alice@example.test", OttIntent.VERIFY_EMAIL));

        OneTimeToken first = TenantScope.call(alpha.slug(), alpha.id(), () ->
            tokenService.consume(new OneTimeTokenAuthenticationToken(issued.tokenValue())));
        OneTimeToken second = TenantScope.call(alpha.slug(), alpha.id(), () ->
            tokenService.consume(new OneTimeTokenAuthenticationToken(issued.tokenValue())));

        assertThat(first).isNotNull();
        assertThat(second).isNull();
    }

    @Test
    @DisplayName("A token whose expires_at is in the past returns null on consume — even under the right tenant")
    void expiredTokenReturnsNull() {
        // Use a fixed-clock service so we can issue at T0 and consume at T0+10min,
        // past the 60-minute default. Wire it directly rather than touching the
        // bean wiring of the SUT — the real bean is unaffected.
        var pastClock = Clock.fixed(Instant.parse("2020-01-01T00:00:00Z"), ZoneOffset.UTC);
        var futureClock = Clock.fixed(Instant.parse("2020-01-01T02:00:00Z"), ZoneOffset.UTC);
        var pastService = new TenantAwareOneTimeTokenService(jdbcTemplate, pastClock);
        var futureService = new TenantAwareOneTimeTokenService(jdbcTemplate, futureClock);

        TenantOneTimeToken issued = TenantScope.call(alpha.slug(), alpha.id(), () ->
            pastService.generateForIntent("alice@example.test", OttIntent.VERIFY_EMAIL));
        OneTimeToken result = TenantScope.call(alpha.slug(), alpha.id(), () ->
            futureService.consume(new OneTimeTokenAuthenticationToken(issued.tokenValue())));

        assertThat(result).isNull();
    }

    @Test
    @DisplayName("consume() with a never-issued token value returns null (no row, no leak)")
    void unknownTokenReturnsNull() {
        OneTimeToken result = TenantScope.call(alpha.slug(), alpha.id(), () ->
            tokenService.consume(new OneTimeTokenAuthenticationToken(UUID.randomUUID().toString())));
        assertThat(result).isNull();
    }
}
