package com.stucray.limen.security;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.ClientAuthenticationMethod;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.security.oauth2.server.authorization.settings.ClientSettings;
import org.springframework.stereotype.Component;

import java.util.UUID;

@Component
public class BffClientBootstrap implements CommandLineRunner {

    private static final String CLIENT_ID = "bff-client";
    private static final String REDIRECT_URI = "http://localhost:8091/login/oauth2/code/bff-client";

    private final String clientSecret;
    private final RegisteredClientRepository registeredClientRepository;
    private final PasswordEncoder passwordEncoder;

    public BffClientBootstrap(
        @Value("${OVERROUND_BFF_CLIENT_SECRET:#{null}}") String clientSecret,
        RegisteredClientRepository registeredClientRepository,
        PasswordEncoder passwordEncoder
    ) {
        this.clientSecret = clientSecret;
        this.registeredClientRepository = registeredClientRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (clientSecret == null) return;

        String encodedSecret = passwordEncoder.encode(clientSecret);
        RegisteredClient existing = registeredClientRepository.findByClientId(CLIENT_ID);

        if (existing != null) {
            registeredClientRepository.save(RegisteredClient.from(existing)
                .clientSecret(encodedSecret)
                .build());
        } else {
            registeredClientRepository.save(RegisteredClient.withId(UUID.randomUUID().toString())
                .clientId(CLIENT_ID)
                .clientSecret(encodedSecret)
                .clientAuthenticationMethod(ClientAuthenticationMethod.CLIENT_SECRET_BASIC)
                .authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
                .authorizationGrantType(AuthorizationGrantType.REFRESH_TOKEN)
                .redirectUri(REDIRECT_URI)
                .scope(OidcScopes.OPENID)
                .scope(OidcScopes.PROFILE)
                .clientSettings(ClientSettings.builder()
                    .requireProofKey(true)
                    .requireAuthorizationConsent(false)
                    .build())
                .build());
        }
    }
}
