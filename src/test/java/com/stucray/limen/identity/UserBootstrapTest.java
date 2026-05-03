package com.stucray.limen.identity;

import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantProvisioningService;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantStatus;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
@DisplayName("Startup user-bootstrap runner")
class UserBootstrapTest {

    @Mock TenantRepository tenantRepository;
    @Mock TenantProvisioningService tenantProvisioningService;
    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;

    private static final Tenant SYSTEM_TENANT = new Tenant(1L, "system", "System", TenantStatus.ACTIVE, LocalDateTime.now());

    @BeforeEach
    void stubSystemTenant() {
        given(tenantRepository.findBySlug("system")).willReturn(Optional.of(SYSTEM_TENANT));
    }

    private static final BootstrapAdminProperties NO_ADMIN = new BootstrapAdminProperties(null, null);
    private static final BootstrapAdminProperties ADMIN = new BootstrapAdminProperties("admin@example.test", "pass");

    @Test
    @DisplayName("Always looks up the system tenant on startup, even with no admin credentials configured")
    void alwaysBootstrapsSystemTenant() throws Exception {
        new UserBootstrap(NO_ADMIN, tenantRepository, tenantProvisioningService, userRepository, passwordEncoder).run();
        verify(tenantRepository).findBySlug("system");
        verifyNoInteractions(tenantProvisioningService, userRepository, passwordEncoder);
    }

    @Test
    @DisplayName("Provisions the system tenant when it does not yet exist")
    void createsSystemTenantWhenAbsent() throws Exception {
        given(tenantRepository.findBySlug("system")).willReturn(Optional.empty());
        given(tenantProvisioningService.createTenant("system", "System")).willReturn(SYSTEM_TENANT);

        new UserBootstrap(NO_ADMIN, tenantRepository, tenantProvisioningService, userRepository, passwordEncoder).run();

        verify(tenantProvisioningService).createTenant("system", "System");
    }

    @Test
    @DisplayName("Skips user creation entirely when no bootstrap admin credentials are configured")
    void doesNothingWithUsersWhenCredentialsUnset() throws Exception {
        new UserBootstrap(NO_ADMIN, tenantRepository, tenantProvisioningService, userRepository, passwordEncoder).run();
        verifyNoInteractions(userRepository, passwordEncoder);
    }

    @Test
    @DisplayName("Creates the configured admin user in the system tenant with an encoded password")
    void createsAdminUserInSystemTenantWhenAbsent() throws Exception {
        given(userRepository.findByEmailAndTenantId("admin@example.test", 1L)).willReturn(Optional.empty());
        given(passwordEncoder.encode("pass")).willReturn("hashed");

        new UserBootstrap(ADMIN, tenantRepository, tenantProvisioningService, userRepository, passwordEncoder).run();

        verify(userRepository).save(argThat(u ->
            u.email().equals("admin@example.test") && u.passwordHash().equals("hashed")
            && u.tenantId().equals(1L) && u.enabled() && !u.mustChangePassword()
        ));
    }

    @Test
    @DisplayName("Re-hashes and saves the admin password when the admin user already exists")
    void updatesPasswordHashWhenAdminUserExists() throws Exception {
        var existing = new User(10L, 1L, "admin@example.test", "oldhash", true, false, false, true, LocalDateTime.now());
        given(userRepository.findByEmailAndTenantId("admin@example.test", 1L)).willReturn(Optional.of(existing));
        given(passwordEncoder.encode("newpass")).willReturn("newhash");

        new UserBootstrap(new BootstrapAdminProperties("admin@example.test", "newpass"),
            tenantRepository, tenantProvisioningService, userRepository, passwordEncoder).run();

        verify(userRepository).save(argThat(u -> u.id().equals(10L) && u.passwordHash().equals("newhash")));
    }
}
