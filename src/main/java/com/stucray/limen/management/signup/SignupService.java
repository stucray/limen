package com.stucray.limen.management.signup;

import com.stucray.limen.tenant.TenantRepository;
import com.stucray.limen.tenant.TenantUserBootstrap;
import com.stucray.limen.tenant.TenantUserBootstrap.OwnerCredentials;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import java.util.Set;
import java.util.regex.Pattern;

@Service
public class SignupService {

    private static final Pattern SLUG_FORMAT = Pattern.compile("^[a-z0-9][a-z0-9-]{1,46}[a-z0-9]$");
    private static final Pattern EMAIL_FORMAT = Pattern.compile("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$");
    private static final Set<String> RESERVED_SLUGS = Set.of(
        "system", "admin", "manage", "api", "www", "static", "health", "limen"
    );

    private final TenantRepository tenantRepository;
    private final TenantUserBootstrap tenantUserBootstrap;

    public SignupService(
        TenantRepository tenantRepository,
        TenantUserBootstrap tenantUserBootstrap
    ) {
        this.tenantRepository = tenantRepository;
        this.tenantUserBootstrap = tenantUserBootstrap;
    }

    public sealed interface SignupResult {
        record Success(String slug, String email) implements SignupResult {}
        record Error(String field, String message) implements SignupResult {}
    }

    public SignupResult signup(SignupForm form) {
        String slug = form.slug() == null ? "" : form.slug().trim();
        SignupResult.Error slugError = validateSlug(slug, tenantRepository);
        if (slugError != null) {
            return slugError;
        }

        String orgName = form.organizationName() == null ? "" : form.organizationName().trim();
        if (orgName.isBlank()) {
            return new SignupResult.Error("organizationName", "Organization name is required");
        }

        String email = form.email() == null ? "" : form.email().trim();
        SignupResult.Error emailError = validateEmail(email);
        if (emailError != null) {
            return emailError;
        }

        if (form.password() == null || form.password().isBlank()) {
            return new SignupResult.Error("password", "Password is required");
        }

        tenantUserBootstrap.bootstrap(
            slug, orgName, email, new OwnerCredentials.Provided(form.password()));
        return new SignupResult.Success(slug, email);
    }

    /**
     * Slug-validation rules, exposed as a static helper so the system-admin
     * tenant-create form can apply identical checks (PRD #120 acceptance:
     * "Slug validation reuses the rules from {@code SignupService}").
     * Returns null when the slug is acceptable.
     */
    public static SignupResult.@Nullable Error validateSlug(String slug, TenantRepository tenantRepository) {
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
        return null;
    }

    /**
     * Email-validation rules. Mirrors {@link #validateSlug} so the sysadmin
     * tenant-create form gets the same length / format checks. Returns null
     * when the email is acceptable.
     */
    public static SignupResult.@Nullable Error validateEmail(String email) {
        if (email.isBlank()) {
            return new SignupResult.Error("email", "Email is required");
        }
        if (email.length() > 255) {
            return new SignupResult.Error("email", "Email must be 255 characters or fewer");
        }
        if (!EMAIL_FORMAT.matcher(email).matches()) {
            return new SignupResult.Error("email", "Email must be a valid email address");
        }
        return null;
    }
}
