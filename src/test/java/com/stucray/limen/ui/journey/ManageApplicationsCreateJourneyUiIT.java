package com.stucray.limen.ui.journey;

import com.microsoft.playwright.Page;
import com.stucray.limen.ui.pages.manage.ManageHomePage;
import com.stucray.limen.ui.support.BaseUiIT;
import com.stucray.limen.ui.support.LoginPageObject;
import com.stucray.limen.ui.support.TestTenantFactory.SeededTenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

@DisplayName("A tenant administrator can create an application via the manage console")
class ManageApplicationsCreateJourneyUiIT extends BaseUiIT {

    @Test
    @DisplayName("happy path: from the manage home, navigates to Applications, fills the new-application form, submits, and the new app appears in the list")
    void happyPath(Page page) {
        SeededTenant tenant = tenants.createTenant();
        String appName = "App " + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        new LoginPageObject(page, baseUrl()).loginAsTenantAdmin(tenant);

        new ManageHomePage(page, baseUrl(), tenant.slug())
            .clickApplications()
            .assertOnList()
            .clickNewApplication()
            .assertOnForm()
            .fillName(appName)
            .fillDescription("Created from a Playwright test.")
            .submit()
            .assertOnList()
            .assertApplicationVisible(appName);
    }
}
