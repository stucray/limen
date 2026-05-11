package com.stucray.limen.oauth2.sas;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.applications.Application;
import com.stucray.limen.applications.ApplicationRepository;
import com.stucray.limen.clients.TenantClient;
import com.stucray.limen.clients.TenantClientRepository;
import com.stucray.limen.security.SigningKeyProvisioning;
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
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Cross-cutting boundary test for the four tenant-scoped SAS SPIs wired by
 * {@link SasConfig}. Asserts the one invariant the {@code oauth2.sas} sub-package
 * exists to enforce: a complete OAuth2 storage state for tenant A does not leak
 * into tenant B through any SPI.
 *
 * <p>Asserts on observable storage outcomes ({@code findById} returns null
 * across scope, JWKS sets are disjoint, hard-fail SPIs throw without scope) so
 * the test survives any future structural refactor inside {@code oauth2.sas}.
 */
@Import(TestcontainersConfiguration.class)
@SpringBootTest
@DisplayName("Tenant-scoped SAS isolates every SPI across tenants")
class TenantScopedSasIntegrationTest {

    @Autowired RegisteredClientRepository registeredClients;
    @Autowired OAuth2AuthorizationService authorizations;
    @Autowired OAuth2AuthorizationConsentService consents;
    @Autowired JWKSource<SecurityContext> jwks;

    @Autowired SigningKeyProvisioning signingKeys;
    @Autowired TenantRepository tenantRepository;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired TenantClientRepository tenantClientRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    Tenant alpha;
    Tenant beta;
    RegisteredClient alphaClient;
    RegisteredClient betaClient;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM oauth2_authorization");
        jdbcTemplate.execute("DELETE FROM oauth2_authorization_consent");
        jdbcTemplate.execute("DELETE FROM client_membership");
        jdbcTemplate.execute("DELETE FROM application_membership_role");
        jdbcTemplate.execute("DELETE FROM application_membership");
        jdbcTemplate.execute("DELETE FROM role");
        jdbcTemplate.execute("DELETE FROM client_metadata");
        jdbcTemplate.execute("DELETE FROM oauth2_registered_client");
        jdbcTemplate.execute("DELETE FROM applications");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug IN ('boundary-alpha', 'boundary-beta')");

        alpha = tenantRepository.save(new Tenant(
            null, "boundary-alpha", "Boundary Alpha", TenantStatus.ACTIVE, LocalDateTime.now()));
        beta = tenantRepository.save(new Tenant(
            null, "boundary-beta", "Boundary Beta", TenantStatus.ACTIVE, LocalDateTime.now()));

        signingKeys.createForTenant(alpha.id());
        signingKeys.createForTenant(beta.id());

