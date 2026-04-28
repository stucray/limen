package com.stucray.limen.management.memberships;

import java.util.Set;

/**
 * Single-call helper that grants the App Membership prerequisite and the
 * Client Membership in one shot, optionally with a Role set. Used by the
 * /oauth2/authorize integration tests so each test does not re-encode the
 * three-step grant + updateRoles boilerplate.
 *
 * Mirrors the production grant order: App Membership first (eligibility
 * gate), then Client Membership; updateRoles is only invoked when a non-empty
 * set is supplied so the "Membership without Roles" path is exercised
 * naturally by passing {@link Set#of()}.
 */
public final class ClientMembershipTestFixture {

    private ClientMembershipTestFixture() {}

    public static ClientMembership grant(
        ApplicationMembershipService applicationMembershipService,
        ClientMembershipService clientMembershipService,
        Long applicationId,
        Long tenantId,
        Long userId,
        Long granterId,
        String registeredClientId,
        Set<Long> roleIds
    ) {
        applicationMembershipService.grant(applicationId, tenantId, userId, granterId);
        ClientMembership membership = clientMembershipService.grant(
            registeredClientId, applicationId, tenantId, userId, granterId
        );
        if (roleIds != null && !roleIds.isEmpty()) {
            clientMembershipService.updateRoles(
                membership.id(), registeredClientId, applicationId, tenantId, roleIds
            );
        }
        return membership;
    }
}
