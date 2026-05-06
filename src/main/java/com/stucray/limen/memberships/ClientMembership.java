package com.stucray.limen.memberships;

import org.springframework.data.annotation.Id;
import org.springframework.data.relational.core.mapping.MappedCollection;
import org.springframework.data.relational.core.mapping.Table;

import java.time.LocalDateTime;
import java.util.LinkedHashSet;
import java.util.Set;

@Table("client_membership")
public record ClientMembership(
    @Id Long id,
    Long userId,
    Long clientMetadataId,
    Long applicationMembershipId,
    LocalDateTime grantedAt,
    Long grantedBy,
    @MappedCollection(idColumn = "client_membership_id")
    Set<ClientMembershipRole> roles
) {
    public ClientMembership withRoles(Set<Long> roleIds) {
        Set<ClientMembershipRole> assignments = new LinkedHashSet<>();
        for (Long roleId : roleIds) {
            assignments.add(new ClientMembershipRole(roleId));
        }
        return new ClientMembership(
            id, userId, clientMetadataId, applicationMembershipId,
            grantedAt, grantedBy, assignments
        );
    }

    public Set<Long> roleIds() {
        Set<Long> ids = new LinkedHashSet<>();
        for (ClientMembershipRole r : roles) {
            ids.add(r.roleId());
        }
        return ids;
    }
}
