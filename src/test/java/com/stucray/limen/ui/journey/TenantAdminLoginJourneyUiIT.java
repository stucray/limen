package com.stucray.limen.ui.journey;

import com.microsoft.playwright.Page;
import com.stucray.limen.ui.pages.manage.ManageHomePage;
import com.stucray.limen.ui.support.BaseUiIT;
import com.stucray.limen.ui.support.LoginPageObject;
import com.stucray.limen.ui.support.TestTenantFactory.SeededTenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("A tenant administrator can sign in to the management console")
class TenantAdminLoginJourneyUiIT extends BaseUiIT {

    @Test
    @DisplayName("happy path: signs in at /manage/t/{slug}/login and lands on the manage console home with tenant identity, welcome line, and admin nav tiles")
    void happyPath(Page page) {
        SeededTenant tenant = tenants.createTenant();

        new LoginPageObject(page, baseUrl()).loginAsTenantAdmin(tenant);

        new ManageHomePage(page, baseUrl(), tenant.slug())
            .assertOnHomeForTenant(tenant.displayName())
            .assertWelcomeForUser(tenant.adminEmail())
            .assertTenantAdminNavTilesVisible();
    }
}
