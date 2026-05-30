package com.stucray.limen.ui.support;

import com.stucray.limen.applications.Application;
import com.stucray.limen.applications.ApplicationRepository;
import com.stucray.limen.clients.TenantClient;
import com.stucray.limen.clients.TenantClientRepository;
import com.stucray.limen.memberships.ApplicationMembershipService;
import com.stucray.limen.memberships.ClientMembershipService;
import com.stucray.limen.memberships.ClientMembershipTestFixture;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.provisioning.TenantProvisioningService;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.UUID;

/**
 * Single source of truth for "give me a fresh tenant" across UI tests.
 *
 * <p>Each call seeds a uniquely-named tenant with one admin (owner) and one end user,
 * so concurrent tests cannot collide on slug/email and no teardown is needed —
 * the Testcontainers Postgres is discarded at JVM exit.
 *
 * <p>Fixture state is built via repository writes, not service calls. Service contracts
 * are shaped around production scenarios (require an actor, publish events, hardcode
 * temporary-password / force-change semantics) — correct for production, wrong for
 * fixtures. Tests that need to assert on a production code path drive that path
 * through MockMvc directly in the test class; this factory only produces precondition
 * state.
 */
@Component
public class TestTenantFactory {

    private static final String SHARED_TEST_PASSWORD = "secret123";

    private final TenantProvisioningService tenantProvisioningService;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationRepository applicationRepository;
    private final RegisteredClientRepository registeredClientRepository;
    private final TenantClientRepository tenantClientRepository;
    private final ApplicationMembershipService applicationMembershipService;
    private final ClientMembershipService clientMembershipService;

    public TestTenantFactory(
        TenantProvisioningService tenantProvisioningService,
        TenantRepository tenantRepository,
        UserRepository userRepository,
        PasswordEncoder passwordEncoder,
        ApplicationRepository applicationRepository,
        RegisteredClientRepository registeredClientRepository,
        TenantClientRepository tenantClientRepository,
        ApplicationMembershipService applicationMembershipService,
        ClientMembershipService clientMembershipService
    ) {
        this.tenantProvisioningService = tenantProvisioningService;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.applicationRepository = applicationRepository;
        this.registeredClientRepository = registeredClientRepository;
        this.tenantClientRepository = tenantClientRepository;
        this.applicationMembershipService = applicationMembershipService;
        this.clientMembershipService = clientMembershipService;
    }

    @SuppressWarnings("NullAway") // Spring Data convention: null id on insert; populated on save
    @Transactional
    public SeededApplication seedApplication(SeededTenant tenant) {
        String suffix = uniqueSuffix();
        String name = "App " + suffix;
        Application app = applicationRepository.save(
            new Application(null, tenant.tenantId(), name, "Seeded for UI test", LocalDateTime.now())
        );
        return new SeededApplication(app.id(), name);
    }

    /**
     * Seeds a public PKCE OAuth2 client under the given application and grants the
     * tenant's seeded end-user the App Membership + Client Membership needed to
     * pass the {@code MembershipGateFilter}.
     *
     * <p>Drops down to {@link RegisteredClientRepository} + {@link TenantClientRepository}
     * directly rather than going through {@code ClientManagementService.createClient},
     * because that production helper hardcodes {@code requireAuthorizationConsent(true)}
     * and this slice's journey deliberately bypasses the consent step (Limen has no
     * custom consent template — the SAS default is intentionally out of scope).
     */
    @SuppressWarnings("NullAway") // Spring Data convention: null id on insert; populated on save
    @Transactional
    public SeededOAuth2Client seedOAuth2ClientForEndUser(
        SeededTenant tenant, SeededApplication app, String redirectUri
    ) {
        User endUser = userRepository.findByEmailAndTenantId(tenant.endUserEmail(), tenant.tenantId())
            .orElseThrow(() -> new IllegalStateException("seeded end user missing"));
        User admin = userRepository.findByEmailAndTenantId(tenant.adminEmail(), tenant.tenantId())
            .orElseThrow(() -> new IllegalStateException("seeded admin missing"));

        String registeredClientId = UUID.randomUUID().toString();
        String oauthClientId = UUID.randomUUID().toString();
        RegisteredClient rc = RegisteredClient.withId(registeredClientId)
            .clientId(oauthClientId)
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(redirectUri)
            .scope(OidcScopes.OPENID)
            .clientSettings(ClientSettings.builder()
                .requireProofKey(true)
                .requireAuthorizationConsent(false)
                .build())
            .build();
        registeredClientRepository.save(rc);
        tenantClientRepository.save(new TenantClient(
            null, registeredClientId, app.appId(), tenant.tenantId(), "UI Test Client", false));

        ClientMembershipTestFixture.grant(
            applicationMembershipService, clientMembershipService,
            app.appId(), tenant.tenantId(), endUser.id(), admin.id(),
            registeredClientId, Set.of()
        );

        return new SeededOAuth2Client(oauthClientId, redirectUri);
    }