        alphaClient = seedRegisteredClient(alpha);
        betaClient = seedRegisteredClient(beta);
    }

    @Test
    @DisplayName("RegisteredClient saved under A is invisible under B (findById + findByClientId)")
    void registeredClientsIsolated() {
        TenantScope.run(alpha.slug(), alpha.id(), () -> {
            assertThat(registeredClients.findById(alphaClient.getId())).isNotNull();
            assertThat(registeredClients.findByClientId(alphaClient.getClientId())).isNotNull();
            assertThat(registeredClients.findById(betaClient.getId())).isNull();
            assertThat(registeredClients.findByClientId(betaClient.getClientId())).isNull();
        });
        TenantScope.run(beta.slug(), beta.id(), () -> {
            assertThat(registeredClients.findById(betaClient.getId())).isNotNull();
            assertThat(registeredClients.findById(alphaClient.getId())).isNull();
            assertThat(registeredClients.findByClientId(alphaClient.getClientId())).isNull();
        });
    }

    @Test
    @DisplayName("OAuth2Authorization saved under A is invisible under B via findById and findByToken")
    void authorizationsIsolated() {
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER, "access-" + UUID.randomUUID(),
            Instant.now(), Instant.now().plusSeconds(3600));
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(alphaClient)
            .id(UUID.randomUUID().toString())
            .principalName("alice@example.test")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .accessToken(accessToken)
            .build();

        TenantScope.run(alpha.slug(), alpha.id(), () -> authorizations.save(authorization));

        TenantScope.run(alpha.slug(), alpha.id(), () ->
            assertThat(authorizations.findById(authorization.getId())).isNotNull());
        TenantScope.run(beta.slug(), beta.id(), () -> {
            assertThat(authorizations.findById(authorization.getId()))
                .as("alpha's authorization invisible under beta").isNull();
            assertThat(authorizations.findByToken(
                accessToken.getTokenValue(), OAuth2TokenType.ACCESS_TOKEN))
                .as("alpha's access token invisible under beta").isNull();
        });
    }

    @Test
    @DisplayName("OAuth2AuthorizationConsent saved under A is invisible under B")
    void consentsIsolated() {
        OAuth2AuthorizationConsent consent = OAuth2AuthorizationConsent
            .withId(alphaClient.getId(), "alice@example.test")
            .authority(new SimpleGrantedAuthority("SCOPE_openid"))
            .build();

        TenantScope.run(alpha.slug(), alpha.id(), () -> consents.save(consent));

        TenantScope.run(alpha.slug(), alpha.id(), () ->
            assertThat(consents.findById(alphaClient.getId(), "alice@example.test")).isNotNull());
        TenantScope.run(beta.slug(), beta.id(), () ->
            assertThat(consents.findById(alphaClient.getId(), "alice@example.test"))
                .as("alpha's consent invisible under beta").isNull());
    }

    @Test
    @DisplayName("JWKSource returns each tenant's keys under TenantScope; the two key sets are disjoint")
    void jwksIsolated() throws Exception {
        JWKSelector matchAll = new JWKSelector(new JWKMatcher.Builder().build());

        List<JWK> alphaKeys = TenantScope.call(alpha.slug(), alpha.id(),
            () -> jwks.get(matchAll, null));
        List<JWK> betaKeys = TenantScope.call(beta.slug(), beta.id(),
            () -> jwks.get(matchAll, null));

        assertThat(alphaKeys).isNotEmpty();
        assertThat(betaKeys).isNotEmpty();
        assertThat(alphaKeys).extracting(JWK::getKeyID)
            .doesNotContainAnyElementsOf(betaKeys.stream().map(JWK::getKeyID).toList());
    }

    @Test
    @DisplayName("Authorization + Consent SPIs reject writes when TenantScope is not bound")
    void writesRejectMissingScope() {
        OAuth2Authorization authorization = OAuth2Authorization.withRegisteredClient(alphaClient)
            .id(UUID.randomUUID().toString())
            .principalName("alice@example.test")
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .build();
        OAuth2AuthorizationConsent consent = OAuth2AuthorizationConsent
            .withId(alphaClient.getId(), "alice@example.test")
            .authority(new SimpleGrantedAuthority("SCOPE_openid"))
            .build();

        assertThatThrownBy(() -> authorizations.save(authorization))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TenantScope");
        assertThatThrownBy(() -> consents.save(consent))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TenantScope");
    }

    private RegisteredClient seedRegisteredClient(Tenant tenant) {
        Application app = applicationRepository.save(new Application(
            null, tenant.id(), tenant.slug() + " App", "Boundary test app", LocalDateTime.now()));
        String internalId = UUID.randomUUID().toString();
        RegisteredClient client = RegisteredClient.withId(internalId)
            .clientId(UUID.randomUUID().toString())
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost/callback")
            .scope("openid")
            .build();
        TenantScope.run(tenant.slug(), tenant.id(), () -> registeredClients.save(client));
        tenantClientRepository.save(new TenantClient(
            null, internalId, app.id(), tenant.id(), tenant.slug() + " Client", false));
        return client;
    }

}
