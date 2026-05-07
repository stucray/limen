package com.stucray.limen.memberships;

import com.stucray.limen.clients.TenantClient;
import com.stucray.limen.clients.TenantClientRepository;
import com.stucray.limen.roles.RoleResolver;
import com.stucray.limen.user.User;
import com.stucray.limen.user.UserRepository;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Client Memberships are the explicit grant — without a row here a User cannot
 * complete /oauth2/authorize for a Client (the gate lands in slice 5 / #44).
 * Tenant isolation is transitive via Client (client_metadata.tenant_id).
 *
 * Every public method requires the caller to pass `(registeredClientId,
 * applicationId, tenantId)`; the Client is fetched first and its parent
 * Application is asserted to match `applicationId` so that the URL hierarchy
 * `/applications/{appId}/clients/{registeredClientId}/...` cannot be subverted.
 *
 * Eligibility-gate enforcement: grant looks up an existing App Membership for
 * `(userId, applicationId)` where `applicationId` is the Client's parent App.
 * If absent, grant fails — there is no path to attach an App Membership from
 * a different Application, so the cross-table invariant from PRD #39 is
 * structurally enforced rather than checked after the fact.
 *
 * The JWT `roles` claim still emits `[]` after this slice — slice 4 (#43)
 * sources it from Client Membership rows.
 */
@Service
public class ClientMembershipService {

    private final ClientMembershipRepository membershipRepository;
    private final ApplicationMembershipRepository applicationMembershipRepository;
    private final TenantClientRepository tenantClientRepository;
    private final UserRepository userRepository;
    private final RoleResolver roleResolver;

    public ClientMembershipService(
        ClientMembershipRepository membershipRepository,
        ApplicationMembershipRepository applicationMembershipRepository,
        TenantClientRepository tenantClientRepository,
        UserRepository userRepository,
        RoleResolver roleResolver
    ) {
        this.membershipRepository = membershipRepository;
        this.applicationMembershipRepository = applicationMembershipRepository;
        this.tenantClientRepository = tenantClientRepository;
        this.userRepository = userRepository;
        this.roleResolver = roleResolver;
    }

    public List<ClientMembership> listMemberships(String registeredClientId, Long applicationId, Long tenantId) {
        TenantClient client = requireClient(registeredClientId, applicationId, tenantId);
        return membershipRepository.findAllByClientMetadataId(client.id());
    }

    public ClientMembership getMembership(Long membershipId, String registeredClientId, Long applicationId, Long tenantId) {
        TenantClient client = requireClient(registeredClientId, applicationId, tenantId);
        return membershipRepository.findByIdAndClientMetadataId(membershipId, client.id())
            .orElseThrow(() -> new IllegalArgumentException("Client membership not found"));
    }

    @SuppressWarnings("NullAway") // Spring Data convention: null id on insert; populated on save
    public ClientMembership grant(
        String registeredClientId, Long applicationId, Long tenantId,
        Long userId, Long grantedByUserId
    ) {
        TenantClient client = requireClient(registeredClientId, applicationId, tenantId);
        User user = userRepository.findByIdAndTenantId(userId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("User not found in this tenant"));
        // Eligibility gate: App Membership for the *Client's parent application*
        // must already exist. Looking up by applicationId (the Client's parent)
        // makes it impossible to attach an App Membership from a different App.
        ApplicationMembership appMembership = applicationMembershipRepository
            .findByUserIdAndApplicationId(user.id(), applicationId)
            .orElseThrow(() -> new IllegalArgumentException(
                "User is not a member of this application; grant Application Membership first"
            ));
        if (membershipRepository.existsByUserIdAndClientMetadataId(userId, client.id())) {
            throw new IllegalArgumentException("User is already a member of this client");
        }
        return membershipRepository.save(new ClientMembership(
            null, userId, client.id(), appMembership.id(),
            LocalDateTime.now(), grantedByUserId, Set.of()
        ));
    }

    public void updateRoles(
        Long membershipId, String registeredClientId, Long applicationId, Long tenantId,
        Set<Long> roleIds
    ) {
        ClientMembership membership = getMembership(membershipId, registeredClientId, applicationId, tenantId);
        Set<Long> requested = roleIds == null ? Set.of() : new LinkedHashSet<>(roleIds);
        roleResolver.requireRolesInApplication(applicationId, requested);
        membershipRepository.save(membership.withRoles(requested));
    }

    public void revoke(Long membershipId, String registeredClientId, Long applicationId, Long tenantId) {
        ClientMembership membership = getMembership(membershipId, registeredClientId, applicationId, tenantId);
        membershipRepository.delete(membership);
    }

    /**
     * Users in the tenant who already have an Application Membership for the
     * Client's parent App but do not yet have a Client Membership for this
     * specific Client. Drives the "Add Client Member" form's user picker.
     */
    public List<User> listGrantableUsers(String registeredClientId, Long applicationId, Long tenantId) {
        TenantClient client = requireClient(registeredClientId, applicationId, tenantId);
        List<ApplicationMembership> appMembers = applicationMembershipRepository.findAllByApplicationId(applicationId);
        List<User> grantable = new ArrayList<>();
        for (ApplicationMembership am : appMembers) {
            if (membershipRepository.existsByUserIdAndClientMetadataId(am.userId(), client.id())) continue;
            userRepository.findById(am.userId()).ifPresent(grantable::add);
        }
        return grantable;
    }

    private TenantClient requireClient(String registeredClientId, Long applicationId, Long tenantId) {
        TenantClient client = tenantClientRepository.findByRegisteredClientIdAndTenantId(registeredClientId, tenantId)
            .orElseThrow(() -> new IllegalArgumentException("Client not found"));
        if (!client.applicationId().equals(applicationId)) {
            // The URL claims this Client lives under {appId}, but it doesn't.
            // Treat as not found rather than leaking the real parent.
            throw new IllegalArgumentException("Client not found");
        }
        return client;
    }
}
