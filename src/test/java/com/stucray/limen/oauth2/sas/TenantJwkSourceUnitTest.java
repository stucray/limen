package com.stucray.limen.oauth2.sas;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.KeyType;
import com.nimbusds.jose.jwk.KeyUse;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.stucray.limen.security.SigningKeyReader;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantScope;
import com.stucray.limen.tenant.TenantStatus;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
 * arm of {@code resolveTenantId} plus the no-active-key throw and the
 * ACTIVE-first ordering invariant — the latter is load-bearing for signing
 * during a rotation grace window.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("TenantJwkSource: branch coverage for resolveTenantId() arms, no-key error, and ACTIVE-first ordering")
class TenantJwkSourceUnitTest {

    @Mock TenantRepository tenantRepository;
    @Mock SigningKeyReader signingKeys;

    TenantJwkSource source;
    Tenant alpha;
    RSAKey activeKey;
    RSAKey retiredKey;

    @BeforeEach
    void setUp() throws JOSEException {
        source = new TenantJwkSource(tenantRepository, signingKeys);
        alpha = new Tenant(1L, "alpha", "Alpha", TenantStatus.ACTIVE, LocalDateTime.now());
        activeKey = new RSAKeyGenerator(2048).keyID("active-kid").keyUse(KeyUse.SIGNATURE).generate();
        retiredKey = new RSAKeyGenerator(2048).keyID("retired-kid").keyUse(KeyUse.SIGNATURE).generate();
    }

    @AfterEach
    void clearContext() {
        AuthorizationServerContextHolder.resetContext();
    }

    @Test
    @DisplayName("Issuer URL containing /t/{slug} resolves the tenant via the repo and returns that tenant's signing keys (match-all → JWKS path)")
    void issuerWithTenantSegmentResolvesViaRepositoryAndReturnsKeys() throws Exception {
        setIssuer("https://auth.example.com/t/alpha");
        given(tenantRepository.findBySlug("alpha")).willReturn(Optional.of(alpha));
        given(signingKeys.getJwkSet(1L)).willReturn(new JWKSet(activeKey.toPublicJWK()));

        List<JWK> jwks = source.get(matchAllSelector(), null);

        assertThat(jwks).extracting(JWK::getKeyID).containsExactly("active-kid");
    }

    @Test
    @DisplayName("Issuer URL without a /t/{slug} segment falls back to the bound TenantScope and skips the repo lookup entirely")
    void issuerWithoutTenantSegmentFallsBackToTenantScope() throws Exception {
        setIssuer("https://auth.example.com");
        given(signingKeys.getJwkSet(1L)).willReturn(new JWKSet(activeKey.toPublicJWK()));

        List<JWK> jwks = TenantScope.call("alpha", 1L, () -> source.get(matchAllSelector(), null));

        assertThat(jwks).extracting(JWK::getKeyID).containsExactly("active-kid");
        verifyNoInteractions(tenantRepository);
    }

    @Test
    @DisplayName("Issuer URL with an unknown slug falls back to the bound TenantScope rather than blowing up")
    void issuerWithUnknownSlugFallsBackToTenantScope() throws Exception {
        setIssuer("https://auth.example.com/t/ghost");
        given(tenantRepository.findBySlug("ghost")).willReturn(Optional.empty());
        given(signingKeys.getJwkSet(1L)).willReturn(new JWKSet(activeKey.toPublicJWK()));

        List<JWK> jwks = TenantScope.call("alpha", 1L, () -> source.get(matchAllSelector(), null));

        assertThat(jwks).extracting(JWK::getKeyID).containsExactly("active-kid");
    }

    @Test
    @DisplayName("With neither AuthorizationServerContext nor TenantScope set, get() throws IllegalStateException and never touches the repo or key store")
    void noContextAndNoTenantScopeThrowsIllegalState() {
        assertThatThrownBy(() -> source.get(matchAllSelector(), null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no tenant context");
        verifyNoInteractions(tenantRepository, signingKeys);
    }

    @Test
    @DisplayName("Match-all selector with empty key set throws IllegalStateException naming the tenant id")
    void matchAllWithNoKeysThrowsIllegalState() {
        setIssuer("https://auth.example.com/t/alpha");
        given(tenantRepository.findBySlug("alpha")).willReturn(Optional.of(alpha));
        given(signingKeys.getJwkSet(1L)).willReturn(new JWKSet(List.of()));

        assertThatThrownBy(() -> source.get(matchAllSelector(), null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No signing keys for tenant 1");
    }

    @Test
    @DisplayName("Signing-style selector (constrained by keyType+keyUse+algorithm) with no active key throws IllegalStateException")
    void signingPathWithNoActiveKeyThrowsIllegalState() {
        setIssuer("https://auth.example.com/t/alpha");
        given(tenantRepository.findBySlug("alpha")).willReturn(Optional.of(alpha));
        given(signingKeys.getActiveSigningKey(1L)).willReturn(null);

        assertThatThrownBy(() -> source.get(signingSelector(), null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("No active signing key for tenant 1");
    }

    @Test
    @DisplayName("Match-all selector returns ACTIVE + RETIRED keys (public-only) so the JWKS endpoint covers a rotation grace window")
    void matchAllReturnsAllPublicKeysIncludingRetired() throws Exception {
        setIssuer("https://auth.example.com/t/alpha");
        given(tenantRepository.findBySlug("alpha")).willReturn(Optional.of(alpha));
        given(signingKeys.getJwkSet(1L)).willReturn(new JWKSet(List.of(
            activeKey.toPublicJWK(),
            retiredKey.toPublicJWK()
        )));

        List<JWK> jwks = source.get(matchAllSelector(), null);

        assertThat(jwks)
            .extracting(JWK::getKeyID)
            .containsExactly("active-kid", "retired-kid");
        assertThat(((RSAKey) jwks.get(0)).toPrivateKey())
            .as("JWKS path returns public-only keys — never leaks private material")
            .isNull();
    }

    @Test
    @DisplayName("Signing-style selector returns ONLY the ACTIVE key — NimbusJwtEncoder throws on multi-match, so RETIRED must not surface here")
    void signingSelectorReturnsOnlyActiveWithPrivateMaterial() throws Exception {
        setIssuer("https://auth.example.com/t/alpha");
        given(tenantRepository.findBySlug("alpha")).willReturn(Optional.of(alpha));
        given(signingKeys.getActiveSigningKey(1L)).willReturn(activeKey);

        List<JWK> jwks = source.get(signingSelector(), null);

        assertThat(jwks).extracting(JWK::getKeyID).containsExactly("active-kid");
        assertThat(((RSAKey) jwks.get(0)).toPrivateKey())
            .as("signing path returns the full key with private material")
            .isNotNull();
    }

    private static JWKSelector matchAllSelector() {
        return new JWKSelector(new JWKMatcher.Builder().build());
    }

    private static JWKSelector signingSelector() {
        // Mirrors NimbusJwtEncoder.createJwkSelector for RS256.
        return new JWKSelector(new JWKMatcher.Builder()
            .keyType(KeyType.RSA)
            .keyUses(KeyUse.SIGNATURE, null)
            .algorithms(JWSAlgorithm.RS256, null)
            .build());
    }

    private static void setIssuer(String issuer) {
        AuthorizationServerSettings settings = AuthorizationServerSettings.builder().issuer(issuer).build();
        AuthorizationServerContextHolder.setContext(new AuthorizationServerContext() {
            @Override public String getIssuer() { return issuer; }
            @Override public AuthorizationServerSettings getAuthorizationServerSettings() { return settings; }
        });
    }
}
