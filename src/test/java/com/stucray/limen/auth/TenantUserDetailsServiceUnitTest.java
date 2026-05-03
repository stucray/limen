package com.stucray.limen.auth;

import com.stucray.limen.tenant.Tenant;
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
import org.springframework.security.core.userdetails.UserDetails;
import org.springframework.security.core.userdetails.UsernameNotFoundException;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
@DisplayName("TenantUserDetailsService")
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
        alice = new User(10L, 1L, "alice@example.test", "hash", true, false, false, LocalDateTime.now());
    }

    @Test
    @DisplayName("Returns TenantUserDetails carrying both email and tenant slug for a known user")
    void loadByEmailAndSlugReturnsTenantUserDetailsForKnownUser() {
        given(tenantRepository.findBySlug("alpha")).willReturn(Optional.of(alpha));
        given(userRepository.findByEmailAndTenantId("alice@example.test", 1L)).willReturn(Optional.of(alice));

        UserDetails details = service.loadByEmailAndSlug("alice@example.test", "alpha");

        assertThat(details).isInstanceOf(TenantUserDetails.class);
        TenantUserDetails tenantDetails = (TenantUserDetails) details;
        // Spring's UserDetails.getUsername() returns the email value in this codebase
        assertThat(tenantDetails.getUsername()).isEqualTo("alice@example.test");
        assertThat(tenantDetails.tenantSlug()).isEqualTo("alpha");
        assertThat(tenantDetails.userId()).isEqualTo(10L);
    }

    @Test
    @DisplayName("Throws UsernameNotFoundException when the slug does not resolve to a tenant")
    void loadByEmailAndSlugThrowsWhenTenantSlugUnknown() {
        given(tenantRepository.findBySlug("ghost")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadByEmailAndSlug("alice@example.test", "ghost"))
            .isInstanceOf(UsernameNotFoundException.class)
            .hasMessageContaining("Unknown tenant: ghost");
    }

    @Test
    @DisplayName("Throws UsernameNotFoundException when the user is unknown within the resolved tenant")
    void loadByEmailAndSlugThrowsWhenUserUnknownInTenant() {
        given(tenantRepository.findBySlug("alpha")).willReturn(Optional.of(alpha));
        given(userRepository.findByEmailAndTenantId("nobody@example.test", 1L)).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadByEmailAndSlug("nobody@example.test", "alpha"))
            .isInstanceOf(UsernameNotFoundException.class)
            .hasMessage("nobody@example.test");
    }

    @Test
    @DisplayName("loadUserByUsername (the slugless API) always throws — slug is required")
    void loadUserByUsernameAlwaysThrowsBecauseSlugIsRequired() {
        assertThatThrownBy(() -> service.loadUserByUsername("alice@example.test"))
            .isInstanceOf(UnsupportedOperationException.class)
            .hasMessageContaining("loadByEmailAndSlug");
    }
}
