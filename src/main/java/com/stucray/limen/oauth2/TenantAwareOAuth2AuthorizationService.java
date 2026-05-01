package com.stucray.limen.oauth2;

import com.stucray.limen.tenant.TenantScope;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2Authorization;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationService;
import org.springframework.security.oauth2.server.authorization.OAuth2TokenType;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.util.Assert;
import tools.jackson.databind.json.JsonMapper;

import java.util.List;

/**
 * Decorates an OAuth2AuthorizationService to enforce tenant isolation at the storage layer.
 *
 * Save delegates to the wrapped service then UPDATEs tenant_id on the inserted row from
 * TenantScope. Reads run tenant-filtered SQL using Spring's OAuth2AuthorizationRowMapper.
 * findByToken delegates to inherit Spring's column-routing for token types, then re-fetches
 * via the tenant-filtered findById.
 *
 * All operations require an active TenantScope; missing scope throws IllegalStateException.
 */
public class TenantAwareOAuth2AuthorizationService implements OAuth2AuthorizationService {

    private final OAuth2AuthorizationService delegate;
    private final JdbcTemplate jdbcTemplate;
    private final JdbcOAuth2AuthorizationService.JsonMapperOAuth2AuthorizationRowMapper rowMapper;

    public TenantAwareOAuth2AuthorizationService(
        OAuth2AuthorizationService delegate,
        JdbcTemplate jdbcTemplate,
        RegisteredClientRepository registeredClientRepository,
        JsonMapper jsonMapper
    ) {
        this.delegate = delegate;
        this.jdbcTemplate = jdbcTemplate;
        this.rowMapper = new JdbcOAuth2AuthorizationService.JsonMapperOAuth2AuthorizationRowMapper(
            registeredClientRepository, jsonMapper);
    }

    @Override
    public void save(OAuth2Authorization authorization) {
        Assert.notNull(authorization, "authorization cannot be null");
        Long tenantId = requireTenantId();
        delegate.save(authorization);
        jdbcTemplate.update(
            "UPDATE oauth2_authorization SET tenant_id = ? WHERE id = ? AND (tenant_id IS NULL OR tenant_id = ?)",
            tenantId, authorization.getId(), tenantId
        );
    }

    @Override
    public void remove(OAuth2Authorization authorization) {
        Assert.notNull(authorization, "authorization cannot be null");
        requireTenantId();
        delegate.remove(authorization);
    }

    @Override
    public @Nullable OAuth2Authorization findById(String id) {
        Assert.hasText(id, "id cannot be empty");
        Long tenantId = requireTenantId();
        List<OAuth2Authorization> result = jdbcTemplate.query(
            "SELECT * FROM oauth2_authorization WHERE id = ? AND tenant_id = ?",
            rowMapper, id, tenantId
        );
        return result.isEmpty() ? null : result.get(0);
    }

    @Override
    public @Nullable OAuth2Authorization findByToken(String token, @Nullable OAuth2TokenType tokenType) {
        Assert.hasText(token, "token cannot be empty");
        Long tenantId = requireTenantId();
        String sql;
        Object[] params;
        String column = tokenColumn(tokenType);
        if (column == null) {
            sql = "SELECT id FROM oauth2_authorization WHERE tenant_id = ? AND ("
                + "state = ? OR authorization_code_value = ? OR access_token_value = ? OR "
                + "oidc_id_token_value = ? OR refresh_token_value = ? OR user_code_value = ? OR "
                + "device_code_value = ?)";
            params = new Object[]{tenantId, token, token, token, token, token, token, token};
        } else {
            sql = "SELECT id FROM oauth2_authorization WHERE tenant_id = ? AND " + column + " = ?";
            params = new Object[]{tenantId, token};
        }
        List<String> ids = jdbcTemplate.queryForList(sql, String.class, params);
        return ids.isEmpty() ? null : findById(ids.get(0));
    }

    private static @Nullable String tokenColumn(@Nullable OAuth2TokenType tokenType) {
        if (tokenType == null) return null;
        return switch (tokenType.getValue()) {
            case "state" -> "state";
            case "code" -> "authorization_code_value";
            case "access_token" -> "access_token_value";
            case "id_token" -> "oidc_id_token_value";
            case "refresh_token" -> "refresh_token_value";
            case "user_code" -> "user_code_value";
            case "device_code" -> "device_code_value";
            default -> null;
        };
    }

    private static Long requireTenantId() {
        Long tenantId = TenantScope.tenantId();
        if (tenantId == null) {
            throw new IllegalStateException(
                "TenantAwareOAuth2AuthorizationService called without TenantScope"
            );
        }
        return tenantId;
    }
}
