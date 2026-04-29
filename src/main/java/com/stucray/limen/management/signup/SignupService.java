package com.stucray.limen.management.signup;

import com.stucray.limen.tenant.Tenant;
import com.stucray.limen.tenant.TenantProvisioningService;
import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Set;
import java.util.regex.Pattern;

@Service
public class SignupService {

    private static final Pattern SLUG_FORMAT = Pattern.compile("^[a-z0-9][a-z0-9-]{1,46}[a-z0-9]$");
    private static final Set<String> RESERVED_SLUGS = Set.of(
        "system", "admin", "manage", "api", "www", "static", "health", "limen"
    );

    private final TenantRepository tenantRepository;
    private final TenantProvisioningService tenantProvisioningService;
    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public SignupService(
        TenantRepository tenantRepository,
        TenantProvisioningService tenantProvisioningService,
        UserRepository userRepository,
        PasswordEncoder passwordEncoder
    ) {
        this.tenantRepository = tenantRepository;
        this.tenantProvisioningService = tenantProvisioningService;
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    public sealed interface SignupResult {
        record Success(String slug) implements SignupResult {}
        record Error(String field, String message) implements SignupResult {}
    }

    @Transactional
    public SignupResult signup(SignupForm form) {
        String slug = form.slug() == null ? "" : form.slug().trim();

        if (slug.length() < 3 || slug.length() > 48) {
            return new SignupResult.Error("slug", "Slug must be between 3 and 48 characters");
        }
        if (!SLUG_FORMAT.matcher(slug).matches()) {
            return new SignupResult.Error("slug", "Slug may only contain lowercase letters, digits, and hyphens, and must start and end with a letter or digit");
        }
        if (RESERVED_SLUGS.contains(slug)) {
            return new SignupResult.Error("slug", "That slug is reserved and cannot be used");
        }
        if (tenantRepository.existsBySlug(slug)) {
            return new SignupResult.Error("slug", "That slug is already taken");
        }

        String orgName = form.organizationName() == null ? "" : form.organizationName().trim();
        if (orgName.isBlank()) {
            return new SignupResult.Error("organizationName", "Organization name is required");
        }

        String username = form.username() == null ? "" : form.username().trim();
        if (username.isBlank()) {
            return new SignupResult.Error("username", "Username is required");
        }
        if (username.length() > 100) {
            return new SignupResult.Error("username", "Username must be 100 characters or fewer");
        }

        if (form.password() == null || form.password().isBlank()) {
            return new SignupResult.Error("password", "Password is required");
        }

        Tenant tenant = tenantProvisioningService.createTenant(slug, orgName);
        userRepository.save(new User(
            null, tenant.id(), username,
            passwordEncoder.encode(form.password()),
            true, false, true, LocalDateTime.now()
        ));

        return new SignupResult.Success(slug);
    }
}
