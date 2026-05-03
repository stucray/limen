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

@DisplayName("A tenant administrator can create an OAuth2 client under an application via the manage console")
class ManageClientsCreateJourneyUiIT extends BaseUiIT {

    @Test
    @DisplayName("happy path: from the manage home, navigates to Applications, opens the seeded app's Clients list, fills the new-client form, submits, and the new client appears in the list")
    void happyPath(Page page) {
        SeededTenant tenant = tenants.createTenant();
        SeededApplication app = tenants.seedApplication(tenant);
        String clientName = "Client " + UUID.randomUUID().toString().replace("-", "").substring(0, 8);

        new LoginPageObject(page, baseUrl()).loginAsTenantAdmin(tenant);

        new ManageHomePage(page, baseUrl(), tenant.slug())
            .clickApplications()
            .assertOnList()
            .assertApplicationVisible(app.name())
            .clickClientsForApplication(app.name())
            .assertOnList()
            .clickNewClient()
            .assertOnForm()
            .fillName(clientName)
            .checkClientCredentialsGrant()
            .submit()
            .assertOnList()
            .assertClientVisible(clientName);
    }
}
