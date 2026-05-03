package com.stucray.limen.ui.journey;

import com.microsoft.playwright.Page;
import com.stucray.limen.ui.pages.manage.system.SystemTenantCreatePage;
import com.stucray.limen.ui.pages.manage.system.SystemTenantsListPage;
import com.stucray.limen.ui.support.BaseUiIT;
import com.stucray.limen.ui.support.LoginPageObject;
import com.stucray.limen.ui.support.TestTenantFactory.SeededSystemAdmin;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.UUID;

@DisplayName("A system administrator can provision a new tenant via the manage console")
class SystemAdminTenantCreateJourneyUiIT extends BaseUiIT {

    @Test
    @DisplayName("happy path: sysadmin signs in, opens New tenant, fills form, lands back on the tenants list with the new slug visible")
    void happyPath(Page page) {
        SeededSystemAdmin admin = tenants.createSystemAdmin();
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        String slug = "newco-" + suffix;

        new LoginPageObject(page, baseUrl())
            .loginAsSystemAdmin(admin.email(), admin.password());

        new SystemTenantsListPage(page, baseUrl())
            .visit()
            .assertOnTenantsList();

        new SystemTenantCreatePage(page, baseUrl())
            .visitFromTenantsList()
            .assertOnForm()
            .fillForm(slug, "Newco " + suffix, "owner-" + suffix + "@example.test")
            .submit()
            .assertOnTenantsList()
            .assertTenantSlugVisible(slug);
    }
}
