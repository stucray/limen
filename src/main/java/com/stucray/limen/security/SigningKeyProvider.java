package com.stucray.limen.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;

import java.nio.file.Files;
import java.nio.file.Path;
import java.text.ParseException;
import java.util.UUID;

// Dead code post slice 5a (#18): per-tenant signing keys are served by TenantJwkSource.
// Kept temporarily so slice 5b (#19) can excise this class, dev-signing-key.jwk, and
// LIMEN_SIGNING_KEY_PATH together. Do not re-wire as a @Configuration.
public class SigningKeyProvider {

    private final Path keyPath;

    public SigningKeyProvider(String keyPath) {
        this.keyPath = Path.of(keyPath);
    }

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
