package com.stucray.limen.ui.journey;

import com.microsoft.playwright.Locator;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.assertions.PlaywrightAssertions;
import com.nimbusds.jwt.SignedJWT;
import com.stucray.limen.ui.pages.manage.ManageHomePage;
import com.stucray.limen.ui.support.BaseUiIT;
import com.stucray.limen.ui.support.LoginPageObject;
import com.stucray.limen.ui.support.TestOAuth2RelyingParty;
import com.stucray.limen.ui.support.TestTenantFactory.SeededApplication;
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

import java.nio.charset.StandardCharsets;
import java.text.ParseException;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end OAuth2 relying-party journey. The tenant admin creates a
 * confidential authorization-code client through the manage UI; the end user
 * completes the dance; an in-process test RP exchanges the code for an access
 * token and asserts the JWT's {@code sub} and that the token is accepted by
 * the userinfo endpoint.
 *
 * <p>This is the only test that exercises the full chain through a real
 * relying party. The existing {@code OAuth2AuthorizeJourneyUiIT} covers the
 * public-client + PKCE path and stops at the RP redirect — the two tests are
 * complementary, not overlapping.
 *
 * <p>What this test locks in (from the overround-driven cluster):
 * <ul>
 *   <li>#275: the Client ID flashed after create is the wire id, not the
 *       internal SAS PK — the test feeds the captured value back into
 *       {@code /oauth2/authorize} + {@code /oauth2/token}; a PK would fail
 *       with {@code invalid_client}.</li>
 *   <li>#277: {@code requireAuthorizationConsent} defaults off — the test
 *       creates a client through the form without touching the consent
 *       checkbox and expects the authorize redirect to resume directly to the
 *       RP callback, not stop at a consent screen.</li>
 *   <li>#278: scopes input is editable, parser splits on whitespace, helper
 *       text replaces the misleading placeholder — DOM assertions plus the
 *       scopes are exercised end-to-end against SAS.</li>
 *   <li>#279: Spring Security's {@code continue} marker on saved-request
 *       resume is stripped — the test starts anonymous and relies on the
 *       resume working without {@code access_denied}.</li>
 * </ul>
 */
@Tag("cross-browser")
@DisplayName("An end-to-end OAuth2 relying party drives /authorize → /token → /userinfo through a confidential authorization_code client created via the manage UI")
@Import(TestOAuth2RelyingParty.class)
class OAuth2EndToEndJourneyUiIT extends BaseUiIT {

    @Autowired
    private TestOAuth2RelyingParty.TestRpCallbackController rp;

