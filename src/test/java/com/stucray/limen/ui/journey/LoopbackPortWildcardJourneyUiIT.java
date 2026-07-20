package com.stucray.limen.ui.journey;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import com.stucray.limen.ui.pages.manage.ManageHomePage;
import com.stucray.limen.ui.support.BaseUiIT;
import com.stucray.limen.ui.support.LoginPageObject;
import com.stucray.limen.ui.support.TestTenantFactory.SeededApplication;
import com.stucray.limen.ui.support.TestTenantFactory.SeededTenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.MediaType;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;
import org.springframework.web.util.UriComponentsBuilder;

import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Full end-to-end pin for {@code PRD #316}'s loopback port wildcarding: register
 * a client through the manage UI with one loopback redirect URI + one loopback
 * post-logout redirect URI, then drive sign-in from a <em>different</em>
 * loopback port and RP-initiated logout to <em>yet another</em> different
 * loopback port — and assert both 302s land. Exercises the full stack:
 * slice 2's {@code LoopbackAwareOidcLogoutValidator}, SAS's built-in
 * {@code /authorize} loopback branch, and Limen's OIDC wiring.
 *
 * <p>{@code LoopbackAwareOidcLogoutValidatorTest} + {@code OidcLogoutLoopbackIntegrationTest}
 * pin the decision logic and HTTP wiring at lower levels; this journey is the
 * consumer-facing proof that a registered loopback URI matches any-port
 * symmetrically on sign-in and sign-out, the way RFC 8252 §7.3 (BCP 212)
 * anticipates and the way the local-setup documentation describes.
 *
 * <p>The callback ports the journey uses have no listener — Playwright
 * intercepts the navigation client-side via {@code page.route(...)} and the
 * assertion runs against the captured request URL, so the test is independent
 * of OS port availability.
 *
 * <p>Client is registered as confidential to match the existing manage-UI
 * defaults (the form's {@code confidential} checkbox defaults to {@code true};
 * unchecking the box submits no value, so {@code CreateClientForm} falls back
 * to {@code true} — see {@code CreateClientForm} class javadoc). The
 * loopback-port wildcarding behaviour under test is independent of client
 * authentication method.
 */
@Tag("cross-browser")
@DisplayName("Loopback port wildcarding holds end-to-end: one registered loopback URI matches any request port on /authorize AND /connect/logout")
class LoopbackPortWildcardJourneyUiIT extends BaseUiIT {

    private static final String REGISTERED_REDIRECT_URI = "http://127.0.0.1:8080/callback";
    private static final String REGISTERED_POST_LOGOUT_URI = "http://127.0.0.1:8080/logged-out";
    private static final String REQUESTED_REDIRECT_URI = "http://127.0.0.1:54801/callback";
    private static final String REQUESTED_POST_LOGOUT_URI = "http://127.0.0.1:54802/logged-out";
    private static final String AUTHZ_STATE = "loopback-authz-state";
    private static final String LOGOUT_STATE = "loopback-logout-state";

    @Test
    @DisplayName("happy path: admin registers a confidential AC client via the manage UI with port-8080 loopback URIs; end user signs in via /authorize with a different loopback port and the callback receives ?code=…; the RP exchanges the code for an id_token; RP-initiated /connect/logout to yet another loopback port redirects with the matching state")
    void loopbackWildcardJourney(Page page) {
        SeededTenant tenant = tenants.createTenant();
        SeededApplication app = tenants.seedApplication(tenant);
        String clientName = "Loopback Client " + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        // 1. Admin registers the client through the new-client form with both
        //    a loopback redirect URI and a loopback post-logout redirect URI.
        new LoginPageObject(page, baseUrl()).loginAsTenantAdmin(tenant);
        new ManageHomePage(page, baseUrl(), tenant.slug())
            .clickApplications()
            .clickClientsForApplication(app.name())
            .clickNewClient()
            .assertOnForm();

        page.getByLabel("Name").fill(clientName);
        page.getByLabel("Authorization code").check();
        page.locator("textarea[name=redirectUris]").fill(REGISTERED_REDIRECT_URI);
        page.locator("textarea[name=postLogoutRedirectUris]").fill(REGISTERED_POST_LOGOUT_URI);
        page.locator("input[name=scopes]").fill("openid");
        page.getByTestId("clients-create-submit").click();
        page.waitForURL(baseUrl() + "/manage/t/" + tenant.slug() + "/applications/*/clients");

        String clientId = page.locator("[data-test-flash=client-id]").textContent();
        String clientSecret = page.locator("[data-test-flash=client-secret]").textContent();
        assertThat(clientId).isNotBlank();
        assertThat(clientSecret).isNotBlank();

        // 2. Grant end-user app + client membership (MembershipGateFilter; see
        //    OAuth2EndToEndJourneyUiIT for the same pattern).
        tenants.grantEndUserAccessToClient(tenant, app, clientId);

        // 3. Drop the admin session before driving the end-user flow.
        page.context().clearCookies();

        // 4. Drive /authorize with a redirect_uri on a DIFFERENT loopback port
        //    than the one registered. SAS's built-in /authorize loopback branch
        //    (SAS #243) must accept this even though the port differs from
        //    REGISTERED_REDIRECT_URI. No listener at REQUESTED_REDIRECT_URI's
        //    port, so intercept client-side.
        page.route(REQUESTED_REDIRECT_URI + "*", Route::abort);
        String authorizeUrl = UriComponentsBuilder
            .fromUriString(baseUrl() + "/t/" + tenant.slug() + "/oauth2/authorize")
            .queryParam("response_type", "code")
            .queryParam("client_id", clientId)
            .queryParam("redirect_uri", REQUESTED_REDIRECT_URI)
            .queryParam("scope", "openid")
            .queryParam("state", AUTHZ_STATE)
            .build().toUriString();
        page.navigate(authorizeUrl);
        page.waitForURL("**/t/" + tenant.slug() + "/login*"); // login URL now carries ?ref= (issue #327)
        page.getByLabel("Email").fill(tenant.endUserEmail());
        page.getByLabel("Password").fill(tenant.endUserPassword());
        Request callback = page.waitForRequest(REQUESTED_REDIRECT_URI + "*", () ->
            page.getByTestId("login-submit").click());

        assertThat(callback.url())
            .as("SAS must accept the cross-port loopback redirect_uri and send the browser to the requested URI with a code; got %s",
                callback.url())
            .startsWith(REQUESTED_REDIRECT_URI);
        assertThat(callback.url()).contains("code=");
        assertThat(callback.url()).contains("state=" + AUTHZ_STATE);
        String code = extractQueryParam(callback.url(), "code");
        assertThat(code).isNotBlank();

        // 5. Exchange the code for tokens out-of-band. SAS's /oauth2/token
        //    validates redirect_uri against the one used at /authorize — same
        //    loopback wildcard applies, so REQUESTED_REDIRECT_URI works.
        RestClient rest = RestClient.create();
        MultiValueMap<String, String> tokenBody = new LinkedMultiValueMap<>();
        tokenBody.add("grant_type", "authorization_code");
        tokenBody.add("code", code);
        tokenBody.add("redirect_uri", REQUESTED_REDIRECT_URI);
        Map<String, Object> tokens = rest.post()
            .uri(baseUrl() + "/t/" + tenant.slug() + "/oauth2/token")
            .header("Authorization", basicAuth(clientId, clientSecret))
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(tokenBody)
            .retrieve()
            .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        String idToken = (String) tokens.get("id_token");
        assertThat(idToken)
            .as("the id_token is required as the id_token_hint for RP-initiated logout")
            .isNotBlank();

        // 6. RP-initiated /connect/logout with a post_logout_redirect_uri on a
        //    DIFFERENT loopback port than the one registered. This is the
        //    branch slice 2's LoopbackAwareOidcLogoutValidator owns — without
        //    it, the request would fail with "invalid_redirect_uri". Same
        //    client-side intercept pattern: no listener at the requested port.
        page.route(REQUESTED_POST_LOGOUT_URI + "*", Route::abort);
        String logoutUrl = UriComponentsBuilder.fromUriString(
                baseUrl() + "/t/" + tenant.slug() + "/connect/logout")
            .queryParam("id_token_hint", idToken)
            .queryParam("post_logout_redirect_uri", REQUESTED_POST_LOGOUT_URI)
            .queryParam("state", LOGOUT_STATE)
            .build().toUriString();
        // page.navigate() waits for the load event on the final URL — but the
        // final URL here (the requested post-logout callback) is aborted by
        // the route above, so navigate() throws CONNECTION_REFUSED before
        // waitForRequest gets to capture it. Drive the navigation via
        // window.location.href = url instead: it kicks off the navigation
        // synchronously from the JS side and returns immediately, leaving the
        // captured request as the test's signal.
        Request loggedOut = page.waitForRequest(REQUESTED_POST_LOGOUT_URI + "*", () ->
            page.evaluate("url => { window.location.href = url; }", logoutUrl));

        assertThat(loggedOut.url())
            .as("custom logout validator must accept the cross-port loopback post_logout_redirect_uri and send the browser to the requested URI; got %s",
                loggedOut.url())
            .startsWith(REQUESTED_POST_LOGOUT_URI);
        assertThat(loggedOut.url()).contains("state=" + LOGOUT_STATE);
    }

    private static String basicAuth(String clientId, String clientSecret) {
        String credentials = clientId + ":" + clientSecret;
        return "Basic " + Base64.getEncoder().encodeToString(
            credentials.getBytes(StandardCharsets.UTF_8));
    }

    private static String extractQueryParam(String url, String name) {
        return UriComponentsBuilder.fromUriString(url).build()
            .getQueryParams()
            .getFirst(name);
    }
}
