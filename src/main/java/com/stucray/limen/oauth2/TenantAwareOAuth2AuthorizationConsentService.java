package com.stucray.limen.oauth2;

import com.stucray.limen.tenant.TenantScope;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.server.authorization.JdbcOAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsent;
import org.springframework.security.oauth2.server.authorization.OAuth2AuthorizationConsentService;
import org.springframework.security.oauth2.server.authorization.client.RegisteredClientRepository;
import org.springframework.util.Assert;
import org.springframework.util.StringUtils;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Tenant-scoped OAuth2AuthorizationConsentService.
 *
 * Spring's JdbcOAuth2AuthorizationConsentService writes (registered_client_id, principal_name,
 * authorities) — its INSERT cannot supply tenant_id, so a delegate-then-UPDATE strategy would
 * violate the parent PRD's NOT NULL composite PK on (tenant_id, registered_client_id,
 * principal_name). Instead we write tenant-aware SQL directly and reuse Spring's public
 * row mapper / parameters mapper for value translation.
 *
 * All operations require an active TenantScope; missing scope throws IllegalStateException.
 */
public class TenantAwareOAuth2AuthorizationConsentService implements OAuth2AuthorizationConsentService {

    private static final String INSERT_SQL =
        "INSERT INTO oauth2_authorization_consent "
        + "(tenant_id, registered_client_id, principal_name, authorities) VALUES (?, ?, ?, ?)";

    private static final String UPDATE_SQL =
        "UPDATE oauth2_authorization_consent SET authorities = ? "
        + "WHERE tenant_id = ? AND registered_client_id = ? AND principal_name = ?";

    private static final String DELETE_SQL =
        "DELETE FROM oauth2_authorization_consent "
        + "WHERE tenant_id = ? AND registered_client_id = ? AND principal_name = ?";

    private static final String SELECT_SQL =
        "SELECT registered_client_id, principal_name, authorities FROM oauth2_authorization_consent "
        + "WHERE tenant_id = ? AND registered_client_id = ? AND principal_name = ?";

    private final JdbcTemplate jdbcTemplate;
    private final JdbcOAuth2AuthorizationConsentService.OAuth2AuthorizationConsentRowMapper rowMapper;

    public TenantAwareOAuth2AuthorizationConsentService(
        JdbcTemplate jdbcTemplate,
        RegisteredClientRepository registeredClientRepository
    ) {
        Assert.notNull(jdbcTemplate, "jdbcTemplate cannot be null");
        Assert.notNull(registeredClientRepository, "registeredClientRepository cannot be null");
        this.jdbcTemplate = jdbcTemplate;
        this.rowMapper =
            new JdbcOAuth2AuthorizationConsentService.OAuth2AuthorizationConsentRowMapper(registeredClientRepository);
    }

    @Override
    public void save(OAuth2AuthorizationConsent authorizationConsent) {
        Assert.notNull(authorizationConsent, "authorizationConsent cannot be null");
        Long tenantId = requireTenantId();
        String authorities = serializedAuthorities(authorizationConsent);
        OAuth2AuthorizationConsent existing = findById(
            authorizationConsent.getRegisteredClientId(),
            authorizationConsent.getPrincipalName()
        );
        if (existing == null) {
            jdbcTemplate.update(
                INSERT_SQL,
                tenantId,
                authorizationConsent.getRegisteredClientId(),
                authorizationConsent.getPrincipalName(),
                authorities
            );
        } else {
            jdbcTemplate.update(
                UPDATE_SQL,
                authorities,
                tenantId,
                authorizationConsent.getRegisteredClientId(),
                authorizationConsent.getPrincipalName()
            );
        }
    }

    @Override
    public void remove(OAuth2AuthorizationConsent authorizationConsent) {
        Assert.notNull(authorizationConsent, "authorizationConsent cannot be null");
        Long tenantId = requireTenantId();
        jdbcTemplate.update(
            DELETE_SQL,
            tenantId,
            authorizationConsent.getRegisteredClientId(),
            authorizationConsent.getPrincipalName()
        );
    }

    @Override
    public @Nullable OAuth2AuthorizationConsent findById(String registeredClientId, String principalName) {
        Assert.hasText(registeredClientId, "registeredClientId cannot be empty");
        Assert.hasText(principalName, "principalName cannot be empty");
        Long tenantId = requireTenantId();
        List<OAuth2AuthorizationConsent> result = jdbcTemplate.query(
            SELECT_SQL, rowMapper, tenantId, registeredClientId, principalName
        );
        return result.isEmpty() ? null : result.get(0);
    }

    private static String serializedAuthorities(OAuth2AuthorizationConsent consent) {
        Set<String> authorities = new HashSet<>();
        for (GrantedAuthority authority : consent.getAuthorities()) {
            authorities.add(authority.getAuthority());
        }
        return StringUtils.collectionToDelimitedString(authorities, ",");
    }

    private static Long requireTenantId() {
        Long tenantId = TenantScope.tenantId();
        if (tenantId == null) {
            throw new IllegalStateException(
                "TenantAwareOAuth2AuthorizationConsentService called without TenantScope"
            );
        }
        return tenantId;
    }
}
