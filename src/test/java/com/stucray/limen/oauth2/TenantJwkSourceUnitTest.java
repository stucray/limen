package com.stucray.limen.oauth2;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.stucray.limen.security.SigningKeyStore;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantScope;
import com.stucray.limen.tenant.TenantStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContext;
import org.springframework.security.oauth2.server.authorization.context.AuthorizationServerContextHolder;
import org.springframework.security.oauth2.server.authorization.settings.AuthorizationServerSettings;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.verifyNoInteractions;

/**
 * Branch-coverage unit tests for {@link TenantJwkSource}. Exercises every
 * arm of {@code resolveTenantId} plus the no-active-key throw — these
 * branches don't surface through the existing integration tests, which only
 * hit the issuer-with-slug → key happy path.
 */
@ExtendWith(MockitoExtension.class)
class TenantJwkSourceUnitTest {

    @Mock TenantRepository tenantRepository;
    @Mock SigningKeyStore signingKeyStore;

    TenantJwkSource source;
    Tenant alpha;
    RSAKey alphaKey;

    @BeforeEach
    void setUp() throws JOSEException {
        source = new TenantJwkSource(tenantRepository, signingKeyStore);
        alpha = new Tenant(1L, "alpha", "Alpha", TenantStatus.ACTIVE, LocalDateTime.now());
        alphaKey = new RSAKeyGenerator(2048).keyID("alpha-key").keyUse(KeyUse.SIGNATURE).generate();
    }

    @AfterEach
    void clearContext() {
        AuthorizationServerContextHolder.resetContext();
    }

    @Test
    void issuerWithTenantSegmentResolvesViaRepositoryAndReturnsKey() throws Exception {
        setIssuer("https://auth.example.com/t/alpha");
        given(tenantRepository.findBySlug("alpha")).willReturn(Optional.of(alpha));
        given(signingKeyStore.getActiveSigningKey(1L)).willReturn(alphaKey);

        List<JWK> jwks = source.get(new JWKSelector(new com.nimbusds.jose.jwk.JWKMatcher.Builder().build()), null);

        assertThat(jwks).extracting(JWK::getKeyID).containsExactly("alpha-key");
    }

    @Test
    void issuerWithoutTenantSegmentFallsBackToTenantScope() throws Exception {
        setIssuer("https://auth.example.com");
        given(signingKeyStore.getActiveSigningKey(1L)).willReturn(alphaKey);

        List<JWK> jwks = TenantScope.call("alpha", 1L, () ->
            source.get(new JWKSelector(new com.nimbusds.jose.jwk.JWKMatcher.Builder().build()), null)
        );

        assertThat(jwks).extracting(JWK::getKeyID).containsExactly("alpha-key");
        verifyNoInteractions(tenantRepository);
    }

    @Test
    void issuerWithUnknownSlugFallsBackToTenantScope() throws Exception {
        setIssuer("https://auth.example.com/t/ghost");
        given(tenantRepository.findBySlug("ghost")).willReturn(Optional.empty());
        given(signingKeyStore.getActiveSigningKey(1L)).willReturn(alphaKey);

        List<JWK> jwks = TenantScope.call("alpha", 1L, () ->
            source.get(new JWKSelector(new com.nimbusds.jose.jwk.JWKMatcher.Builder().build()), null)
        );

        assertThat(jwks).extracting(JWK::getKeyID).containsExactly("alpha-key");
    }

    @Test
    void noContextAndNoTenantScopeThrowsIllegalState() {
        assertThatThrownBy(() ->
            source.get(new JWKSelector(new com.nimbusds.jose.jwk.JWKMatcher.Builder().build()), null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no tenant context");
        verifyNoInteractions(tenantRepository, signingKeyStore);
    }

    @Test
    void resolvedTenantWithNoActiveKeyThrowsIllegalState() {
        setIssuer("https://auth.example.com/t/alpha");
        given(tenantRepository.findBySlug("alpha")).willReturn(Optional.of(alpha));
        given(signingKeyStore.getActiveSigningKey(1L)).willReturn(null);

        assertThatThrownBy(() ->
            source.get(new JWKSelector(new com.nimbusds.jose.jwk.JWKMatcher.Builder().build()), null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No active signing key for tenant 1");
    }

    private static void setIssuer(String issuer) {
        AuthorizationServerSettings settings = AuthorizationServerSettings.builder().issuer(issuer).build();
        AuthorizationServerContextHolder.setContext(new AuthorizationServerContext() {
            @Override public String getIssuer() { return issuer; }
            @Override public AuthorizationServerSettings getAuthorizationServerSettings() { return settings; }
        });
    }
}
