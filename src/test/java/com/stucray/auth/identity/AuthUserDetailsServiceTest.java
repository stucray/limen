package com.stucray.auth.identity;

import com.stucray.auth.user.User;
import com.stucray.auth.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
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

    @Mock UserRepository userRepository;
    @InjectMocks AuthUserDetailsService service;

    @Test
    void loadsUserByUsername() {
        given(userRepository.findByUsername("alice")).willReturn(
            Optional.of(new User(1L, "alice", "hash", true, LocalDateTime.now()))
        );

        UserDetails details = service.loadUserByUsername("alice");

        assertThat(details.getUsername()).isEqualTo("alice");
        assertThat(details.getPassword()).isEqualTo("hash");
        assertThat(details.isEnabled()).isTrue();
    }

    @Test
    void throwsUsernameNotFoundForUnknownUser() {
        given(userRepository.findByUsername("unknown")).willReturn(Optional.empty());

        assertThatThrownBy(() -> service.loadUserByUsername("unknown"))
            .isInstanceOf(UsernameNotFoundException.class);
    }

    @Test
    void assignsRoleUserAuthority() {
        given(userRepository.findByUsername("alice")).willReturn(
            Optional.of(new User(1L, "alice", "hash", true, LocalDateTime.now()))
        );

        UserDetails details = service.loadUserByUsername("alice");

        assertThat(details.getAuthorities())
            .extracting("authority")
            .containsExactly("ROLE_USER");
    }

    @Test
    void disabledUserIsNotEnabled() {
        given(userRepository.findByUsername("bob")).willReturn(
            Optional.of(new User(2L, "bob", "hash", false, LocalDateTime.now()))
        );

        UserDetails details = service.loadUserByUsername("bob");

        assertThat(details.isEnabled()).isFalse();
    }
}
