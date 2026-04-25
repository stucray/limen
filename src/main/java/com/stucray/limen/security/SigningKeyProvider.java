package com.stucray.limen.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.UUID;

@Configuration
public class SigningKeyProvider {

    private final Path keyPath;

    public SigningKeyProvider(@Value("${OVERROUND_SIGNING_KEY_PATH:./dev-signing-key.jwk}") String keyPath) {
        this.keyPath = Path.of(keyPath);
    }

    @Bean
    public JWKSource<SecurityContext> jwkSource() throws Exception {
        if (Files.exists(keyPath)) {
            return load();
        }
        return generateAndPersist();
    }

    private JWKSource<SecurityContext> load() throws Exception {
        String json = Files.readString(keyPath);
        JWKSet jwkSet;
        try {
            jwkSet = JWKSet.parse(json);
        } catch (ParseException e) {
            throw new IllegalStateException("Malformed signing key at " + keyPath + ": " + e.getMessage(), e);
        }
        return new ImmutableJWKSet<>(jwkSet);
    }

    private JWKSource<SecurityContext> generateAndPersist() throws Exception {
        RSAKey rsaKey = new RSAKeyGenerator(2048)
            .keyID(UUID.randomUUID().toString())
            .generate();
        Files.writeString(keyPath, new JWKSet(rsaKey).toString(false));
        return new ImmutableJWKSet<>(new JWKSet(rsaKey));
    }
}
