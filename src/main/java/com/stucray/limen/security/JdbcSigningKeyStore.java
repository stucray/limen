package com.stucray.limen.security;

import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.text.ParseException;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

@Component
public class JdbcSigningKeyStore implements SigningKeyStore {

    private static final String ALGORITHM = "RS256";
    private static final int RSA_KEY_SIZE = 2048;
    private static final int SALT_BYTES = 16;
    private static final int KEK_BYTES = 32;

    private final JdbcTemplate jdbcTemplate;
    private final String kekPassword;

    public JdbcSigningKeyStore(
        JdbcTemplate jdbcTemplate,
        @Value("${LIMEN_KEY_ENCRYPTION_KEY:}") String kek
    ) {
        this.jdbcTemplate = jdbcTemplate;
        this.kekPassword = validateKek(kek);
    }

    private static String validateKek(String kek) {
        if (kek == null || kek.isBlank()) {
            throw new IllegalStateException(
                "LIMEN_KEY_ENCRYPTION_KEY is not set. Provide a base64-encoded 256-bit AES key."
            );
        }
        byte[] decoded;
        try {
            decoded = Base64.getDecoder().decode(kek);
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException(
                "LIMEN_KEY_ENCRYPTION_KEY is not valid base64: " + e.getMessage(), e
            );
        }
        if (decoded.length != KEK_BYTES) {
            throw new IllegalStateException(
                "LIMEN_KEY_ENCRYPTION_KEY must decode to " + KEK_BYTES + " bytes (256 bits); got " + decoded.length
            );
        }
        return kek;
    }

    @Override
    public void createForTenant(long tenantId) {
        jdbcTemplate.execute((Connection conn) -> {
            insertActiveSigningKey(conn, tenantId, kekPassword);
            return null;
        });
    }

    private static void insertActiveSigningKey(Connection conn, long tenantId, String kekPassword) throws SQLException {
        RSAKey rsaKey;
        try {
            rsaKey = new RSAKeyGenerator(RSA_KEY_SIZE)
                .keyID(UUID.randomUUID().toString())
                .generate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to generate RSA key for tenant " + tenantId, e);
        }
        byte[] salt = new byte[SALT_BYTES];
        new SecureRandom().nextBytes(salt);
        byte[] ciphertext = Encryptors.stronger(kekPassword, HexFormat.of().formatHex(salt))
            .encrypt(rsaKey.toJSONString().getBytes(StandardCharsets.UTF_8));
        String publicJwk = rsaKey.toPublicJWK().toJSONString();

        try (PreparedStatement ps = conn.prepareStatement(
            "INSERT INTO tenant_signing_key " +
                "(tenant_id, kid, algorithm, private_key_ciphertext, iv, public_key_jwk, status) " +
                "VALUES (?, ?, ?, ?, ?, ?, 'ACTIVE')"
        )) {
            ps.setLong(1, tenantId);
            ps.setString(2, rsaKey.getKeyID());
            ps.setString(3, ALGORITHM);
            ps.setBytes(4, ciphertext);
            ps.setBytes(5, salt);
            ps.setString(6, publicJwk);
            ps.executeUpdate();
        }
    }

    @Override
    public RSAKey getActiveSigningKey(long tenantId) {
        return jdbcTemplate.query(
            "SELECT private_key_ciphertext, iv FROM tenant_signing_key " +
                "WHERE tenant_id = ? AND status = 'ACTIVE'",
            rs -> {
                if (!rs.next()) {
                    return null;
                }
                byte[] ciphertext = rs.getBytes("private_key_ciphertext");
                byte[] salt = rs.getBytes("iv");
                byte[] plaintext = encryptor(salt).decrypt(ciphertext);
                try {
                    return RSAKey.parse(new String(plaintext, StandardCharsets.UTF_8));
                } catch (ParseException e) {
                    throw new IllegalStateException(
                        "Stored signing key for tenant " + tenantId + " is corrupt", e
                    );
                }
            },
            tenantId
        );
    }

    @Override
    public JWKSet getJwkSet(long tenantId) {
        List<JWK> jwks = jdbcTemplate.query(
            "SELECT public_key_jwk FROM tenant_signing_key WHERE tenant_id = ?",
            (rs, rowNum) -> {
                try {
                    return JWK.parse(rs.getString("public_key_jwk"));
                } catch (ParseException e) {
                    throw new IllegalStateException(
                        "Stored public JWK for tenant " + tenantId + " is corrupt", e
                    );
                }
            },
            tenantId
        );
        return new JWKSet(jwks);
    }

    @Override
    public void deleteForTenant(long tenantId) {
        jdbcTemplate.update("DELETE FROM tenant_signing_key WHERE tenant_id = ?", tenantId);
    }

    private BytesEncryptor encryptor(byte[] salt) {
        return Encryptors.stronger(kekPassword, HexFormat.of().formatHex(salt));
    }
}
