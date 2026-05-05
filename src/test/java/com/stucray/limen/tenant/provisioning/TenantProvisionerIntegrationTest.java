package com.stucray.limen.tenant.provisioning;

import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.auth.ott.OttIntent;
import com.stucray.limen.auth.ott.TenantAwareOneTimeTokenService;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantStatus;
import com.stucray.limen.tenant.provisioning.TenantProvisioner.NewTenantRequest;
import com.stucray.limen.tenant.provisioning.TenantProvisioner.Result;
import com.stucray.limen.user.User;
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
@DisplayName("TenantProvisioner: validation, atomic happy path, rollback, field-name mapping")
class TenantProvisionerIntegrationTest {

    @Autowired TenantProvisioner provisioner;
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
    @DisplayName("Signup form happy path: tenant + owner + signing key + verification OTT, owner has provided password and mustChangePassword=false")
    void signupFormProvisionsTenantAndOwner() {
        String slug = uniqueSlug();
        String email = "owner-" + slug + "@example.test";

        Result result = provisioner.provision(NewTenantRequest.fromSignupForm(
            slug, "Acme " + slug, email, "secret123"));

        assertThat(result).isInstanceOf(Result.Provisioned.class);
        Result.Provisioned provisioned = (Result.Provisioned) result;
        assertThat(provisioned.ownerEmail()).isEqualTo(email);
        assertThat(provisioned.tenant().slug()).isEqualTo(slug);
        assertThat(provisioned.tenant().status()).isEqualTo(TenantStatus.ACTIVE);

        User owner = userRepository.findByEmailAndTenantId(email, provisioned.tenant().id()).orElseThrow();
        assertThat(owner.tenantOwner()).isTrue();
        assertThat(owner.emailVerified()).isFalse();
        assertThat(owner.mustChangePassword()).isFalse();

        Long signingKeys = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM tenant_signing_key WHERE tenant_id = ? AND status = 'ACTIVE'",
            Long.class, provisioned.tenant().id());
        assertThat(signingKeys).isOne();
    }

    @Test
    @DisplayName("System-admin form happy path: owner has random placeholder password and mustChangePassword=true")
    void systemAdminFormProvisionsTenantAndOwnerWithForcedChange() {
        String slug = uniqueSlug();
        String email = "owner-" + slug + "@example.test";

        Result result = provisioner.provision(NewTenantRequest.fromSystemAdminForm(
            slug, "Acme " + slug, email));

        assertThat(result).isInstanceOf(Result.Provisioned.class);
        User owner = userRepository.findByEmailAndTenantId(
                email, ((Result.Provisioned) result).tenant().id())
            .orElseThrow();
        assertThat(owner.mustChangePassword()).isTrue();
        assertThat(owner.tenantOwner()).isTrue();
        assertThat(owner.emailVerified()).isFalse();
    }

    @Test
    @DisplayName("OTT generation failure rolls back the entire provision: no tenant, no owner, no signing key")
    void ottFailureRollsBackEverything() {
        String slug = uniqueSlug();
        String email = "owner-" + slug + "@example.test";

        doThrow(new IllegalStateException("simulated OTT outage"))
            .when(tokenService)
            .generateForIntent(eq(email), any(OttIntent.class));

        assertThatThrownBy(() -> provisioner.provision(NewTenantRequest.fromSystemAdminForm(
            slug, "Acme " + slug, email)))
            .isInstanceOf(IllegalStateException.class)
            .hasMessage("simulated OTT outage");

        assertThat(tenantRepository.findBySlug(slug)).isEmpty();
        Integer userCount = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM users WHERE email = ?", Integer.class, email);
        assertThat(userCount).isZero();
    }

    @Test
    @DisplayName("Reserved slug is rejected; field name follows the signup form's input names")
    void rejectsReservedSlugUnderSignupFieldNames() {
        Result result = provisioner.provision(NewTenantRequest.fromSignupForm(
            "system", "Acme", "alice@example.test", "secret123"));
        assertThat(result).isEqualTo(new Result.Rejected("slug", "That slug is reserved and cannot be used"));
    }

    @Test
    @DisplayName("Email rejection under signup uses field name 'email'")
    void emailErrorFieldUnderSignupIsEmail() {
        Result result = provisioner.provision(NewTenantRequest.fromSignupForm(
            uniqueSlug(), "Acme", "not-an-email", "secret123"));
        assertThat(result).isInstanceOf(Result.Rejected.class);
        assertThat(((Result.Rejected) result).field()).isEqualTo("email");
    }

    @Test
    @DisplayName("Email rejection under system-admin uses field name 'ownerEmail' — no caller rebind needed")
    void emailErrorFieldUnderSystemAdminIsOwnerEmail() {
        Result result = provisioner.provision(NewTenantRequest.fromSystemAdminForm(
            uniqueSlug(), "Acme", "not-an-email"));
        assertThat(result).isInstanceOf(Result.Rejected.class);
        assertThat(((Result.Rejected) result).field()).isEqualTo("ownerEmail");
    }

    @Test
    @DisplayName("Display-name rejection under signup uses 'organizationName'; under system-admin uses 'displayName'")
    void displayNameFieldNameTracksTheCaller() {
        Result signupResult = provisioner.provision(NewTenantRequest.fromSignupForm(
            uniqueSlug(), "  ", "alice@example.test", "secret123"));
        assertThat(((Result.Rejected) signupResult).field()).isEqualTo("organizationName");

        Result sysAdminResult = provisioner.provision(NewTenantRequest.fromSystemAdminForm(
            uniqueSlug(), "  ", "alice@example.test"));
        assertThat(((Result.Rejected) sysAdminResult).field()).isEqualTo("displayName");
    }

    @Test
    @DisplayName("Blank password under signup is rejected with field='password'")
    void blankPasswordUnderSignupIsRejected() {
        Result result = provisioner.provision(NewTenantRequest.fromSignupForm(
            uniqueSlug(), "Acme", "alice@example.test", ""));
        assertThat(result).isEqualTo(new Result.Rejected("password", "Password is required"));
    }

    @Test
    @DisplayName("Duplicate slug is rejected")
    void duplicateSlugIsRejected() {
        String slug = uniqueSlug();
        Result first = provisioner.provision(NewTenantRequest.fromSignupForm(
            slug, "First", "first-" + slug + "@example.test", "secret123"));
        assertThat(first).isInstanceOf(Result.Provisioned.class);

        Result second = provisioner.provision(NewTenantRequest.fromSignupForm(
            slug, "Second", "second-" + slug + "@example.test", "secret123"));
        assertThat(second).isEqualTo(new Result.Rejected("slug", "That slug is already taken"));
    }

    private static String uniqueSlug() {
        return "tp-" + UUID.randomUUID().toString().substring(0, 8);
    }
}
