package com.stucray.limen.security.signing;

import com.stucray.limen.security.SigningKeyStore;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@DisplayName("JdbcSigningKeyStore (per-tenant RSA key storage)")
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
    @DisplayName("Created key round-trips: getActiveSigningKey returns the key whose public JWK matches the stored row")
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
    @DisplayName("getJwkSet returns only public-key material — never the private exponent")
    void getJwkSetReturnsPublicKeysForTenant() throws Exception {
        signingKeyStore.createForTenant(tenant.id());

        JWKSet jwkSet = signingKeyStore.getJwkSet(tenant.id());
        assertThat(jwkSet.getKeys()).hasSize(1);
        assertThat(jwkSet.getKeys().getFirst()).isInstanceOf(RSAKey.class);
        assertThat(((RSAKey) jwkSet.getKeys().getFirst()).toPrivateKey()).isNull();
    }

    @Test
    @DisplayName("Stored private_key_ciphertext bytes contain no PEM, no JWK fields, and no PKCS#8 sequence header")
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
    @DisplayName("deleteForTenant removes all rows for the tenant (active and rotated)")
    void deleteForTenantRemovesAllRows() {
        signingKeyStore.createForTenant(tenant.id());
        assertThat(rowCount(tenant.id())).isEqualTo(1);

        signingKeyStore.deleteForTenant(tenant.id());

        assertThat(rowCount(tenant.id())).isZero();
    }

    @Test
    @DisplayName("getActiveSigningKey returns null when no active row exists for the tenant")
    void getActiveSigningKeyReturnsNullWhenNoneExists() {
        assertThat(signingKeyStore.getActiveSigningKey(tenant.id())).isNull();
    }

    @Test
    @DisplayName("Calling createForTenant twice violates the partial-unique index on (tenant_id, status='ACTIVE')")
    void createForTenantTwiceViolatesActiveUniqueConstraint() {
        signingKeyStore.createForTenant(tenant.id());

        assertThatPartialUniqueIndexBlocksSecondActive();
    }

    @Test
    @DisplayName("rotateForTenant retires the old ACTIVE row and inserts a new ACTIVE one in a single transaction; partial unique index does not fire mid-rotation")
    void rotateForTenantSwapsActiveAndRetiresPrevious() {
        signingKeyStore.createForTenant(tenant.id());
        String originalKid = jdbcTemplate.queryForObject(
            "SELECT kid FROM tenant_signing_key WHERE tenant_id = ? AND status = 'ACTIVE'",
            String.class, tenant.id());

        SigningKeyStore.RotationOutcome outcome = signingKeyStore.rotateForTenant(tenant.id());

        assertThat(outcome.oldKid()).isEqualTo(originalKid);
        assertThat(outcome.newKid()).isNotEqualTo(originalKid);
        assertThat(rowCount(tenant.id())).isEqualTo(2);

        String activeKid = jdbcTemplate.queryForObject(
            "SELECT kid FROM tenant_signing_key WHERE tenant_id = ? AND status = 'ACTIVE'",
            String.class, tenant.id());
        assertThat(activeKid).isEqualTo(outcome.newKid());

        Object retiredAt = jdbcTemplate.queryForObject(
            "SELECT retired_at FROM tenant_signing_key WHERE tenant_id = ? AND status = 'RETIRED'",
            Object.class, tenant.id());
        assertThat(retiredAt).as("retired_at populated when key transitions to RETIRED").isNotNull();
    }

    @Test
    @DisplayName("rotateForTenant throws IllegalStateException when the tenant has no ACTIVE key")
    void rotateForTenantThrowsWhenNoActiveKey() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
            signingKeyStore.rotateForTenant(tenant.id()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("no ACTIVE signing key");
    }

    @Test
    @DisplayName("getJwkSet orders ACTIVE before RETIRED so SAS's first-match selector keeps signing on the live key during a grace window")
    void getJwkSetOrdersActiveBeforeRetired() {
        signingKeyStore.createForTenant(tenant.id());
        SigningKeyStore.RotationOutcome outcome = signingKeyStore.rotateForTenant(tenant.id());

        JWKSet jwkSet = signingKeyStore.getJwkSet(tenant.id());

        assertThat(jwkSet.getKeys()).hasSize(2);
        assertThat(jwkSet.getKeys()).extracting(JWK::getKeyID)
            .containsExactly(outcome.newKid(), outcome.oldKid());
    }

    @Test
    @DisplayName("pruneRetiredOlderThan deletes RETIRED rows whose retired_at exceeds the grace, returning each (tenantId, kid) pair")
    void pruneDeletesGraceExpiredRetiredRowsAndReturnsTheirIdentities() {
        signingKeyStore.createForTenant(tenant.id());
        SigningKeyStore.RotationOutcome outcome = signingKeyStore.rotateForTenant(tenant.id());
        backdateRetiredAt(tenant.id(), outcome.oldKid(), "2 days");

        List<SigningKeyStore.PrunedKey> pruned = signingKeyStore.pruneRetiredOlderThan(Duration.ofHours(24));

        assertThat(pruned).hasSize(1);
        assertThat(pruned.getFirst().tenantId()).isEqualTo(tenant.id());
        assertThat(pruned.getFirst().kid()).isEqualTo(outcome.oldKid());
        assertThat(rowCount(tenant.id())).isEqualTo(1);
        Integer retiredRows = jdbcTemplate.queryForObject(
            "SELECT COUNT(*) FROM tenant_signing_key WHERE tenant_id = ? AND status = 'RETIRED'",
            Integer.class, tenant.id());
        assertThat(retiredRows).isZero();
    }

    @Test
    @DisplayName("pruneRetiredOlderThan leaves RETIRED rows still inside the grace window untouched")
    void pruneLeavesRowsStillInsideGraceWindow() {
        signingKeyStore.createForTenant(tenant.id());
        SigningKeyStore.RotationOutcome outcome = signingKeyStore.rotateForTenant(tenant.id());
        // retired_at defaults to CURRENT_TIMESTAMP — well inside any sane grace.

        List<SigningKeyStore.PrunedKey> pruned = signingKeyStore.pruneRetiredOlderThan(Duration.ofHours(24));

        assertThat(pruned).isEmpty();
        assertThat(rowCount(tenant.id())).isEqualTo(2);
        String retiredKid = jdbcTemplate.queryForObject(
            "SELECT kid FROM tenant_signing_key WHERE tenant_id = ? AND status = 'RETIRED'",
            String.class, tenant.id());
        assertThat(retiredKid).isEqualTo(outcome.oldKid());
    }

    @Test
    @DisplayName("pruneRetiredOlderThan never touches ACTIVE rows even if the partial unique index made them old")
    void pruneNeverTouchesActiveRows() {
        signingKeyStore.createForTenant(tenant.id());
        // Force the ACTIVE row's created_at into the distant past — prune must
        // ignore it because the WHERE clause filters on status='RETIRED'.
        jdbcTemplate.update(
            "UPDATE tenant_signing_key SET created_at = CURRENT_TIMESTAMP - INTERVAL '365 days' " +
                "WHERE tenant_id = ? AND status = 'ACTIVE'",
            tenant.id());

        List<SigningKeyStore.PrunedKey> pruned = signingKeyStore.pruneRetiredOlderThan(Duration.ofHours(1));

        assertThat(pruned).isEmpty();
        assertThat(rowCount(tenant.id())).isEqualTo(1);
    }

    private void backdateRetiredAt(long tenantId, String kid, String pgInterval) {
        int updated = jdbcTemplate.update(
            "UPDATE tenant_signing_key SET retired_at = CURRENT_TIMESTAMP - INTERVAL '" + pgInterval + "' " +
                "WHERE tenant_id = ? AND kid = ?",
            tenantId, kid);
        assertThat(updated).isEqualTo(1);
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
