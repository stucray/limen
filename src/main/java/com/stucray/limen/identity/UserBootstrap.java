package com.stucray.limen.identity;

import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;

@Component
public class UserBootstrap implements CommandLineRunner {

    private final String adminUsername;
    private final String adminPassword;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserBootstrap(
        @Value("${OVERROUND_ADMIN_USERNAME:#{null}}") String adminUsername,
        @Value("${OVERROUND_ADMIN_PASSWORD:#{null}}") String adminPassword,
        UserRepository userRepository,
        PasswordEncoder passwordEncoder
    ) {
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(String... args) {
        if (adminUsername == null || adminPassword == null) return;

        String hash = passwordEncoder.encode(adminPassword);
        userRepository.findByUsername(adminUsername).ifPresentOrElse(
            existing -> userRepository.save(existing.withPasswordHash(hash)),
            () -> userRepository.save(new User(null, adminUsername, hash, true, LocalDateTime.now()))
        );
    }
}
