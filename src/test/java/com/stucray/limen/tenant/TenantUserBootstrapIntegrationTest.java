package com.stucray.limen.tenant;

import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.auth.ott.OttIntent;
import com.stucray.limen.auth.ott.TenantAwareOneTimeTokenService;
import com.stucray.limen.tenant.TenantUserBootstrap.OwnerCredentials;
import com.stucray.limen.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@DisplayName("TenantUserBootstrap atomicity + happy path")
class TenantUserBootstrapIntegrationTest {

    @Autowired TenantUserBootstrap bootstrap;
    @Autowired TenantRepository tenantRepository;
    @Autowired UserRepository userRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    @MockitoSpyBean TenantAwareOneTimeTokenService tokenService;

    @BeforeEach
    void cleanCustomerData() {
        jdbcTemplate.execute(
            "DELETE FROM users WHERE tenant_id IN (SELECT id FROM tenants WHERE slug != 'system')");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug != 'system'");
        reset(tokenService);
    }

    @Test
    @DisplayName("Provided-credentials path creates tenant + owner, owner has the supplied password and emailVerified=false")
    void providedPasswordHappyPath() {
        String slug = uniqueSlug();
        Tenant tenant = bootstrap.bootstrap(
            slug, "Acme " + slug, "owner-" + slug + "@example.test",
            new OwnerCredentials.Provided("secret123"));

        // Compare by id rather than full equality: Linux LocalDateTime.now() is
        // nanosecond-precision in memory, Postgres timestamp truncates to micros
        // on persistence (memory note 2026-05-03 / slice-6).
        Tenant reloaded = tenantRepository.findBySlug(slug).orElseThrow();
        assertThat(reloaded.id()).isEqualTo(tenant.id());
        assertThat(reloaded.slug()).isEqualTo(slug);

        var owner = userRepository.findByEmailAndTenantId("owner-" + slug + "@example.test", tenant.id())
            .orElseThrow();
        assertThat(owner.tenantOwner()).isTrue();
        assertThat(owner.emailVerified()).isFalse();
        assertThat(owner.mustChangePassword()).isFalse();
    }

    @Test
    @DisplayName("GenerateRandom path creates tenant + owner with mustChangePassword=true and emailVerified=false")
    void generateRandomHappyPath() {
        String slug = uniqueSlug();
        bootstrap.bootstrap(
            slug, "Acme " + slug, "owner-" + slug + "@example.test",
            new OwnerCredentials.GenerateRandom());

        var owner = userRepository.findByEmailAndTenantId(
                "owner-" + slug + "@example.test",
                tenantRepository.findBySlug(slug).orElseThrow().id())
            .orElseThrow();
        assertThat(owner.mustChangePassword()).isTrue();
        assertThat(owner.tenantOwner()).isTrue();
        assertThat(owner.emailVerified()).isFalse();
    }

    @Test
    @DisplayName("If OTT generation throws, the bootstrap rolls back: no orphan tenant or owner row")
    void ottFailureRollsBackTenantAndOwner() {
        String slug = uniqueSlug();
        String email = "owner-" + slug + "@example.test";

        doThrow(new IllegalStateException("simulated OTT outage"))
            .when(tokenService)
            .generateForIntent(eq(email), any(OttIntent.class));

        assertThatThrownBy(() -> bootstrap.bootstrap(
            slug, "Acme " + slug, email, new OwnerCredentials.GenerateRandom()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("simulated OTT outage");

        assertThat(tenantRepository.findBySlug(slug)).isEmpty();
        Integer userCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email);
        assertThat(userCount).isZero();
    }

    private static String uniqueSlug() {
        return "boot-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
