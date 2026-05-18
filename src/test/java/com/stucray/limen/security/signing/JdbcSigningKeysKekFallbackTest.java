package com.stucray.limen.security.signing;

import com.nimbusds.jose.jwk.RSAKey;
import com.stucray.limen.TestcontainersConfiguration;
import com.stucray.limen.security.SecurityProperties;
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

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@Import(TestcontainersConfiguration.class)
@SpringBootTest
@DisplayName("JdbcSigningKeys decrypts with the previous KEK when the active fails (lazy migration)")
class JdbcSigningKeysKekFallbackTest {

    private static final String KEK_A = randomKek();
    private static final String KEK_B = randomKek();
    private static final String KEK_C = randomKek();

    @Autowired JdbcTemplate jdbcTemplate;
    @Autowired TenantRepository tenantRepository;

    Tenant tenant;

    @BeforeEach
    void setUp() {
        tenant = tenantRepository.save(new Tenant(
            null, "kek-" + System.nanoTime(), "KEK Fallback Test", TenantStatus.ACTIVE, LocalDateTime.now()
        ));
    }

    @Test
    @DisplayName("Active KEK works — row is not re-wrapped even when a previous KEK is configured")
    void activeKekFastPathDoesNotRewrap() {
        JdbcSigningKeys signingKeys = signingKeysWith(KEK_A, KEK_B);
        signingKeys.createForTenant(tenant.id());
        StoredRow before = currentRow(tenant.id());

        RSAKey key = signingKeys.getActiveSigningKey(tenant.id());

        assertThat(key).isNotNull();
        StoredRow after = currentRow(tenant.id());
        assertThat(after.ciphertext()).containsExactly(before.ciphertext());
        assertThat(after.salt()).containsExactly(before.salt());
    }

    @Test
    @DisplayName("Active fails, previous works — returns the key and re-wraps the row with the active KEK")
    void rewrapsLazilyOnFallback() {
        signingKeysWith(KEK_A, null).createForTenant(tenant.id());
        StoredRow original = currentRow(tenant.id());

        JdbcSigningKeys rotated = signingKeysWith(KEK_B, KEK_A);
        RSAKey key = rotated.getActiveSigningKey(tenant.id());

        assertThat(key).isNotNull();
        StoredRow afterRewrap = currentRow(tenant.id());
        assertThat(afterRewrap.ciphertext()).isNotEqualTo(original.ciphertext());
        assertThat(afterRewrap.salt()).isNotEqualTo(original.salt());

        // Subsequent read takes the active-KEK fast path with no previous configured.
        JdbcSigningKeys activeOnly = signingKeysWith(KEK_B, null);
        assertThat(activeOnly.getActiveSigningKey(tenant.id())).isNotNull();
    }

    @Test
    @DisplayName("Both KEKs fail — surfaces the active-KEK failure (operator's first hypothesis)")
    void bothKeksFailSurfacesActiveFailure() {
        signingKeysWith(KEK_A, null).createForTenant(tenant.id());

        JdbcSigningKeys neitherMatches = signingKeysWith(KEK_B, KEK_C);

        assertThatThrownBy(() -> neitherMatches.getActiveSigningKey(tenant.id()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("bad padding");
    }

    @Test
    @DisplayName("Active KEK fails, no previous configured — the active-KEK failure propagates unchanged")
    void activeFailsWithoutPreviousPropagates() {
        signingKeysWith(KEK_A, null).createForTenant(tenant.id());

        JdbcSigningKeys wrongActiveNoFallback = signingKeysWith(KEK_B, null);

        assertThatThrownBy(() -> wrongActiveNoFallback.getActiveSigningKey(tenant.id()))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("bad padding");
    }

    private JdbcSigningKeys signingKeysWith(String activeKek, String previousKek) {
        return new JdbcSigningKeys(jdbcTemplate, new SecurityProperties(activeKek, previousKek));
    }

    private StoredRow currentRow(long tenantId) {
        return jdbcTemplate.queryForObject(
            "SELECT private_key_ciphertext, pbkdf2_salt FROM tenant_signing_key " +
                "WHERE tenant_id = ? AND status = 'ACTIVE'",
            (rs, rowNum) -> new StoredRow(
                rs.getBytes("private_key_ciphertext"),
                rs.getBytes("pbkdf2_salt")
            ),
            tenantId
        );
    }

    private record StoredRow(byte[] ciphertext, byte[] salt) {}

    private static String randomKek() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getEncoder().encodeToString(bytes);
    }
}
