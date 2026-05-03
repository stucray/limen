package com.stucray.limen.ui.journey;

import com.microsoft.playwright.Page;
import com.stucray.limen.ui.pages.manage.ManageHomePage;
import com.stucray.limen.ui.support.BaseUiIT;
import com.stucray.limen.ui.support.LoginPageObject;
import com.stucray.limen.ui.support.TestTenantFactory.SeededApplication;
import com.stucray.limen.ui.support.TestTenantFactory.SeededTenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

@DisplayName("A tenant administrator can create a role under an application via the manage console")
class ManageRolesCreateJourneyUiIT extends BaseUiIT {

    @Test
    @DisplayName("happy path: from the manage home, navigates to Applications, opens the seeded app's Roles list, fills the new-role form, submits, and the new role appears in the list")
    void happyPath(Page page) {
        SeededTenant tenant = tenants.createTenant();
        SeededApplication app = tenants.seedApplication(tenant);
        String roleName = "role-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        new LoginPageObject(page, baseUrl()).loginAsTenantAdmin(tenant);

        new ManageHomePage(page, baseUrl(), tenant.slug())
            .clickApplications()
            .assertOnList()
            .assertApplicationVisible(app.name())
            .clickRolesForApplication(app.name())
            .assertOnList()
            .clickNewRole()
            .assertOnForm()
            .fillName(roleName)
            .fillDescription("Created from a Playwright test.")
            .submit()
            .assertOnList()
            .assertRoleVisible(roleName);
    }
}
