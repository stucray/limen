package com.stucray.limen.clients;

import com.stucray.limen.audit.events.ClientSecretRotatedEvent;
import org.jspecify.annotations.Nullable;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.security.oauth2.server.authorization.settings.TokenSettings;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.security.SecureRandom;
import java.time.Duration;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ClientManagementService {

    private final RegisteredClientRepository registeredClientRepository;
    private final TenantClientRepository tenantClientRepository;
    private final PasswordEncoder passwordEncoder;
    private final ApplicationEventPublisher eventPublisher;

    ClientManagementService(
        RegisteredClientRepository registeredClientRepository,
        TenantClientRepository tenantClientRepository,
        PasswordEncoder passwordEncoder,
        ApplicationEventPublisher eventPublisher
    ) {
        this.registeredClientRepository = registeredClientRepository;
        this.tenantClientRepository = tenantClientRepository;
        this.passwordEncoder = passwordEncoder;
        this.eventPublisher = eventPublisher;
    }

    List<TenantClient> listClients(Long applicationId, Long tenantId) {
        return tenantClientRepository.findAllByApplicationIdAndTenantId(applicationId, tenantId);
    }

    public static final long DEFAULT_ACCESS_TOKEN_TTL_MINUTES = 5;
    public static final long DEFAULT_REFRESH_TOKEN_TTL_DAYS = 30;
    public static final boolean DEFAULT_REUSE_REFRESH_TOKENS = false;

    public record ClientCreationResult(TenantClient client, String wireClientId, @Nullable String rawSecret) {}

    public record ClientWithSettings(
        TenantClient tenantClient,
        long accessTokenTtlMinutes,
        long refreshTokenTtlDays,
        boolean reuseRefreshTokens,
        boolean requirePkce,
        boolean requireConsent
    ) {}

    @SuppressWarnings("NullAway") // Spring Data convention: null id on insert; populated on save
    ClientCreationResult createClient(CreateClientCommand cmd) {
        if (cmd.grantTypes().contains(AuthorizationGrantType.AUTHORIZATION_CODE) && cmd.scopes().isEmpty()) {
            throw new IllegalArgumentException("Scopes are required for authorization_code grant");
        }

        String rawSecret = null;
        String hashedSecret = null;
        if (cmd.confidential()) {
            rawSecret = generateSecret();
            hashedSecret = passwordEncoder.encode(rawSecret);
        }

        RegisteredClient.Builder builder = RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId(UUID.randomUUID().toString())
            .clientName(cmd.displayName())
            .clientSettings(ClientSettings.builder()
                .requireProofKey(cmd.requirePkce() || !cmd.confidential())
                .requireAuthorizationConsent(cmd.requireConsent())
                .build())
            .tokenSettings(TokenSettings.builder()
                .accessTokenTimeToLive(Duration.ofMinutes(cmd.accessTokenTtlMinutes()))
                .refreshTokenTimeToLive(Duration.ofDays(cmd.refreshTokenTtlDays()))
                .reuseRefreshTokens(cmd.reuseRefreshTokens())
                .build());

        if (hashedSecret != null) {
            builder.clientSecret(hashedSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST);
        } else {
            builder.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
        }

        cmd.grantTypes().forEach(builder::authorizationGrantType);
        cmd.redirectUris().stream().filter(u -> !u.isBlank()).forEach(builder::redirectUri);
        cmd.postLogoutRedirectUris().stream().filter(u -> !u.isBlank()).forEach(builder::postLogoutRedirectUri);
        cmd.scopes().stream().filter(s -> !s.isBlank()).forEach(builder::scope);

        RegisteredClient registered = builder.build();
        registeredClientRepository.save(registered);

        TenantClient tenantClient = tenantClientRepository.save(new TenantClient(
            null, registered.getId(), cmd.applicationId(), cmd.tenantId(), cmd.displayName(), cmd.confidential()
        ));

        return new ClientCreationResult(tenantClient, registered.getClientId(), rawSecret);
    }

    public TenantClient getClient(String registeredClientId, Long tenantId) {
        return tenantClientRepository.findByRegisteredClientIdAndTenantId(registeredClientId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Client not found"));
    }

    ClientWithSettings getClientWithSettings(String registeredClientId, Long tenantId) {
        TenantClient tc = getClient(registeredClientId, tenantId);
        RegisteredClient rc = registeredClientRepository.findById(registeredClientId);
        if (rc == null) throw new IllegalArgumentException("Client not found");
        TokenSettings ts = rc.getTokenSettings();
        return new ClientWithSettings(
            tc,
            ts.getAccessTokenTimeToLive().toMinutes(),
            ts.getRefreshTokenTimeToLive().toDays(),
            ts.isReuseRefreshTokens(),
            rc.getClientSettings().isRequireProofKey(),
            rc.getClientSettings().isRequireAuthorizationConsent()
        );
    }

    void updateClientSettings(
        String registeredClientId, Long tenantId,
        long accessTokenTtlMinutes,
        long refreshTokenTtlDays,
        boolean reuseRefreshTokens,
        boolean requirePkce,
        boolean requireConsent
    ) {
        TenantClient tc = getClient(registeredClientId, tenantId);
        RegisteredClient existing = registeredClientRepository.findById(registeredClientId);
        if (existing == null) throw new IllegalArgumentException("Client not found");

        RegisteredClient updated = RegisteredClient.from(existing)
            .tokenSettings(TokenSettings.builder()
                .accessTokenTimeToLive(Duration.ofMinutes(accessTokenTtlMinutes))
                .refreshTokenTimeToLive(Duration.ofDays(refreshTokenTtlDays))
                .reuseRefreshTokens(reuseRefreshTokens)
                .build())
            .clientSettings(ClientSettings.builder()
                .requireProofKey(requirePkce || !tc.confidential())
                .requireAuthorizationConsent(requireConsent)
                .build())
            .build();
        registeredClientRepository.save(updated);
    }

    void deleteClient(String registeredClientId, Long tenantId) {
        TenantClient tenantClient = getClient(registeredClientId, tenantId);
        RegisteredClient rc = registeredClientRepository.findById(registeredClientId);
        if (rc != null) {
            // Spring's JdbcRegisteredClientRepository has no delete method; we use the low-level approach
            tenantClientRepository.delete(tenantClient);
            tenantClientRepository.deleteRegisteredClient(registeredClientId);
        }
    }

    public record SecretRotationResult(String wireClientId, String rawSecret) {}

    @Transactional
    SecretRotationResult rotateSecret(String registeredClientId, Long tenantId, long actorUserId) {
        getClient(registeredClientId, tenantId); // assert ownership
        RegisteredClient existing = registeredClientRepository.findById(registeredClientId);
        if (existing == null) throw new IllegalArgumentException("Client not found");

        String rawSecret = generateSecret();
        RegisteredClient updated = RegisteredClient.from(existing)
            .clientSecret(passwordEncoder.encode(rawSecret))
            .build();
        registeredClientRepository.save(updated);
        eventPublisher.publishEvent(
            new ClientSecretRotatedEvent(tenantId, registeredClientId, actorUserId));
        return new SecretRotationResult(existing.getClientId(), rawSecret);
    }

    private static String generateSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
