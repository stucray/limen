package com.stucray.limen.auth;

import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.web.authentication.rememberme.PersistentRememberMeToken;

import java.time.LocalDateTime;
import java.util.Date;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@DisplayName("TenantPersistentTokenRepository: tenant-scoped CRUD")
class TenantPersistentTokenRepositoryIntegrationTest {

    @Autowired TenantPersistentTokenRepository repo;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    Tenant alpha;
    Tenant beta;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM persistent_logins");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug NOT IN ('system')");
        alpha = tenantRepository.save(new Tenant(null, "alpha-rmtok", "Alpha", TenantStatus.ACTIVE, LocalDateTime.now()));
        beta = tenantRepository.save(new Tenant(null, "beta-rmtok", "Beta", TenantStatus.ACTIVE, LocalDateTime.now()));
    }

    @Test
    @DisplayName("getTokenForSeries returns the row only when called with the matching tenant_id")
    void createAndGetIsTenantScoped() {
        repo.createNewToken(new PersistentRememberMeToken("alice@example.test", "series-1", "tok-1", new Date()), alpha.id());
        repo.createNewToken(new PersistentRememberMeToken("alice@example.test", "series-2", "tok-2", new Date()), beta.id());

        TenantPersistentRememberMeToken alphaRow = repo.getTokenForSeries("series-1", alpha.id());
        assertThat(alphaRow).isNotNull();
        // Spring's PersistentRememberMeToken.getUsername() returns the email value in this codebase
        assertThat(alphaRow.getUsername()).isEqualTo("alice@example.test");
        assertThat(alphaRow.getTenantId()).isEqualTo(alpha.id());

        // Same series under wrong tenant returns null
        assertThat(repo.getTokenForSeries("series-1", beta.id())).isNull();
    }

    @Test
    @DisplayName("updateToken under the wrong tenant_id is a no-op; under the right one it replaces the token")
    void updateIsTenantScoped() {
        repo.createNewToken(new PersistentRememberMeToken("alice@example.test", "series-3", "old-tok", new Date()), alpha.id());

        // Update under wrong tenant — no-op
        repo.updateToken("series-3", beta.id(), "wrong-tok", new Date());
        assertThat(repo.getTokenForSeries("series-3", alpha.id()).getTokenValue()).isEqualTo("old-tok");

        // Update under correct tenant — replaces
        repo.updateToken("series-3", alpha.id(), "new-tok", new Date());
        assertThat(repo.getTokenForSeries("series-3", alpha.id()).getTokenValue()).isEqualTo("new-tok");
    }

    @Test
    @DisplayName("removeUserTokens deletes only the rows belonging to the named tenant")
    void removeUserTokensIsTenantScoped() {
        repo.createNewToken(new PersistentRememberMeToken("alice@example.test", "series-4", "tok-a", new Date()), alpha.id());
        repo.createNewToken(new PersistentRememberMeToken("alice@example.test", "series-5", "tok-b", new Date()), beta.id());

        repo.removeUserTokens("alice@example.test", alpha.id());

        assertThat(repo.getTokenForSeries("series-4", alpha.id())).isNull();
        // Beta's row survives
        assertThat(repo.getTokenForSeries("series-5", beta.id())).isNotNull();
    }

    @Test
    @DisplayName("Two tenants can store rows for the same series — (tenant_id, series) is the composite PK")
    void sameSeriesAcrossTenantsCoexist() {
        // (tenant_id, series) is the PK so both rows can persist with the same series.
        repo.createNewToken(new PersistentRememberMeToken("alice@example.test", "shared", "tok-a", new Date()), alpha.id());
        repo.createNewToken(new PersistentRememberMeToken("alice@example.test", "shared", "tok-b", new Date()), beta.id());

        assertThat(repo.getTokenForSeries("shared", alpha.id()).getTokenValue()).isEqualTo("tok-a");
        assertThat(repo.getTokenForSeries("shared", beta.id()).getTokenValue()).isEqualTo("tok-b");
    }
}
