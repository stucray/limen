package com.stucray.limen.oauth2.sas;

import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.applications.Application;
import com.stucray.limen.applications.ApplicationRepository;
import com.stucray.limen.clients.TenantClient;
import com.stucray.limen.clients.TenantClientRepository;
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
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@DisplayName("TenantAwareOAuth2AuthorizationService: TenantScope-required save/findById/findByToken with cross-tenant isolation")
class TenantAwareOAuth2AuthorizationServiceIntegrationTest {

    @Autowired OAuth2AuthorizationService authorizationService;
    @Autowired RegisteredClientRepository registeredClientRepository;
    @Autowired TenantRepository tenantRepository;
    @Autowired ApplicationRepository applicationRepository;
    @Autowired TenantClientRepository tenantClientRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    Tenant alpha;
    Tenant beta;
    RegisteredClient client;
    Application alphaApp;

    @BeforeEach
    void setUp() {
        jdbcTemplate.execute("DELETE FROM client_membership");
        jdbcTemplate.execute("DELETE FROM application_membership_role");
        jdbcTemplate.execute("DELETE FROM application_membership");
        jdbcTemplate.execute("DELETE FROM role");
        jdbcTemplate.execute("DELETE FROM oauth2_authorization");
        jdbcTemplate.execute("DELETE FROM client_metadata");
        jdbcTemplate.execute("DELETE FROM oauth2_registered_client");
        jdbcTemplate.execute("DELETE FROM applications");
        jdbcTemplate.execute("DELETE FROM tenants WHERE slug IN ('iso-alpha', 'iso-beta')");

        alpha = tenantRepository.save(new Tenant(
            null, "iso-alpha", "Iso Alpha", TenantStatus.ACTIVE, LocalDateTime.now()
        ));
        beta = tenantRepository.save(new Tenant(
            null, "iso-beta", "Iso Beta", TenantStatus.ACTIVE, LocalDateTime.now()
        ));
        alphaApp = applicationRepository.save(new Application(
            null, alpha.id(), "Alpha App", "Test app", LocalDateTime.now()
        ));

        String internalId = UUID.randomUUID().toString();
        client = RegisteredClient.withId(internalId)
            .clientId(UUID.randomUUID().toString())
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost/callback")
            .scope("openid")
            .build();
        registeredClientRepository.save(client);
        // Map client to alpha so TenantAwareRegisteredClientRepository#findById succeeds
        // for the row mapper when reading authorizations under alpha context.
        tenantClientRepository.save(new TenantClient(
            null, internalId, alphaApp.id(), alpha.id(), "Iso Test Client", false
        ));
    }

    @Test
    @DisplayName("save() without an active TenantScope throws IllegalStateException")
    void saveWithoutTenantScopeThrows() {
        OAuth2Authorization auth = buildAuthorizationWithAccessToken("alice", "tok-1");

        assertThatThrownBy(() -> authorizationService.save(auth))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TenantScope");
    }

    @Test
    @DisplayName("findById() without an active TenantScope throws IllegalStateException")
    void findByIdWithoutTenantScopeThrows() {
        assertThatThrownBy(() -> authorizationService.findById("any-id"))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TenantScope");
    }

    @Test
    @DisplayName("findByToken() without an active TenantScope throws IllegalStateException")
    void findByTokenWithoutTenantScopeThrows() {
        assertThatThrownBy(() -> authorizationService.findByToken("any-token", OAuth2TokenType.ACCESS_TOKEN))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("TenantScope");
    }

    @Test
    @DisplayName("An authorization saved under tenant alpha is invisible to findById() running under tenant beta's scope")
    void crossTenantFindByIdReturnsNull() throws Exception {
        OAuth2Authorization saved = TenantScope.call(alpha.slug(), alpha.id(), () -> {
            OAuth2Authorization a = buildAuthorizationWithAccessToken("alice", "tok-alpha-1");
            authorizationService.save(a);
            assertThat(authorizationService.findById(a.getId())).isNotNull();
            return a;
        });

        TenantScope.run(beta.slug(), beta.id(), () -> {
            assertThat(authorizationService.findById(saved.getId())).isNull();
        });
    }

    @Test
    @DisplayName("A token issued under tenant alpha is resolvable by findByToken() under alpha but returns null under tenant beta's scope")
    void crossTenantFindByTokenReturnsNull() throws Exception {
        OAuth2Authorization saved = TenantScope.call(alpha.slug(), alpha.id(), () -> {
            OAuth2Authorization a = buildAuthorizationWithAccessToken("alice", "tok-alpha-2");
            authorizationService.save(a);
            OAuth2Authorization underAlpha =
                authorizationService.findByToken("tok-alpha-2", OAuth2TokenType.ACCESS_TOKEN);
            assertThat(underAlpha).isNotNull();
            assertThat(underAlpha.getId()).isEqualTo(a.getId());
            return a;
        });

        TenantScope.run(beta.slug(), beta.id(), () -> {
            assertThat(authorizationService.findByToken("tok-alpha-2", OAuth2TokenType.ACCESS_TOKEN)).isNull();
        });
        // saved is intentionally referenced to silence the "may be unused" warning;
        // the real assertion is the null result above.
        assertThat(saved).isNotNull();
    }

    @Test
    @DisplayName("save() under TenantScope persists the calling tenant's id into the oauth2_authorization.tenant_id column")
    void savePersistsTenantIdColumn() throws Exception {
        OAuth2Authorization saved = TenantScope.call(alpha.slug(), alpha.id(), () -> {
            OAuth2Authorization a = buildAuthorizationWithAccessToken("alice", "tok-alpha-3");
            authorizationService.save(a);
            return a;
        });

        Long persistedTenantId = jdbcTemplate.queryForObject(
            "SELECT tenant_id FROM oauth2_authorization WHERE id = ?",
            Long.class, saved.getId()
        );
        assertThat(persistedTenantId).isEqualTo(alpha.id());
    }

    private OAuth2Authorization buildAuthorizationWithAccessToken(String principalName, String tokenValue) {
        OAuth2AccessToken accessToken = new OAuth2AccessToken(
            OAuth2AccessToken.TokenType.BEARER,
            tokenValue,
            Instant.now(),
            Instant.now().plusSeconds(300)
        );
        return OAuth2Authorization.withRegisteredClient(client)
            .id(UUID.randomUUID().toString())
            .principalName(principalName)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .accessToken(accessToken)
            .build();
    }
}
