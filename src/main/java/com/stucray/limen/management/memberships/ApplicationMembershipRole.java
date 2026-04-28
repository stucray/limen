package com.stucray.limen.management.memberships;

import org.springframework.data.relational.core.mapping.Table;

@Table("application_membership_role")
public record ApplicationMembershipRole(Long roleId) {
}
