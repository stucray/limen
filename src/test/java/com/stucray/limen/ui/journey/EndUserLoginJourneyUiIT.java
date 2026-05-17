package com.stucray.limen.ui.journey;

import com.microsoft.playwright.Page;
import com.stucray.limen.ui.pages.manage.ManageHomePage;
import com.stucray.limen.ui.support.BaseUiIT;
import com.stucray.limen.ui.support.LoginPageObject;
import com.stucray.limen.ui.support.TestTenantFactory.SeededTenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

@Tag("cross-browser")
@DisplayName("A tenant owner signing in at /t/{slug}/login is redirected to the management home (issue #283)")
class EndUserLoginJourneyUiIT extends BaseUiIT {

    @Test
    @DisplayName("happy path: signs in at /t/{slug}/login and lands on /manage/t/{slug}/ — Limen's current role model issues TENANT_OWNER to every authed principal, so the OAuth2 surface's terminal /t/{slug}/ home redirects through")
    void happyPath(Page page) {
        SeededTenant tenant = tenants.createTenant();

        new LoginPageObject(page, baseUrl()).loginAsEndUser(tenant);

        new ManageHomePage(page, baseUrl(), tenant.slug())
            .assertOnHomeForTenant(tenant.displayName())
            .assertWelcomeForUser(tenant.endUserEmail())
            .assertTenantAdminNavTilesVisible();
    }
}
