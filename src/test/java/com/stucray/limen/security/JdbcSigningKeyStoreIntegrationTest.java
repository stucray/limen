package com.stucray.limen.security;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest(properties = {"LIMEN_SIGNING_KEY_PATH=./target/test-signing-key.jwk"})
class JdbcSigningKeyStoreIntegrationTest {

    @Autowired SigningKeyStore signingKeyStore;
    @Autowired TenantRepository tenantRepository;
    @Autowired JdbcTemplate jdbcTemplate;

    Tenant tenant;

    @BeforeEach
    void setUp() {
        tenant = tenantRepository.save(new Tenant(
            null, "ks-" + System.nanoTime(), "Key Store Test", TenantStatus.ACTIVE, LocalDateTime.now()
        ));
    }

    @Test
    void createThenGetReturnsKeyWithMatchingPublicJwk() throws Exception {
        signingKeyStore.createForTenant(tenant.id());

        RSAKey key = signingKeyStore.getActiveSigningKey(tenant.id());
        assertThat(key).isNotNull();
        assertThat(key.toPrivateKey()).isNotNull();

        String storedPublicJwk = jdbcTemplate.queryForObject(
            "SELECT public_key_jwk FROM tenant_signing_key WHERE tenant_id = ? AND status = 'ACTIVE'",
            String.class,
            tenant.id()
        );
        assertThat(storedPublicJwk).isEqualTo(key.toPublicJWK().toJSONString());
    }

    @Test
    void getJwkSetReturnsPublicKeysForTenant() throws Exception {
        signingKeyStore.createForTenant(tenant.id());

        JWKSet jwkSet = signingKeyStore.getJwkSet(tenant.id());
        assertThat(jwkSet.getKeys()).hasSize(1);
        assertThat(jwkSet.getKeys().getFirst()).isInstanceOf(RSAKey.class);
        assertThat(((RSAKey) jwkSet.getKeys().getFirst()).toPrivateKey()).isNull();
    }

    @Test
    void privateKeyCiphertextDoesNotContainPlaintextKeyMaterial() {
        signingKeyStore.createForTenant(tenant.id());

        byte[] ciphertext = jdbcTemplate.queryForObject(
            "SELECT private_key_ciphertext FROM tenant_signing_key WHERE tenant_id = ? AND status = 'ACTIVE'",
            byte[].class,
            tenant.id()
        );
        assertThat(ciphertext).isNotNull();
        String asText = new String(ciphertext, StandardCharsets.UTF_8);
        assertThat(asText).doesNotContain("RSA PRIVATE KEY");
        assertThat(asText).doesNotContain("\"kty\":\"RSA\"");
        assertThat(asText).doesNotContain("\"d\":");

        for (int i = 0; i < ciphertext.length - 3; i++) {
            boolean pkcs8Magic = ciphertext[i] == 0x30
                && ciphertext[i + 1] == (byte) 0x82
                && ciphertext[i + 2] == 0x04;
            assertThat(pkcs8Magic).as("no PKCS#8 SEQUENCE header at offset " + i).isFalse();
        }
    }

    @Test
    void deleteForTenantRemovesAllRows() {
        signingKeyStore.createForTenant(tenant.id());
        assertThat(rowCount(tenant.id())).isEqualTo(1);

        signingKeyStore.deleteForTenant(tenant.id());

        assertThat(rowCount(tenant.id())).isZero();
    }

    @Test
    void getActiveSigningKeyReturnsNullWhenNoneExists() {
        assertThat(signingKeyStore.getActiveSigningKey(tenant.id())).isNull();
    }

    @Test
    void createForTenantTwiceViolatesActiveUniqueConstraint() {
        signingKeyStore.createForTenant(tenant.id());

        assertThatPartialUniqueIndexBlocksSecondActive();
    }

    private void assertThatPartialUniqueIndexBlocksSecondActive() {
        try {
            signingKeyStore.createForTenant(tenant.id());
            org.assertj.core.api.Assertions.fail("expected unique-constraint violation");
        } catch (org.springframework.dao.DataIntegrityViolationException expected) {
            // partial unique index enforces one ACTIVE key per tenant
        }
    }

    private int rowCount(long tenantId) {
        Integer count = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM tenant_signing_key WHERE tenant_id = ?",
            Integer.class,
            tenantId
        );
        return count == null ? 0 : count;
    }
}