    @Test
    @DisplayName("happy path: admin creates a confidential AC client via the form (consent default off, scopes whitespace-separated), end user logs in, saved-request resume reaches /test-rp/callback, RP exchanges code for token, JWT sub equals end-user email, userinfo accepts the bearer")
    void happyPath(Page page) {
        SeededTenant tenant = tenants.createTenant();
        SeededApplication app = tenants.seedApplication(tenant);
        String clientName = "E2E Client " + UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String redirectUri = baseUrl() + "/test-rp/callback";
        String state = UUID.randomUUID().toString();

        // 1. Tenant admin navigates to the new-client form.
        new LoginPageObject(page, baseUrl()).loginAsTenantAdmin(tenant);
        new ManageHomePage(page, baseUrl(), tenant.slug())
            .clickApplications()
            .clickClientsForApplication(app.name())
            .clickNewClient()
            .assertOnForm();

        // 2. DOM-level assertions that lock in the visible parts of PR #277 (consent
        //    is a per-client toggle the user controls, defaulting unchecked) and PR
        //    #278 (scopes input has a helper paragraph, not a misleading placeholder
        //    that users mistake for a pre-populated value).
        Locator consentToggle = page.getByLabel("Require user consent on authorize");
        PlaywrightAssertions.assertThat(consentToggle).isVisible();
        PlaywrightAssertions.assertThat(consentToggle).not().isChecked();

        Locator scopesInput = page.locator("input[name=scopes]");
        PlaywrightAssertions.assertThat(
            page.locator("small").filter(new Locator.FilterOptions()
                .setHasText("Whitespace- or comma-separated"))
        ).isVisible();
        // The misleading placeholder PR #278 removed must stay absent. Assert no
        // placeholder attribute at all — if a future change reintroduces example
        // text as a placeholder, this fails fast at the form, not later at SAS.
        assertThat(scopesInput.getAttribute("placeholder")).isNull();

        // 3. Fill the form: name, authorization_code grant, redirect URI, scopes
        //    typed whitespace-separated (the form-input shape that PR #278 made
        //    the parser handle). Confidential is checked by default in the template.
        page.getByLabel("Name").fill(clientName);
        page.getByLabel("Authorization code").check();
        // getByLabel substring-matches; "Redirect URIs" is a substring of
        // "Post-logout redirect URIs", so target the textarea by name attribute.
        page.locator("textarea[name=redirectUris]").fill(redirectUri);
        scopesInput.fill("openid profile email");
        // Reading back the typed value catches the deferred "scopes field is not
        // editable" claim from PR #278 — fill silently no-ops on a readonly input,
        // so the assertion is what would notice.
        assertThat(scopesInput.inputValue()).isEqualTo("openid profile email");

        // 4. Submit and capture the flashed credentials. PR #275 made `clientId`
        //    the wire id (RegisteredClient.getClientId()) not the internal PK;
        //    feeding the captured value back into /oauth2/authorize is what locks
        //    that fix in.
        page.getByTestId("clients-create-submit").click();
        page.waitForURL(baseUrl() + "/manage/t/" + tenant.slug() + "/applications/*/clients");

        String displayedClientId = page.locator("[data-test-flash=client-id]").textContent();
        String displayedClientSecret = page.locator("[data-test-flash=client-secret]").textContent();
        assertThat(displayedClientId).isNotBlank();
        assertThat(displayedClientSecret).isNotBlank();

        // 5. Grant the end user app + client membership. The manage-UI create path
        //    does not auto-grant memberships to anyone other than the actor admin,
        //    so without this the end-user trips the MembershipGateFilter at
        //    /oauth2/authorize after login. Fixture-via-repo (per spring-boot-tests
        //    rule); the production code path is exercised separately in
        //    /memberships tests.
        tenants.grantEndUserAccessToClient(tenant, app, displayedClientId);

        // 6. Drop the admin session before driving the end-user flow. Same browser
        //    context (cheap), fresh cookie jar (so /oauth2/authorize behaves as for
        //    an anonymous visitor and Spring Security saves the request + redirects
        //    to /t/{slug}/login).
        page.context().clearCookies();

        // 7. Anonymous authorize → bounced to /t/{slug}/login. Confidential client
        //    so no PKCE parameters. Request multi-scope (`openid profile email`,
        //    matching the form input) so the SAS consent code path is exercised
        //    — SAS short-circuits consent when the only requested scope is the
        //    `openid` OIDC marker, which would mask any future regression that
        //    flips the per-client consent default back on.
        String authorizeUrl = UriComponentsBuilder
            .fromUriString(baseUrl() + "/t/" + tenant.slug() + "/oauth2/authorize")
            .queryParam("response_type", "code")
            .queryParam("client_id", displayedClientId)
            .queryParam("redirect_uri", redirectUri)
            .queryParam("scope", "openid profile email")
            .queryParam("state", state)
            .build().toUriString();
        page.navigate(authorizeUrl);

        // 8. End user signs in. Spring Security's saved-request resume kicks in
        //    after the form post. PR #279 ensures the resume URL is stripped of
        //    Spring Security's `&continue` marker before SAS sees it; without
        //    that fix, this hop returns `?error=access_denied` to the redirect URI
        //    instead of `?code=…`.
        page.waitForURL("**/t/" + tenant.slug() + "/login");
        page.getByLabel("Email").fill(tenant.endUserEmail());
        page.getByLabel("Password").fill(tenant.endUserPassword());
        page.getByTestId("login-submit").click();

        // 9. Wait for the browser to land at the RP callback. The test RP captures
        //    the code into its (state → code) map.
        page.waitForURL(redirectUri + "*");
        String code = rp.awaitCallback(state, Duration.ofSeconds(5));

        // 10. Exchange the code for an access token. HTTP Basic over the confidential
        //     client's secret. Bug-shape #275 (wrong flashed id) would surface here
        //     as `invalid_client`; bug-shape #277 (consent default on) would surface
        //     at step 9 by not reaching the RP at all.
        RestClient rest = RestClient.create();
        Map<String, Object> tokenResponse = rest.post()
            .uri(baseUrl() + "/t/" + tenant.slug() + "/oauth2/token")
            .header("Authorization", basicAuth(displayedClientId, displayedClientSecret))
            .contentType(MediaType.APPLICATION_FORM_URLENCODED)
            .body(tokenExchangeBody(code, redirectUri))
            .retrieve()
            .body(new ParameterizedTypeReference<Map<String, Object>>() {});
        assertThat(tokenResponse).containsKey("access_token");
        String accessToken = (String) tokenResponse.get("access_token");
        assertThat(accessToken).isNotBlank();

        // 11. The access token JWT must carry sub = the end user's identifier
        //     (Limen's default — TenantUserDetails.getUsername() returns email).
        //     Asserting on the JWT pins SAS's claim derivation independent of
        //     userinfo's response shape.
        assertThat(subClaim(accessToken)).isEqualTo(tenant.endUserEmail());

        // 12. The access token must be accepted by /userinfo. Proves the full
        //     JWT-validation + tenant-routing + resource-server chain, end to end.
        rest.get()
            .uri(baseUrl() + "/t/" + tenant.slug() + "/userinfo")
            .header("Authorization", "Bearer " + accessToken)
            .retrieve()
            .toBodilessEntity();
    }

    private static String basicAuth(String clientId, String clientSecret) {
        String credentials = clientId + ":" + clientSecret;
        return "Basic " + Base64.getEncoder().encodeToString(
            credentials.getBytes(StandardCharsets.UTF_8));
    }

    private static MultiValueMap<String, String> tokenExchangeBody(String code, String redirectUri) {
        MultiValueMap<String, String> body = new LinkedMultiValueMap<>();
        body.add("grant_type", "authorization_code");
        body.add("code", code);
        body.add("redirect_uri", redirectUri);
        return body;
    }

    private static String subClaim(String accessToken) {
        try {
            return SignedJWT.parse(accessToken).getJWTClaimsSet().getSubject();
        } catch (ParseException e) {
            throw new IllegalStateException("access_token is not a parseable JWT", e);
        }
    }
}
