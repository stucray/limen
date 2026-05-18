package com.stucray.limen.oauth2.sas;

import com.stucray.limen.user.TenantUserDetails;
import org.jspecify.annotations.Nullable;
import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.security.oauth2.core.oidc.StandardClaimNames;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;

import java.util.List;
import java.util.Set;

/**
 * Single source of truth for which OIDC standard scopes Limen honours and which
 * claims each one populates. Read by both the discovery customizer (advertises
 * {@code scopes_supported} and {@code claims_supported}) and the ID-token JWT
 * customizer (populates the claims when the corresponding scope is in the
 * granted scope set). The default Spring Authorization Server userinfo mapper
 * filters the ID-token claims by granted scope using the same OIDC standard
 * mapping, so no separate userinfo wiring is required to keep userinfo in
 * lockstep.
 *
 * <p>Discovery field names ({@code scopes_supported}, {@code claims_supported})
 * are the literal strings defined by the OIDC Discovery 1.0 spec — Spring SAS
 * has no constants for them.
 */
final class OidcScopeClaims {

    static final String SCOPES_SUPPORTED = "scopes_supported";
    static final String CLAIMS_SUPPORTED = "claims_supported";

    static final List<String> SUPPORTED_SCOPES = List.of(
        OidcScopes.OPENID,
        OidcScopes.EMAIL
    );

    private static final List<String> OPENID_BASE_CLAIMS = List.of(
        StandardClaimNames.SUB,
        "iss", "aud", "exp", "iat", "auth_time", "nonce"
    );

    private static final List<String> EMAIL_CLAIMS = List.of(
        StandardClaimNames.EMAIL,
        StandardClaimNames.EMAIL_VERIFIED
    );

    static final List<String> SUPPORTED_CLAIMS = concat(OPENID_BASE_CLAIMS, EMAIL_CLAIMS);

    private OidcScopeClaims() {}

    /**
     * Populate ID-token claims based on which standard scopes were granted at
     * /oauth2/authorize. Called from the ID-token branch of the JWT customizer.
     * No-op when the principal isn't a {@link TenantUserDetails} (e.g.
     * client_credentials flows have no end user, so no email to disclose).
     */
    static void addClaimsForGrantedScopes(
        JwtClaimsSet.Builder claims,
        Set<String> grantedScopes,
        @Nullable Object principal
    ) {
        if (!(principal instanceof TenantUserDetails details)) return;
        if (grantedScopes.contains(OidcScopes.EMAIL)) {
            claims.claim(StandardClaimNames.EMAIL, details.user().email());
            claims.claim(StandardClaimNames.EMAIL_VERIFIED, details.user().emailVerified());
        }
    }

    private static List<String> concat(List<String> a, List<String> b) {
        return List.copyOf(java.util.stream.Stream.concat(a.stream(), b.stream()).toList());
    }
}
