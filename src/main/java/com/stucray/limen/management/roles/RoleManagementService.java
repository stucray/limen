package com.stucray.limen.management.roles;

import com.stucray.limen.management.applications.Application;
import com.stucray.limen.management.applications.ApplicationRepository;
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
    private final ApplicationRepository applicationRepository;

    public RoleManagementService(RoleRepository roleRepository, ApplicationRepository applicationRepository) {
        this.roleRepository = roleRepository;
        this.applicationRepository = applicationRepository;
    }

    public List<Role> listRoles(Long applicationId, Long tenantId) {
        requireApplication(applicationId, tenantId);
        return roleRepository.findAllByApplicationId(applicationId);
    }

    public Role getRole(Long roleId, Long applicationId, Long tenantId) {
        requireApplication(applicationId, tenantId);
        return roleRepository.findByIdAndApplicationId(roleId, applicationId)
            .orElseThrow(() -> new IllegalArgumentException("Role not found"));
    }

    public Role createRole(Long applicationId, Long tenantId, String name, String description) {
        requireApplication(applicationId, tenantId);
        if (roleRepository.existsByNameAndApplicationId(name, applicationId)) {
            throw new IllegalArgumentException("A role named '" + name + "' already exists in this application");
        }
        return roleRepository.save(
            new Role(null, applicationId, name, description, LocalDateTime.now())
        );
    }

    public void updateRole(Long roleId, Long applicationId, Long tenantId, String name, String description) {
        Role role = getRole(roleId, applicationId, tenantId);
        if (!role.name().equals(name) && roleRepository.existsByNameAndApplicationId(name, applicationId)) {
            throw new IllegalArgumentException("A role named '" + name + "' already exists in this application");
        }
        roleRepository.save(role.withName(name).withDescription(description));
    }

    public void deleteRole(Long roleId, Long applicationId, Long tenantId) {
        Role role = getRole(roleId, applicationId, tenantId);
        roleRepository.delete(role);
    }

    private Application requireApplication(Long applicationId, Long tenantId) {
        return applicationRepository.findByIdAndTenantId(applicationId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Application not found"));
    }
}
