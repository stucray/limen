package com.stucray.limen.auth;

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
class TenantUserDetailsServiceUnitTest {

    @Mock TenantRepository tenantRepository;
    @Mock UserRepository userRepository;

    TenantUserDetailsService service;

    Tenant alpha;
    User alice;

    @BeforeEach
    void setUp() {
        service = new TenantUserDetailsService(tenantRepository, userRepository);
        alpha = new Tenant(1L, "alpha", "Alpha", TenantStatus.ACTIVE, LocalDateTime.now());
        alice = new User(10L, 1L, "alice", "hash", true, false, false, LocalDateTime.now());
    }

    @Test
    void loadByUsernameAndSlugReturnsTenantUserDetailsForKnownUser() {
        given(tenantRepository.findBySlug("alpha")).willReturn(Optional.of(alpha));
        given(userRepository.findByUsernameAndTenantId("alice", 1L)).willReturn(Optional.of(alice));

        UserDetails details = service.loadByUsernameAndSlug("alice", "alpha");

        assertThat(details).isInstanceOf(TenantUserDetails.class);
        TenantUserDetails tenantDetails = (TenantUserDetails) details;
        assertThat(tenantDetails.getUsername()).isEqualTo("alice");
        assertThat(tenantDetails.tenantSlug()).isEqualTo("alpha");
        assertThat(tenantDetails.userId()).isEqualTo(10L);
    }

    @Test
    void loadByUsernameAndSlugThrowsWhenTenantSlugUnknown() {
        given(tenantRepository.findBySlug("ghost")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadByUsernameAndSlug("alice", "ghost"))
            .isInstanceOf(UsernameNotFoundException.class)
            .hasMessageContaining("Unknown tenant: ghost");
    }

    @Test
    void loadByUsernameAndSlugThrowsWhenUserUnknownInTenant() {
        given(tenantRepository.findBySlug("alpha")).willReturn(Optional.of(alpha));
        given(userRepository.findByUsernameAndTenantId("nobody", 1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadByUsernameAndSlug("nobody", "alpha"))
            .isInstanceOf(UsernameNotFoundException.class)
            .hasMessage("nobody");
    }

    @Test
    void loadUserByUsernameAlwaysThrowsBecauseSlugIsRequired() {
        assertThatThrownBy(() -> service.loadUserByUsername("alice"))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("loadByUsernameAndSlug");
    }
}
