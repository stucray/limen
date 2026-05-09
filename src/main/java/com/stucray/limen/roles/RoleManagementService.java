package com.stucray.limen.roles;

import com.stucray.limen.applications.ApplicationLookup;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Roles are transitively scoped to a Tenant via Application. Every public
 * method requires the caller to pass `(applicationId, tenantId)`; the
 * Application is fetched first so cross-tenant access is rejected before any
 * role row is touched.
 */
@Service
public class RoleManagementService {

    private final RoleRepository roleRepository;
    private final ApplicationLookup applicationLookup;

    RoleManagementService(RoleRepository roleRepository, ApplicationLookup applicationLookup) {
        this.roleRepository = roleRepository;
        this.applicationLookup = applicationLookup;
    }

    public List<Role> listRoles(Long applicationId, Long tenantId) {
        applicationLookup.require(applicationId, tenantId);
        return roleRepository.findAllByApplicationId(applicationId);
    }

    Role getRole(Long roleId, Long applicationId, Long tenantId) {
        applicationLookup.require(applicationId, tenantId);
        return roleRepository.findByIdAndApplicationId(roleId, applicationId)
            .orElseThrow(() -> new IllegalArgumentException("Role not found"));
    }

    @SuppressWarnings("NullAway") // Spring Data convention: null id on insert; populated on save
    Role createRole(Long applicationId, Long tenantId, String name, String description) {
        applicationLookup.require(applicationId, tenantId);
        if (roleRepository.existsByNameAndApplicationId(name, applicationId)) {
            throw new IllegalArgumentException("A role named '" + name + "' already exists in this application");
        }
        return roleRepository.save(
            new Role(null, applicationId, name, description, LocalDateTime.now())
        );
    }

    void updateRole(Long roleId, Long applicationId, Long tenantId, String name, String description) {
        Role role = getRole(roleId, applicationId, tenantId);
        if (!role.name().equals(name) && roleRepository.existsByNameAndApplicationId(name, applicationId)) {
            throw new IllegalArgumentException("A role named '" + name + "' already exists in this application");
        }
        roleRepository.save(role.withName(name).withDescription(description));
    }

    void deleteRole(Long roleId, Long applicationId, Long tenantId) {
        Role role = getRole(roleId, applicationId, tenantId);
        roleRepository.delete(role);
    }
}
