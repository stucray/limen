package com.stucray.auth.security;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClient;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;

import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class BffClientBootstrapTest {

    @Mock RegisteredClientRepository registeredClientRepository;
    @Mock PasswordEncoder passwordEncoder;

    @Test
    void doesNothingWhenSecretUnset() throws Exception {
        new BffClientBootstrap(null, registeredClientRepository, passwordEncoder).run();
        verifyNoInteractions(registeredClientRepository, passwordEncoder);
    }

    @Test
    void registersClientWhenAbsent() throws Exception {
        given(registeredClientRepository.findByClientId("bff-client")).willReturn(null);
        given(passwordEncoder.encode("secret")).willReturn("encoded");

        new BffClientBootstrap("secret", registeredClientRepository, passwordEncoder).run();

        verify(registeredClientRepository).save(argThat(c ->
            c.getClientId().equals("bff-client") && c.getClientSecret().equals("encoded")
        ));
    }

    @Test
    void updatesSecretWhenClientAlreadyRegistered() throws Exception {
        RegisteredClient existing = RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId("bff-client")
            .clientSecret("oldsecret")
            .authorizationGrantType(org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost:8091/login/oauth2/code/bff-client")
            .build();
        given(registeredClientRepository.findByClientId("bff-client")).willReturn(existing);
        given(passwordEncoder.encode("newsecret")).willReturn("newencoded");

        new BffClientBootstrap("newsecret", registeredClientRepository, passwordEncoder).run();

        verify(registeredClientRepository).save(argThat(c ->
            c.getClientId().equals("bff-client") && c.getClientSecret().equals("newencoded")
        ));
    }

    @Test
    void isIdempotentAcrossRestarts() throws Exception {
        given(registeredClientRepository.findByClientId("bff-client")).willReturn(null);
        given(passwordEncoder.encode("secret")).willReturn("encoded");

        var bootstrap = new BffClientBootstrap("secret", registeredClientRepository, passwordEncoder);
        bootstrap.run();

        RegisteredClient created = RegisteredClient.withId(UUID.randomUUID().toString())
            .clientId("bff-client")
            .clientSecret("encoded")
            .authorizationGrantType(org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
            .redirectUri("http://localhost:8091/login/oauth2/code/bff-client")
            .build();
        given(registeredClientRepository.findByClientId("bff-client")).willReturn(created);

        bootstrap.run();

        verify(registeredClientRepository, times(2)).save(any());
    }
}
