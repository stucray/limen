package com.stucray.limen.ui.journey;

import com.microsoft.playwright.Page;
import com.microsoft.playwright.Request;
import com.microsoft.playwright.Route;
import com.stucray.limen.ui.support.BaseUiIT;
import com.stucray.limen.ui.support.OAuth2AuthorizeFlow;
import com.stucray.limen.ui.support.OAuth2AuthorizeFlow.Pkce;
import com.stucray.limen.ui.support.TestTenantFactory.SeededApplication;
import com.stucray.limen.ui.support.TestTenantFactory.SeededOAuth2Client;
import com.stucray.limen.ui.support.TestTenantFactory.SeededTenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

@Tag("cross-browser")
@DisplayName("An end user can complete an OAuth2 authorization-code-with-PKCE flow and land back at the relying-party callback with a code")
class OAuth2AuthorizeJourneyUiIT extends BaseUiIT {

    private static final String REDIRECT_URI = "http://localhost/callback";
    private static final String STATE = "ui-test-state";

    @Test
    @DisplayName("happy path: authorize → bounced to /t/{slug}/login → end-user signs in → saved request resumes → 302 to RP callback URL with ?code=… present")
    void happyPath(Page page) {
        SeededTenant tenant = tenants.createTenant();
        SeededApplication app = tenants.seedApplication(tenant);
        SeededOAuth2Client client = tenants.seedOAuth2ClientForEndUser(tenant, app, REDIRECT_URI);
        Pkce pkce = OAuth2AuthorizeFlow.newPkce();
        String authorizeUrl = OAuth2AuthorizeFlow.authorizeUrl(
            baseUrl(), tenant.slug(), client.clientId(), client.redirectUri(),
            pkce.challenge(), STATE);

        // The redirect URI host:port (port 80, no listener) wouldn't actually
        // resolve, so intercept client-side and abort — we only care that the
        // browser was sent there with the right query string. Pattern must be
        // host-anchored, NOT `**/callback*`, because the authorize URL itself
        // contains `/callback` inside the redirect_uri query parameter and a
        // trailing-glob match would intercept the wrong request.
        page.route(REDIRECT_URI + "*", Route::abort);

        page.navigate(authorizeUrl);
        page.waitForURL("**/t/" + tenant.slug() + "/login*"); // login URL now carries ?ref= (issue #327)

        page.getByLabel("Email").fill(tenant.endUserEmail());
        page.getByLabel("Password").fill(tenant.endUserPassword());
        // The terminal hop is a 302 to http://localhost/callback?code=…&state=… —
        // capture it via waitForRequest so the assertion doesn't depend on the
        // (intentionally aborted) navigation completing a load event.
        Request callback = page.waitForRequest(REDIRECT_URI + "*", () ->
            page.getByTestId("login-submit").click());

        assertThat(callback.url()).contains("code=");
        assertThat(callback.url()).contains("state=" + STATE);
    }
}
