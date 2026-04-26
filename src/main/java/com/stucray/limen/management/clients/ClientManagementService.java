package com.stucray.limen.management.clients;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.util.Base64;
import java.util.List;
import java.util.Set;
import java.util.UUID;

@Service
public class ClientManagementService {

    private final RegisteredClientRepository registeredClientRepository;
    private final TenantClientRepository tenantClientRepository;
    private final PasswordEncoder passwordEncoder;

    public ClientManagementService(
        RegisteredClientRepository registeredClientRepository,
        TenantClientRepository tenantClientRepository,
        PasswordEncoder passwordEncoder
    ) {
        this.registeredClientRepository = registeredClientRepository;
        this.tenantClientRepository = tenantClientRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public List<TenantClient> listClients(Long applicationId, Long tenantId) {
        return tenantClientRepository.findAllByApplicationIdAndTenantId(applicationId, tenantId);
    }

    public record ClientCreationResult(TenantClient client, String rawSecret) {}

    public ClientCreationResult createClient(
        Long applicationId, Long tenantId,
        String clientName,
        Set<AuthorizationGrantType> grantTypes,
        Set<String> redirectUris,
        Set<String> postLogoutRedirectUris,
        Set<String> scopes,
        boolean requirePkce,
        boolean confidential
    ) {
        String rawSecret = null;
        String hashedSecret = null;
        if (confidential) {
            rawSecret = generateSecret();
            hashedSecret = passwordEncoder.encode(rawSecret);
        }

        RegisteredClient.Builder builder = RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId(UUID.randomUUID().toString())
            .clientName(clientName)
            .clientSettings(ClientSettings.builder()
                .requireProofKey(requirePkce || !confidential)
                .requireAuthorizationConsent(true)
                .build());

        if (hashedSecret != null) {
            builder.clientSecret(hashedSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_POST);
        } else {
            builder.clientAuthenticationMethod(ClientAuthenticationMethod.NONE);
        }

        grantTypes.forEach(builder::authorizationGrantType);
        redirectUris.stream().filter(u -> !u.isBlank()).forEach(builder::redirectUri);
        postLogoutRedirectUris.stream().filter(u -> !u.isBlank()).forEach(builder::postLogoutRedirectUri);
        scopes.stream().filter(s -> !s.isBlank()).forEach(builder::scope);

        RegisteredClient registered = builder.build();
        registeredClientRepository.save(registered);

        TenantClient tenantClient = tenantClientRepository.save(new TenantClient(
            null, registered.getId(), applicationId, tenantId, clientName, confidential
        ));

        return new ClientCreationResult(tenantClient, rawSecret);
    }

    public TenantClient getClient(String registeredClientId, Long tenantId) {
        return tenantClientRepository.findByRegisteredClientIdAndTenantId(registeredClientId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Client not found"));
    }

    public void deleteClient(String registeredClientId, Long tenantId) {
        TenantClient tenantClient = getClient(registeredClientId, tenantId);
        RegisteredClient rc = registeredClientRepository.findById(registeredClientId);
        if (rc != null) {
            // Spring's JdbcRegisteredClientRepository has no delete method; we use the low-level approach
            tenantClientRepository.delete(tenantClient);
            tenantClientRepository.deleteRegisteredClient(registeredClientId);
        }
    }

    public record SecretRotationResult(String rawSecret) {}

    public SecretRotationResult rotateSecret(String registeredClientId, Long tenantId) {
        getClient(registeredClientId, tenantId); // assert ownership
        RegisteredClient existing = registeredClientRepository.findById(registeredClientId);
        if (existing == null) throw new IllegalArgumentException("Client not found");

        String rawSecret = generateSecret();
        RegisteredClient updated = RegisteredClient.from(existing)
            .clientSecret(passwordEncoder.encode(rawSecret))
            .build();
        registeredClientRepository.save(updated);
        return new SecretRotationResult(rawSecret);
    }

    private static String generateSecret() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
