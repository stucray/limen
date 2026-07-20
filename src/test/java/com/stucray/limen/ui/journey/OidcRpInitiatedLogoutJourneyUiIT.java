package com.stucray.limen.ui.journey;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Response;
import com.stucray.limen.ui.support.BaseUiIT;
import com.stucray.limen.ui.support.OAuth2AuthorizeFlow;
import com.stucray.limen.ui.support.OAuth2AuthorizeFlow.Pkce;
import com.stucray.limen.ui.support.TestOAuth2RelyingParty;
import com.stucray.limen.ui.support.TestTenantFactory.SeededApplication;
import com.stucray.limen.ui.support.TestTenantFactory.SeededLogoutClient;
import com.stucray.limen.ui.support.TestTenantFactory.SeededTenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Reproduction + regression guard for #324: OIDC RP-initiated logout via
 * {@code /t/{slug}/connect/logout} when the End-User has an active OP session.
 *
 * <p>The MockMvc wiring test ({@code OidcLogoutLoopbackIntegrationTest}) drives
 * the same flow but does not populate the {@code SessionRegistry} the way a real
 * servlet container + login does, so it never exercises SAS's active-session
 * branch (the {@code sub}/{@code sid}-claim validation that only runs when a
 * principal is present). This test signs the End-User in through a real browser
 * (so the OP session cookie + SessionRegistry entry exist), exchanges the code
 * for an {@code id_token} over the back channel, then drives the browser — still
 * carrying the OP session — to the end-session endpoint.
 *
 * <p>Two cases:
 * <ul>
 *   <li><b>success</b>: a valid request redirects to the registered
 *       {@code post_logout_redirect_uri}.</li>
 *   <li><b>failure</b>: a request that fails SAS validation must return a
 *       <em>renderable</em> error, not an empty {@code 403}. SAS's default
 *       failure handler calls {@code response.sendError(400)}, which the
 *       servlet container ERROR-dispatches to {@code /error}; the catch-all
 *       security chain {@code denyAll()}s {@code /error} and emits a bodyless
 *       {@code 403} the browser cannot render (#324, same class as #293).</li>
 * </ul>
 */
@Tag("cross-browser")
@Import(TestOAuth2RelyingParty.class)
@DisplayName("OIDC RP-initiated logout with an active OP session (#324)")
class OidcRpInitiatedLogoutJourneyUiIT extends BaseUiIT {

    @Autowired
    private TestOAuth2RelyingParty.TestRpCallbackController rp;

    @Test
    @DisplayName("a valid GET /connect/logout (active session) redirects the browser to the registered post_logout_redirect_uri")
    void activeSessionLogoutRedirects(Page page) {
        SeededTenant tenant = tenants.createTenant();
        SeededApplication app = tenants.seedApplication(tenant);
        SeededLogoutClient client = seedLogoutClient(tenant, app);
        String idToken = signInAndMintIdToken(page, tenant, client);

        String logoutUrl = UriComponentsBuilder
            .fromUriString(baseUrl() + "/t/" + tenant.slug() + "/connect/logout")
            .queryParam("id_token_hint", idToken)
            .queryParam("post_logout_redirect_uri", client.postLogoutRedirectUri())
            .queryParam("state", "logout-state")
            .build().toUriString();
        page.navigate(logoutUrl);

        page.waitForURL(client.postLogoutRedirectUri() + "*");
        assertThat(page.url())
            .as("active-session logout must redirect to the registered post_logout_redirect_uri")
            .startsWith(client.postLogoutRedirectUri());
    }

    @Test
    @DisplayName("a GET /connect/logout that fails validation (active session) returns a renderable error, not a bodyless 403")
    void activeSessionLogoutFailureIsRenderable(Page page) {
        SeededTenant tenant = tenants.createTenant();
        SeededApplication app = tenants.seedApplication(tenant);
        SeededLogoutClient client = seedLogoutClient(tenant, app);
        String idToken = signInAndMintIdToken(page, tenant, client);

        // An unregistered, non-loopback post_logout_redirect_uri fails SAS's
        // logout validation. The validation verdict isn't the point — the
        // response *shape* is: it must be renderable, not the bodyless 403 the
        // /error-forward path produced (#324).
        String logoutUrl = UriComponentsBuilder
            .fromUriString(baseUrl() + "/t/" + tenant.slug() + "/connect/logout")
            .queryParam("id_token_hint", idToken)
            .queryParam("post_logout_redirect_uri", "https://unregistered.example.test/logged-out")
            .build().toUriString();

        Response response = page.navigate(logoutUrl);

        assertThat(response).isNotNull();
        assertThat(response.status())
            .as("a logout validation failure must surface as a client error, not the catch-all chain's bodyless 403 forwarded from /error")
            .isNotEqualTo(403);
        assertThat(response.text())
            .as("the error response must carry a renderable body so the browser does not show its own hard error page")
            .isNotBlank();
    }

    private SeededLogoutClient seedLogoutClient(SeededTenant tenant, SeededApplication app) {
        return tenants.seedOAuth2ClientWithPostLogout(
            tenant, app, baseUrl() + "/test-rp/callback", baseUrl() + "/test-rp/logged-out");
    }

    /**
     * Signs the seeded end-user in through the browser (establishing the active
     * OP session) and back-channel-exchanges the resulting code for an
     * {@code id_token} carrying the {@code sid} claim minted against that
     * session.
     */
    private String signInAndMintIdToken(Page page, SeededTenant tenant, SeededLogoutClient client) {
        Pkce pkce = OAuth2AuthorizeFlow.newPkce();
        String state = UUID.randomUUID().toString();
        String authorizeUrl = OAuth2AuthorizeFlow.authorizeUrl(
            baseUrl(), tenant.slug(), client.clientId(), client.redirectUri(), pkce.challenge(), state);

        page.navigate(authorizeUrl);
        page.waitForURL("**/t/" + tenant.slug() + "/login*"); // login URL now carries ?ref= (issue #327)
        page.getByLabel("Email").fill(tenant.endUserEmail());
        page.getByLabel("Password").fill(tenant.endUserPassword());
        page.getByTestId("login-submit").click();
        page.waitForURL(client.redirectUri() + "*");
        String code = rp.awaitCallback(state, Duration.ofSeconds(5));

        RestClient rest = RestClient.create();
        Map<String, Object> tokenResponse = rest.post()
            .uri(baseUrl() + "/t/" + tenant.slug() + "/oauth2/token")
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(tokenExchangeBody(code, client.redirectUri(), client.clientId(), pkce.verifier()))
            .retrieve()
            .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        String idToken = (String) tokenResponse.get("id_token");
        assertThat(idToken).as("id_token is required to drive RP-initiated logout").isNotBlank();
        return idToken;
    }

    private static MultiValueMap<String, String> tokenExchangeBody(
        String code, String redirectUri, String clientId, String codeVerifier
    ) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("code", code);
        body.add("redirect_uri", redirectUri);
        body.add("client_id", clientId);
        body.add("code_verifier", codeVerifier);
        return body;
    }
}
