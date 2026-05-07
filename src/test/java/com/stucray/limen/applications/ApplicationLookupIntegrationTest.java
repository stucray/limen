package com.stucray.limen.applications;

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

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@DisplayName("ApplicationLookup (tenant-scoped Application access)")
class ApplicationLookupIntegrationTest {

    @Autowired ApplicationLookup applicationLookup;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    Tenant tenantA;
    Tenant tenantB;
    Application appA;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM client_membership");
        jdbcTemplate.execute("DELETE FROM application_membership_role");
        jdbcTemplate.execute("DELETE FROM application_membership");
        jdbcTemplate.execute("DELETE FROM role");
        jdbcTemplate.execute("DELETE FROM applications");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug IN ('lookup-a', 'lookup-b')");

        tenantA = tenantRepository.save(new Tenant(null, "lookup-a", "Lookup A", TenantStatus.ACTIVE, LocalDateTime.now()));
        tenantB = tenantRepository.save(new Tenant(null, "lookup-b", "Lookup B", TenantStatus.ACTIVE, LocalDateTime.now()));
        appA = applicationRepository.save(new Application(null, tenantA.id(), "App A", null, LocalDateTime.now()));
    }

    @Test
    @DisplayName("Application exists in the asking tenant: returns the entity")
    void returnsApplicationWhenInTenant() {
        Application found = applicationLookup.require(appA.id(), tenantA.id());

        assertThat(found.id()).isEqualTo(appA.id());
        assertThat(found.tenantId()).isEqualTo(tenantA.id());
    }

    @Test
    @DisplayName("Application id does not exist: throws 'Application not found'")
    void throwsWhenAbsent() {
        assertThatThrownBy(() -> applicationLookup.require(999_999L, tenantA.id()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Application not found");
    }

    @Test
    @DisplayName("Application exists but in a different tenant: throws 'Application not found' (cross-tenant rejection)")
    void throwsWhenInDifferentTenant() {
        // appA belongs to tenantA. tenantB asking for it must get the same
        // 'not found' message — never a leak that reveals the real owner.
        assertThatThrownBy(() -> applicationLookup.require(appA.id(), tenantB.id()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("Application not found");
    }
}
