package com.stucray.auth.security;

import com.nimbusds.jose.jwk.JWKMatcher;
import com.nimbusds.jose.jwk.JWKSelector;
import com.nimbusds.jose.jwk.source.JWKSource;
import com.nimbusds.jose.proc.SecurityContext;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SigningKeyProviderTest {

    @TempDir Path tempDir;

    @Test
    void generatesAndPersistsKeyWhenFileAbsent() throws Exception {
        Path keyPath = tempDir.resolve("signing.jwk");
        SigningKeyProvider provider = new SigningKeyProvider(keyPath.toString());

        JWKSource<SecurityContext> source = provider.jwkSource();

        assertThat(source).isNotNull();
        assertThat(Files.exists(keyPath)).isTrue();
        assertThat(allKeys(source)).hasSize(1);
    }

    @Test
    void loadsExistingKeyWithSameKid() throws Exception {
        Path keyPath = tempDir.resolve("signing.jwk");

        JWKSource<SecurityContext> source1 = new SigningKeyProvider(keyPath.toString()).jwkSource();
        JWKSource<SecurityContext> source2 = new SigningKeyProvider(keyPath.toString()).jwkSource();

        String kid1 = allKeys(source1).get(0).getKeyID();
        String kid2 = allKeys(source2).get(0).getKeyID();
        assertThat(kid1).isEqualTo(kid2);
    }

    @Test
    void failsLoudlyOnMalformedContent() throws Exception {
        Path keyPath = tempDir.resolve("malformed.jwk");
        Files.writeString(keyPath, "not-valid-json {{{");
        SigningKeyProvider provider = new SigningKeyProvider(keyPath.toString());

        assertThatThrownBy(provider::jwkSource)
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("Malformed signing key");
    }

    private static List<com.nimbusds.jose.jwk.JWK> allKeys(JWKSource<SecurityContext> source) throws Exception {
        return source.get(new JWKSelector(new JWKMatcher.Builder().build()), null);
    }
}
