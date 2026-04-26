package com.stucray.limen.identity;

import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantStatus;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
public class UserBootstrap implements CommandLineRunner {

    static final String SYSTEM_SLUG = "system";
    static final String SYSTEM_DISPLAY_NAME = "System";

    private final String adminUsername;
    private final String adminPassword;
    private final TenantRepository tenantRepository;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserBootstrap(
        @Value("${LIMEN_ADMIN_USERNAME:#{null}}") String adminUsername,
        @Value("${LIMEN_ADMIN_PASSWORD:#{null}}") String adminPassword,
        TenantRepository tenantRepository,
        UserRepository userRepository,
        PasswordEncoder passwordEncoder
    ) {
        this.adminUsername = adminUsername;
        this.adminPassword = adminPassword;
        this.tenantRepository = tenantRepository;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) {
        Tenant systemTenant = tenantRepository.findBySlug(SYSTEM_SLUG)
            .orElseGet(() -> tenantRepository.save(
                new Tenant(null, SYSTEM_SLUG, SYSTEM_DISPLAY_NAME, TenantStatus.ACTIVE, LocalDateTime.now())
            ));

        if (adminUsername == null || adminPassword == null) return;

        String hash = passwordEncoder.encode(adminPassword);
        userRepository.findByUsernameAndTenantId(adminUsername, systemTenant.id())
            .ifPresentOrElse(
                existing -> userRepository.save(existing.withPasswordHash(hash).withMustChangePassword(false)),
                () -> userRepository.save(
                    new User(null, systemTenant.id(), adminUsername, hash, true, false, false, LocalDateTime.now())
                )
            );
    }
}
