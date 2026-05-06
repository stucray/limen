package com.stucray.limen.memberships;

import com.stucray.limen.applications.Application;
import com.stucray.limen.applications.ApplicationRepository;
import com.stucray.limen.roles.Role;
import com.stucray.limen.roles.RoleRepository;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Application Memberships are transitively scoped to a Tenant via Application.
 * Every public method requires the caller to pass `(applicationId, tenantId)`;
 * the Application is fetched first so cross-tenant access is rejected before
 * any membership row is touched.
 *
 * App Membership Roles in this slice govern only management-console authority
 * over the Application — the JWT `roles` claim still emits `[]` (changed in
 * slice 4 / issue #43, which sources the claim from Client Membership only).
 */
@Service
public class ApplicationMembershipService {

    private final ApplicationMembershipRepository membershipRepository;
    private final ApplicationRepository applicationRepository;
    private final UserRepository userRepository;
    private final RoleRepository roleRepository;

    public ApplicationMembershipService(
        ApplicationMembershipRepository membershipRepository,
        ApplicationRepository applicationRepository,
        UserRepository userRepository,
        RoleRepository roleRepository
    ) {
        this.membershipRepository = membershipRepository;
        this.applicationRepository = applicationRepository;
        this.userRepository = userRepository;
        this.roleRepository = roleRepository;
    }

    public List<ApplicationMembership> listMemberships(Long applicationId, Long tenantId) {
        requireApplication(applicationId, tenantId);
        return membershipRepository.findAllByApplicationId(applicationId);
    }

    public ApplicationMembership getMembership(Long membershipId, Long applicationId, Long tenantId) {
        requireApplication(applicationId, tenantId);
        return membershipRepository.findByIdAndApplicationId(membershipId, applicationId)
            .orElseThrow(() -> new IllegalArgumentException("Membership not found"));
    }

    @SuppressWarnings("NullAway") // Spring Data convention: null id on insert; populated on save
    public ApplicationMembership grant(Long applicationId, Long tenantId, Long userId, Long grantedByUserId) {
        Application app = requireApplication(applicationId, tenantId);
        User user = userRepository.findByIdAndTenantId(userId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("User not found in this tenant"));
        // Belt-and-braces: the lookup above already filters by tenantId, but
        // make the cross-tenant intent explicit so this check survives any
        // future refactor of the lookup.
        if (!user.tenantId().equals(app.tenantId())) {
            throw new IllegalArgumentException("User does not belong to the same tenant as this application");
        }
        if (membershipRepository.existsByUserIdAndApplicationId(userId, applicationId)) {
            throw new IllegalArgumentException("User is already a member of this application");
        }
        return membershipRepository.save(new ApplicationMembership(
            null, userId, applicationId, LocalDateTime.now(), grantedByUserId, Set.of()
        ));
    }

    public void updateRoles(Long membershipId, Long applicationId, Long tenantId, Set<Long> roleIds) {
        ApplicationMembership membership = getMembership(membershipId, applicationId, tenantId);
        Set<Long> requested = roleIds == null ? Set.of() : new LinkedHashSet<>(roleIds);
        for (Long roleId : requested) {
            Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleId));
            if (!role.applicationId().equals(applicationId)) {
                throw new IllegalArgumentException("Role does not belong to this application");
            }
        }
        membershipRepository.save(membership.withRoles(requested));
    }

    public void revoke(Long membershipId, Long applicationId, Long tenantId) {
        ApplicationMembership membership = getMembership(membershipId, applicationId, tenantId);
        membershipRepository.delete(membership);
    }

    /**
     * Users in the tenant who do not yet have a Membership for this Application.
     * Used to populate the "Add member" form's user picker.
     */
    public List<User> listGrantableUsers(Long applicationId, Long tenantId) {
        requireApplication(applicationId, tenantId);
        List<User> all = userRepository.findAllByTenantId(tenantId);
        List<User> grantable = new java.util.ArrayList<>();
        for (User u : all) {
            if (!membershipRepository.existsByUserIdAndApplicationId(u.id(), applicationId)) {
                grantable.add(u);
            }
        }
        return grantable;
    }

    private Application requireApplication(Long applicationId, Long tenantId) {
        return applicationRepository.findByIdAndTenantId(applicationId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Application not found"));
    }
}
