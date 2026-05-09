package com.stucray.limen.roles;

import org.springframework.stereotype.Component;

import java.util.Set;

/**
 * Validates that requested role ids resolve to Roles owned by a specific
 * Application. The role-validation rule lives here so that it cannot be
 * silently re-implemented (or forgotten) at every membership call site.
 *
 * <p>The signature deliberately omits {@code tenantId}: callers must have
 * already established that {@code applicationId} belongs to the acting
 * tenant (typically via {@code ApplicationLookup.require} or a containment
 * check upstream). Adding {@code tenantId} here would defend against a
 * caller that does not exist and would obscure where the tenant boundary
 * actually lives.
 */
@Component
public class RoleResolver {

    private final RoleRepository roleRepository;

    RoleResolver(RoleRepository roleRepository) {
        this.roleRepository = roleRepository;
    }

    /**
     * Validates that every requested role id resolves to a Role belonging to
     * {@code applicationId}. Fails fast on the first offender.
     *
     * @throws IllegalArgumentException {@code "Role not found: <id>"}
     *         when an id is unknown.
     * @throws IllegalArgumentException
     *         {@code "Role does not belong to this application"} when a role
     *         exists but is owned by a different Application.
     */
    public void requireRolesInApplication(Long applicationId, Set<Long> roleIds) {
        if (roleIds == null || roleIds.isEmpty()) {
            return;
        }
        for (Long roleId : roleIds) {
            Role role = roleRepository.findById(roleId)
                .orElseThrow(() -> new IllegalArgumentException("Role not found: " + roleId));
            if (!role.applicationId().equals(applicationId)) {
                throw new IllegalArgumentException("Role does not belong to this application");
            }
        }
    }
}
