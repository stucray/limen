package com.stucray.limen.ui.support;

import org.springframework.security.oauth2.core.oidc.OidcScopes;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Flow plumbing (not a Page object) for the OAuth2 authorization-code-with-PKCE
 * journey. Generates the PKCE pair and assembles the SAS-tenant authorize URL.
 *
 * <p>The verifier is captured for symmetry with the production flow, but the
 * UI journey ends at the relying-party redirect (a code in the URL), never
 * POSTing to {@code /oauth2/token} — so the verifier is unused by the test.
 */
public final class OAuth2AuthorizeFlow {

    private OAuth2AuthorizeFlow() {}

    public record Pkce(String verifier, String challenge) {}

    public static Pkce newPkce() {
        try {
            byte[] verifierBytes = new byte[32];
            new SecureRandom().nextBytes(verifierBytes);
            String verifier = Base64.getUrlEncoder().withoutPadding().encodeToString(verifierBytes);
            byte[] hash = MessageDigest.getInstance("SHA-256")
                .digest(verifier.getBytes(StandardCharsets.US_ASCII));
            String challenge = Base64.getUrlEncoder().withoutPadding().encodeToString(hash);
            return new Pkce(verifier, challenge);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    public static String authorizeUrl(
        String baseUrl, String slug, String clientId, String redirectUri,
        String codeChallenge, String state
    ) {
        return UriComponentsBuilder.fromUriString(baseUrl + "/t/" + slug + "/oauth2/authorize")
            .queryParam("response_type", "code")
            .queryParam("client_id", clientId)
            .queryParam("redirect_uri", redirectUri)
            .queryParam("scope", OidcScopes.OPENID)
            .queryParam("state", state)
            .queryParam("code_challenge", codeChallenge)
            .queryParam("code_challenge_method", "S256")
            .build().toUriString();
    }
}
