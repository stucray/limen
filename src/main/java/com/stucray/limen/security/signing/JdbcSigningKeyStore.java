package com.stucray.limen.security.signing;

import com.stucray.limen.security.SecurityProperties;
import com.stucray.limen.security.SigningKeyStore;
import com.nimbusds.jose.jwk.JWK;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import org.jspecify.annotations.Nullable;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.encrypt.BytesEncryptor;
import org.springframework.security.crypto.encrypt.Encryptors;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.text.ParseException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

@Component
class JdbcSigningKeyStore implements SigningKeyStore {

    private static final String ALGORITHM = "RS256";
    private static final int RSA_KEY_SIZE = 2048;
    private static final int SALT_BYTES = 16;

    private final JdbcTemplate jdbcTemplate;
    private final String kekPassword;

    public JdbcSigningKeyStore(JdbcTemplate jdbcTemplate, SecurityProperties properties) {
        this.jdbcTemplate = jdbcTemplate;
        this.kekPassword = properties.kek();
    }

    @Override
    public void createForTenant(long tenantId) {
        jdbcTemplate.execute((Connection conn) -> {
            insertActiveSigningKey(conn, tenantId, kekPassword);
            return null;
        });
    }

    private static String insertActiveSigningKey(Connection conn, long tenantId, String kekPassword) throws SQLException {
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
        return rsaKey.getKeyID();
    }

    @Override
    public @Nullable RSAKey getActiveSigningKey(long tenantId) {
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
            "SELECT public_key_jwk FROM tenant_signing_key WHERE tenant_id = ? " +
                "ORDER BY (status = 'ACTIVE') DESC, created_at DESC",
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

    @Override
    @Transactional
    public RotationOutcome rotateForTenant(long tenantId) {
        // Order is forced: the partial unique index allows only one ACTIVE row
        // per tenant, so we must vacate ACTIVE before inserting a new one.
        String oldKid;
        try {
            oldKid = jdbcTemplate.queryForObject(
                "UPDATE tenant_signing_key SET status = 'RETIRED', retired_at = CURRENT_TIMESTAMP " +
                    "WHERE tenant_id = ? AND status = 'ACTIVE' RETURNING kid",
                String.class,
                tenantId
            );
        } catch (EmptyResultDataAccessException e) {
            throw new IllegalStateException(
                "Cannot rotate: tenant " + tenantId + " has no ACTIVE signing key", e);
        }
        String newKid = jdbcTemplate.execute((Connection conn) ->
            insertActiveSigningKey(conn, tenantId, kekPassword));
        return new RotationOutcome(
            Objects.requireNonNull(oldKid, "RETURNING kid must be non-null after non-empty UPDATE"),
            Objects.requireNonNull(newKid, "insertActiveSigningKey returned a non-null kid"));
    }

    @Override
    public List<Long> findTenantIdsWithActiveKeyOlderThan(Duration age) {
        return jdbcTemplate.query(
            "SELECT tenant_id FROM tenant_signing_key " +
                "WHERE status = 'ACTIVE' " +
                "AND created_at < CURRENT_TIMESTAMP - make_interval(secs => ?) " +
                "ORDER BY tenant_id",
            (rs, rowNum) -> rs.getLong("tenant_id"),
            age.toSeconds()
        );
    }

    @Override
    @Transactional
    public List<PrunedKey> pruneRetiredOlderThan(Duration grace) {
        // Threshold is computed in the database to stay coherent with the
        // retired_at value rotateForTenant() writes via CURRENT_TIMESTAMP. Using
        // make_interval(secs => ?) sidesteps Postgres's interval-literal cast
        // and lets JDBC bind the parameter as a plain BIGINT.
        return jdbcTemplate.query(
            "DELETE FROM tenant_signing_key " +
                "WHERE status = 'RETIRED' " +
                "AND retired_at < CURRENT_TIMESTAMP - make_interval(secs => ?) " +
                "RETURNING tenant_id, kid",
            (rs, rowNum) -> new PrunedKey(rs.getLong("tenant_id"), rs.getString("kid")),
            grace.toSeconds()
        );
    }

    private BytesEncryptor encryptor(byte[] salt) {
        return Encryptors.stronger(kekPassword, HexFormat.of().formatHex(salt));
    }
}
