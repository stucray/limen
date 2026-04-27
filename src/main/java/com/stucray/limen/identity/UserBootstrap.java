package com.stucray.limen.identity;

import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantProvisioningService;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.springframework.boot.CommandLineRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

@Component
public class UserBootstrap implements CommandLineRunner {

    static final String SYSTEM_SLUG = "system";
    static final String SYSTEM_DISPLAY_NAME = "System";

    private final BootstrapAdminProperties adminProperties;
    private final TenantRepository tenantRepository;
    private final TenantProvisioningService tenantProvisioningService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public UserBootstrap(
        BootstrapAdminProperties adminProperties,
        TenantRepository tenantRepository,
        TenantProvisioningService tenantProvisioningService,
        UserRepository userRepository,
        PasswordEncoder passwordEncoder
    ) {
        this.adminProperties = adminProperties;
        this.tenantRepository = tenantRepository;
        this.tenantProvisioningService = tenantProvisioningService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    @Transactional
    public void run(String... args) {
        Tenant systemTenant = tenantRepository.findBySlug(SYSTEM_SLUG)
            .orElseGet(() -> tenantProvisioningService.createTenant(SYSTEM_SLUG, SYSTEM_DISPLAY_NAME));

        if (!adminProperties.isConfigured()) return;

        String hash = passwordEncoder.encode(adminProperties.password());
        userRepository.findByUsernameAndTenantId(adminProperties.username(), systemTenant.id())
            .ifPresentOrElse(
                existing -> userRepository.save(existing.withPasswordHash(hash).withMustChangePassword(false)),
                () -> userRepository.save(
                    new User(null, systemTenant.id(), adminProperties.username(), hash, true, false, false, LocalDateTime.now())
                )
            );
    }
}
