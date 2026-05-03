package com.stucray.limen.ui.journey;

import com.microsoft.playwright.Page;
import com.stucray.limen.ui.pages.manage.system.SystemTenantsListPage;
import com.stucray.limen.ui.support.BaseUiIT;
import com.stucray.limen.ui.support.LoginPageObject;
import com.stucray.limen.ui.support.TestTenantFactory.SeededSystemAdmin;
import com.stucray.limen.ui.support.TestTenantFactory.SeededTenant;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("A system administrator can sign in and view the tenants list")
class SystemAdminTenantsJourneyUiIT extends BaseUiIT {

    @Test
    @DisplayName("happy path: sysadmin signs in at /manage/t/system/login and the seeded tenant slug appears in the tenants list")
    void happyPath(Page page) {
        SeededTenant seeded = tenants.createTenant();
        SeededSystemAdmin admin = tenants.createSystemAdmin();

        new LoginPageObject(page, baseUrl())
            .loginAsSystemAdmin(admin.email(), admin.password());

        new SystemTenantsListPage(page, baseUrl())
            .visit()
            .assertOnTenantsList()
            .assertTenantSlugVisible(seeded.slug());
    }
}