    /**
     * Seeds a public PKCE OAuth2 client that also registers a
     * {@code post_logout_redirect_uri}, for exercising OIDC RP-initiated
     * logout. Same membership grant as {@link #seedOAuth2ClientForEndUser}.
     */
    @SuppressWarnings("NullAway") // Spring Data convention: null id on insert; populated on save
    @Transactional
    public SeededLogoutClient seedOAuth2ClientWithPostLogout(
        SeededTenant tenant, SeededApplication app, String redirectUri, String postLogoutRedirectUri
    ) {
        User endUser = userRepository.findByEmailAndTenantId(tenant.endUserEmail(), tenant.tenantId())
            .orElseThrow(() -> new IllegalStateException("seeded end user missing"));
        User admin = userRepository.findByEmailAndTenantId(tenant.adminEmail(), tenant.tenantId())
            .orElseThrow(() -> new IllegalStateException("seeded admin missing"));

        String registeredClientId = UUID.randomUUID().toString();
        String oauthClientId = UUID.randomUUID().toString();
        RegisteredClient rc = RegisteredClient.withId(registeredClientId)
            .clientId(oauthClientId)
            .clientAuthenticationMethod(ClientAuthenticationMethod.NONE)
            .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri(redirectUri)
            .postLogoutRedirectUri(postLogoutRedirectUri)
            .scope(OidcScopes.OPENID)
            .clientSettings(ClientSettings.builder()
                .requireProofKey(true)
                .requireAuthorizationConsent(false)
                .build())
            .build();
        registeredClientRepository.save(rc);
        tenantClientRepository.save(new TenantClient(
            null, registeredClientId, app.appId(), tenant.tenantId(), "UI Logout Test Client", false));

        ClientMembershipTestFixture.grant(
            applicationMembershipService, clientMembershipService,
            app.appId(), tenant.tenantId(), endUser.id(), admin.id(),
            registeredClientId, Set.of()
        );

        return new SeededLogoutClient(oauthClientId, redirectUri, postLogoutRedirectUri);
    }

    /**
     * Grants the seeded end-user the App Membership + Client Membership needed
     * to pass the {@code MembershipGateFilter} for a client that was created
     * outside this factory (typically through the manage UI as part of an
     * end-to-end test). Looks up the internal {@code RegisteredClient.id} from
     * the OAuth2 wire {@code client_id} so callers can pass whichever id they
     * captured from the UI.
     */
    @Transactional
    public void grantEndUserAccessToClient(
        SeededTenant tenant, SeededApplication app, String wireClientId
    ) {
        RegisteredClient rc = registeredClientRepository.findByClientId(wireClientId);
        if (rc == null) {
            throw new IllegalStateException("Client not found by wire id: " + wireClientId);
        }
        User endUser = userRepository.findByEmailAndTenantId(tenant.endUserEmail(), tenant.tenantId())
            .orElseThrow(() -> new IllegalStateException("seeded end user missing"));
        User admin = userRepository.findByEmailAndTenantId(tenant.adminEmail(), tenant.tenantId())
            .orElseThrow(() -> new IllegalStateException("seeded admin missing"));
        ClientMembershipTestFixture.grant(
            applicationMembershipService, clientMembershipService,
            app.appId(), tenant.tenantId(), endUser.id(), admin.id(),
            rc.getId(), Set.of()
        );
    }

    @Transactional
    public SeededForcedChangeUser seedEndUserForcedPasswordChange(SeededTenant tenant) {
        String suffix = uniqueSuffix();
        String email = "forcechange-" + suffix + "@example.test";
        String hash = passwordEncoder.encode(SHARED_TEST_PASSWORD);
        userRepository.save(new User(
            null, tenant.tenantId(), email, hash, true, true, false, true, LocalDateTime.now()));
        return new SeededForcedChangeUser(email, SHARED_TEST_PASSWORD);
    }

    @Transactional
    public SeededTenant createTenant() {
        String suffix = uniqueSuffix();
        String slug = "t-" + suffix;
        String displayName = "Test Org " + suffix;
        String adminEmail = "admin-" + suffix + "@example.test";
        String endUserEmail = "user-" + suffix + "@example.test";

        Tenant tenant = tenantProvisioningService.createTenant(slug, displayName);
        String hash = passwordEncoder.encode(SHARED_TEST_PASSWORD);
        userRepository.save(new User(
            null, tenant.id(), adminEmail, hash, true, false, true, true, LocalDateTime.now()));
        userRepository.save(new User(
            null, tenant.id(), endUserEmail, hash, true, false, false, true, LocalDateTime.now()));

        return new SeededTenant(
            tenant.id(), slug, displayName,
            adminEmail, SHARED_TEST_PASSWORD,
            endUserEmail, SHARED_TEST_PASSWORD);
    }

    @Transactional
    public SeededSystemAdmin createSystemAdmin() {
        String suffix = uniqueSuffix();
        String email = "sysadmin-" + suffix + "@example.test";
        Tenant systemTenant = tenantRepository.findBySlug("system")
            .orElseThrow(() -> new IllegalStateException("system tenant not bootstrapped"));
        String hash = passwordEncoder.encode(SHARED_TEST_PASSWORD);
        userRepository.save(new User(
            null, systemTenant.id(), email, hash, true, false, false, true, LocalDateTime.now()));
        return new SeededSystemAdmin(email, SHARED_TEST_PASSWORD);
    }

    private static String uniqueSuffix() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 8);
    }

    public record SeededTenant(
        Long tenantId,
        String slug,
        String displayName,
        String adminEmail,
        String adminPassword,
        String endUserEmail,
        String endUserPassword
    ) {}

    public record SeededSystemAdmin(String email, String password) {}

    public record SeededApplication(Long appId, String name) {}

    public record SeededForcedChangeUser(String email, String temporaryPassword) {}

    public record SeededOAuth2Client(String clientId, String redirectUri) {}

    public record SeededLogoutClient(String clientId, String redirectUri, String postLogoutRedirectUri) {}
}
