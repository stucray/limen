package com.stucray.limen.ui.journey;

import com.microsoft.playwright.Page;
import com.stucray.limen.ui.pages.enduser.EndUserHomePage;
import com.stucray.limen.ui.support.BaseUiIT;
import com.stucray.limen.ui.support.LoginPageObject;
import com.stucray.limen.ui.support.TestTenantFactory.SeededTenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("An end user can sign in to their tenant via /t/{slug}/login")
class EndUserLoginJourneyUiIT extends BaseUiIT {

    @Test
    @DisplayName("happy path: signs in at /t/{slug}/login and lands on the end-user home with tenant identity and a 'Signed in as ...' line")
    void happyPath(Page page) {
        SeededTenant tenant = tenants.createTenant();

        new LoginPageObject(page, baseUrl()).loginAsEndUser(tenant);

        new EndUserHomePage(page, baseUrl(), tenant.slug())
            .assertOnHomeForTenant(tenant.displayName())
            .assertSignedInAs(tenant.endUserEmail());
    }
}
