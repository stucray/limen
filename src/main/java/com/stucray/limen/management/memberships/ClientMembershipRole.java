package com.stucray.limen.management.memberships;

import org.springframework.data.relational.core.mapping.Table;

@Table("client_membership_role")
public record ClientMembershipRole(Long roleId) {
}
