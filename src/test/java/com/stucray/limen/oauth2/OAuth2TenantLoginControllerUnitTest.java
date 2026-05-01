package com.stucray.limen.oauth2;

import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantStatus;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.ui.ConcurrentModel;
import org.springframework.ui.Model;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * Branch-coverage unit tests for {@link OAuth2TenantLoginController}. Covers
 * the unknown-slug redirect (previously uncovered) and the happy path.
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("OAuth2TenantLoginController: branch coverage for the unknown-slug redirect and the happy path")
class OAuth2TenantLoginControllerUnitTest {

    @Mock TenantRepository tenantRepository;

    @Test
    @DisplayName("Unknown slug redirects to /manage/t/system/login and adds no tenant attributes to the model")
    void unknownSlugRedirectsToSystemLogin() {
        given(tenantRepository.findBySlug("does-not-exist")).willReturn(Optional.empty());
        OAuth2TenantLoginController controller = new OAuth2TenantLoginController(tenantRepository);
        Model model = new ConcurrentModel();

        String view = controller.loginForm("does-not-exist", model);

        assertThat(view).isEqualTo("redirect:/manage/t/system/login");
        assertThat(model.containsAttribute("tenantSlug")).isFalse();
        assertThat(model.containsAttribute("tenantName")).isFalse();
    }

    @Test
    @DisplayName("Known slug renders the login view with tenantSlug and tenantName populated for the template")
    void knownSlugRendersLoginViewWithTenantAttributes() {
        Tenant alpha = new Tenant(1L, "alpha", "Alpha Corp", TenantStatus.ACTIVE, LocalDateTime.now());
        given(tenantRepository.findBySlug("alpha")).willReturn(Optional.of(alpha));
        OAuth2TenantLoginController controller = new OAuth2TenantLoginController(tenantRepository);
        Model model = new ConcurrentModel();

        String view = controller.loginForm("alpha", model);

        assertThat(view).isEqualTo("login");
        assertThat(model.getAttribute("tenantSlug")).isEqualTo("alpha");
        assertThat(model.getAttribute("tenantName")).isEqualTo("Alpha Corp");
    }
}
