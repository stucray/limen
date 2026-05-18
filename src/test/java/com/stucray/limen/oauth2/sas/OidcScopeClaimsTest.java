package com.stucray.limen.oauth2.sas;

import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantStatus;
import com.stucray.limen.user.TenantUserDetails;
import com.stucray.limen.user.User;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("OidcScopeClaims emits standard claims based on granted scopes and the principal")
class OidcScopeClaimsTest {

    private static final Tenant TENANT = new Tenant(
        7L, "acme", "Acme Inc", TenantStatus.ACTIVE, LocalDateTime.now()
    );

    @Test
    @DisplayName("Granted email scope on a TenantUserDetails principal adds email + email_verified to the claim set")
    void emailScopeAddsEmailClaims() {
        User user = userWith("alice@acme.test", true);
        JwtClaimsSet.Builder claims = baseClaims();

        OidcScopeClaims.addClaimsForGrantedScopes(
            claims, Set.of("openid", "email"), new TenantUserDetails(user, TENANT)
        );

        Map<String, Object> built = claims.build().getClaims();
        assertThat(built).containsEntry("email", "alice@acme.test");
        assertThat(built).containsEntry("email_verified", true);
    }

    @Test
    @DisplayName("email_verified emits the User.emailVerified boolean — not hardcoded true")
    void emailVerifiedReflectsUserStateAsBoolean() {
        User unverifiedUser = userWith("bob@acme.test", false);
        JwtClaimsSet.Builder claims = baseClaims();

        OidcScopeClaims.addClaimsForGrantedScopes(
            claims, Set.of("openid", "email"), new TenantUserDetails(unverifiedUser, TENANT)
        );

        assertThat(claims.build().getClaims()).containsEntry("email_verified", false);
    }

    @Test
    @DisplayName("openid scope without email scope does NOT add email/email_verified")
    void emailScopeNotGrantedOmitsEmailClaims() {
        User user = userWith("alice@acme.test", true);
        JwtClaimsSet.Builder claims = baseClaims();

        OidcScopeClaims.addClaimsForGrantedScopes(
            claims, Set.of("openid"), new TenantUserDetails(user, TENANT)
        );

        Map<String, Object> built = claims.build().getClaims();
        assertThat(built).doesNotContainKey("email");
        assertThat(built).doesNotContainKey("email_verified");
    }

    @Test
    @DisplayName("Non-TenantUserDetails principal (e.g. client_credentials flow with no end user) is a no-op even when email scope is granted")
    void nonUserPrincipalIsNoOp() {
        JwtClaimsSet.Builder claims = baseClaims();

        OidcScopeClaims.addClaimsForGrantedScopes(
            claims, Set.of("openid", "email"), "some-client-id-as-principal"
        );

        Map<String, Object> built = claims.build().getClaims();
        assertThat(built).doesNotContainKey("email");
        assertThat(built).doesNotContainKey("email_verified");
    }

    @Test
    @DisplayName("Null principal is a no-op (defensive — should not happen in production paths)")
    void nullPrincipalIsNoOp() {
        JwtClaimsSet.Builder claims = baseClaims();

        OidcScopeClaims.addClaimsForGrantedScopes(claims, Set.of("openid", "email"), null);

        assertThat(claims.build().getClaims()).doesNotContainKey("email");
    }

    @Test
    @DisplayName("Granted profile scope on a user with a non-null fullName adds the name claim")
    void profileScopeAddsNameClaim() {
        User user = userWith("alice@acme.test", true).withFullName("Alice Example");
        JwtClaimsSet.Builder claims = baseClaims();

        OidcScopeClaims.addClaimsForGrantedScopes(
            claims, Set.of("openid", "profile"), new TenantUserDetails(user, TENANT)
        );

        assertThat(claims.build().getClaims()).containsEntry("name", "Alice Example");
    }

    @Test
    @DisplayName("Granted profile scope on a user with a null fullName OMITS the name claim — does not emit name: null or name: empty")
    void profileScopeWithNullFullNameOmitsNameClaim() {
        User user = userWith("alice@acme.test", true);  // fullName defaults to null
        JwtClaimsSet.Builder claims = baseClaims();

        OidcScopeClaims.addClaimsForGrantedScopes(
            claims, Set.of("openid", "profile"), new TenantUserDetails(user, TENANT)
        );

        assertThat(claims.build().getClaims()).doesNotContainKey("name");
    }

    @Test
    @DisplayName("openid scope without profile scope does NOT add name even if fullName is set")
    void profileScopeNotGrantedOmitsNameClaim() {
        User user = userWith("alice@acme.test", true).withFullName("Alice Example");
        JwtClaimsSet.Builder claims = baseClaims();

        OidcScopeClaims.addClaimsForGrantedScopes(
            claims, Set.of("openid"), new TenantUserDetails(user, TENANT)
        );

        assertThat(claims.build().getClaims()).doesNotContainKey("name");
    }

    @Test
    @DisplayName("Granted scope=openid email profile populates all three sets of claims in one pass")
    void allScopesGrantedPopulatesAllClaims() {
        User user = userWith("alice@acme.test", true).withFullName("Alice Example");
        JwtClaimsSet.Builder claims = baseClaims();

        OidcScopeClaims.addClaimsForGrantedScopes(
            claims, Set.of("openid", "email", "profile"), new TenantUserDetails(user, TENANT)
        );

        Map<String, Object> built = claims.build().getClaims();
        assertThat(built).containsEntry("email", "alice@acme.test");
        assertThat(built).containsEntry("email_verified", true);
        assertThat(built).containsEntry("name", "Alice Example");
    }

    private static JwtClaimsSet.Builder baseClaims() {
        return JwtClaimsSet.builder().subject("placeholder-sub");
    }

    private static User userWith(String email, boolean emailVerified) {
        return new User(
            42L, TENANT.id(), email, "$2a$10$hash",
            true, false, false, emailVerified, LocalDateTime.now()
        );
    }
}
