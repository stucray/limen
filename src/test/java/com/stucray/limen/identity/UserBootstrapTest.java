package com.stucray.limen.identity;

import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.password.PasswordEncoder;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class UserBootstrapTest {

    @Mock UserRepository userRepository;
    @Mock PasswordEncoder passwordEncoder;

    @Test
    void doesNothingWhenUsernameUnset() throws Exception {
        new UserBootstrap(null, "pass", userRepository, passwordEncoder).run();
        verifyNoInteractions(userRepository, passwordEncoder);
    }

    @Test
    void doesNothingWhenPasswordUnset() throws Exception {
        new UserBootstrap("admin", null, userRepository, passwordEncoder).run();
        verifyNoInteractions(userRepository, passwordEncoder);
    }

    @Test
    void createsUserWhenAbsent() throws Exception {
        given(userRepository.findByUsername("admin")).willReturn(Optional.empty());
        given(passwordEncoder.encode("pass")).willReturn("hashed");

        new UserBootstrap("admin", "pass", userRepository, passwordEncoder).run();

        verify(userRepository).save(argThat(u ->
            u.username().equals("admin") && u.passwordHash().equals("hashed") && u.enabled() && u.id() == null
        ));
    }

    @Test
    void updatesPasswordHashWhenUserExists() throws Exception {
        var existing = new User(1L, "admin", "oldhash", true, LocalDateTime.now());
        given(userRepository.findByUsername("admin")).willReturn(Optional.of(existing));
        given(passwordEncoder.encode("newpass")).willReturn("newhash");

        new UserBootstrap("admin", "newpass", userRepository, passwordEncoder).run();

        verify(userRepository).save(argThat(u -> u.id().equals(1L) && u.passwordHash().equals("newhash")));
    }

    @Test
    void isIdempotentAcrossRestarts() throws Exception {
        given(passwordEncoder.encode("pass")).willReturn("hashed");
        given(userRepository.findByUsername("admin")).willReturn(Optional.empty());

        var bootstrap = new UserBootstrap("admin", "pass", userRepository, passwordEncoder);
        bootstrap.run();

        var created = new User(1L, "admin", "hashed", true, LocalDateTime.now());
        given(userRepository.findByUsername("admin")).willReturn(Optional.of(created));

        bootstrap.run();

        verify(userRepository, times(2)).save(any());
    }
}
