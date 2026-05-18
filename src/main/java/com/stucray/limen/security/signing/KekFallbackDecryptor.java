package com.stucray.limen.security.signing;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.security.crypto.encrypt.Encryptors;

import javax.crypto.BadPaddingException;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Decrypts {@code tenant_signing_key.private_key_ciphertext} with the active KEK, falling
 * back to a previous-generation KEK on AES-GCM authentication failure and lazily re-wrapping
 * any row that comes back via the fallback. Lets KEK rotation happen without a flag-day
 * re-wrap migration; the column drains on its own as each tenant's key is next read.
 */
final class KekFallbackDecryptor {

    private static final int SALT_BYTES = 16;

    private final JdbcTemplate jdbcTemplate;
    private final String activeKek;
    private final Optional<String> previousKek;

    KekFallbackDecryptor(JdbcTemplate jdbcTemplate, String activeKek, Optional<String> previousKek) {
        this.jdbcTemplate = jdbcTemplate;
        this.activeKek = activeKek;
        this.previousKek = previousKek;
    }

    byte[] decrypt(long signingKeyId, byte[] ciphertext, byte[] salt) {
        try {
            return encryptor(activeKek, salt).decrypt(ciphertext);
        } catch (RuntimeException activeFail) {
            if (previousKek.isEmpty() || !causedByBadPadding(activeFail)) {
                throw activeFail;
            }
            byte[] plaintext;
            try {
                plaintext = encryptor(previousKek.get(), salt).decrypt(ciphertext);
            } catch (RuntimeException previousFail) {
                // Both KEKs failed. Surface the active-KEK failure so the operator's
                // first hypothesis is "the active KEK is wrong", not "the prior KEK
                // is wrong" — but keep the previous-KEK failure as a suppressed
                // exception so the full diagnostic chain is still in the log.
                activeFail.addSuppressed(previousFail);
                throw activeFail;
            }
            rewrapWithActiveKek(signingKeyId, plaintext);
            return plaintext;
        }
    }

    private void rewrapWithActiveKek(long signingKeyId, byte[] plaintext) {
        byte[] freshSalt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(freshSalt);
        byte[] freshCiphertext = encryptor(activeKek, freshSalt).encrypt(plaintext);
        jdbcTemplate.update(
            "UPDATE tenant_signing_key SET private_key_ciphertext = ?, pbkdf2_salt = ? WHERE id = ?",
            freshCiphertext, freshSalt, signingKeyId
        );
    }

    private static BytesEncryptor encryptor(String kek, byte[] salt) {
        return Encryptors.stronger(kek, HexFormat.of().formatHex(salt));
    }

    private static boolean causedByBadPadding(Throwable t) {
        for (Throwable cursor = t; cursor != null; cursor = cursor.getCause()) {
            if (cursor instanceof BadPaddingException) {
                return true;
            }
        }
        return false;
    }
}
