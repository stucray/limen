package com.stucray.limen.management.memberships;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Table("application_membership")
public record ApplicationMembership(
    @Id Long id,
    Long userId,
    Long applicationId,
    LocalDateTime grantedAt,
    Long grantedBy,
    @MappedCollection(idColumn = "application_membership_id")
    Set<ApplicationMembershipRole> roles
) {
    public ApplicationMembership withRoles(Set<Long> roleIds) {
        Set<ApplicationMembershipRole> assignments = new LinkedHashSet<>();
        for (Long roleId : roleIds) {
            assignments.add(new ApplicationMembershipRole(roleId));
        }
        return new ApplicationMembership(id, userId, applicationId, grantedAt, grantedBy, assignments);
    }

    public Set<Long> roleIds() {
        Set<Long> ids = new LinkedHashSet<>();
        for (ApplicationMembershipRole r : roles) {
            ids.add(r.roleId());
        }
        return ids;
    }
}
