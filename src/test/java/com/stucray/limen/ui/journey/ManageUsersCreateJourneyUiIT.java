package com.stucray.limen.ui.journey;

import com.microsoft.playwright.Page;
import com.stucray.limen.ui.pages.manage.ManageHomePage;
import com.stucray.limen.ui.support.BaseUiIT;
import com.stucray.limen.ui.support.LoginPageObject;
import com.stucray.limen.ui.support.TestTenantFactory.SeededTenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

@DisplayName("A tenant administrator can create a user via the manage console")
class ManageUsersCreateJourneyUiIT extends BaseUiIT {

    @Test
    @DisplayName("happy path: from the manage home, navigates to Users, fills the add-user form, submits, and the new user appears in the list")
    void happyPath(Page page) {
        SeededTenant tenant = tenants.createTenant();
        String email = "newuser-" + UUID.randomUUID().toString().replace("-", "").substring(0, 8) + "@example.test";

        new LoginPageObject(page, baseUrl()).loginAsTenantAdmin(tenant);

        new ManageHomePage(page, baseUrl(), tenant.slug())
            .clickUsers()
            .assertOnList()
            .clickAddUser()
            .assertOnForm()
            .fillEmail(email)
            .fillTemporaryPassword("temp-secret-123")
            .submit()
            .assertOnList()
            .assertUserVisible(email);
    }
}
