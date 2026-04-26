package com.stucray.limen.identity;

import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantStatus;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class AuthUserDetailsServiceTest {

    @Mock TenantRepository tenantRepository;
    @Mock UserRepository userRepository;

    AuthUserDetailsService service;

    private static final Tenant SYSTEM_TENANT = new Tenant(1L, "system", "System", TenantStatus.ACTIVE, LocalDateTime.now());

    @BeforeEach
    void setUp() {
        service = new AuthUserDetailsService(tenantRepository, userRepository);
        given(tenantRepository.findBySlug("system")).willReturn(Optional.of(SYSTEM_TENANT));
    }

    @Test
    void loadsUserByUsernameFromSystemTenant() {
        given(userRepository.findByUsernameAndTenantId("alice", 1L)).willReturn(
            Optional.of(new User(1L, 1L, "alice", "hash", true, false, false, LocalDateTime.now()))
        );

        UserDetails details = service.loadUserByUsername("alice");

        assertThat(details.getUsername()).isEqualTo("alice");
        assertThat(details.getPassword()).isEqualTo("hash");
        assertThat(details.isEnabled()).isTrue();
    }

    @Test
    void throwsUsernameNotFoundForUnknownUser() {
        given(userRepository.findByUsernameAndTenantId("unknown", 1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("unknown"))
            .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void throwsUsernameNotFoundWhenSystemTenantMissing() {
        given(tenantRepository.findBySlug("system")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("alice"))
            .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void assignsRoleUserAuthority() {
        given(userRepository.findByUsernameAndTenantId("alice", 1L)).willReturn(
            Optional.of(new User(1L, 1L, "alice", "hash", true, false, false, LocalDateTime.now()))
        );

        UserDetails details = service.loadUserByUsername("alice");

        assertThat(details.getAuthorities())
            .extracting("authority")
            .containsExactly("ROLE_USER");
    }

    @Test
    void disabledUserIsNotEnabled() {
        given(userRepository.findByUsernameAndTenantId("bob", 1L)).willReturn(
            Optional.of(new User(2L, 1L, "bob", "hash", false, false, false, LocalDateTime.now()))
        );

        UserDetails details = service.loadUserByUsername("bob");

        assertThat(details.isEnabled()).isFalse();
    }
}
