package com.stucray.limen.security.signing;

import java.time.Duration;
import java.util.List;

/**
 * Lifecycle surface — rotation, pruning, eligibility scan. Consumed only by
 * {@link SigningKeyRotator} in this package; package-private so the
 * scheduled-rotation surface doesn't leak across module boundaries.
 */
interface SigningKeyLifecycle {

    /**
     * Rotates the tenant's ACTIVE signing key: marks the current ACTIVE row
     * RETIRED with {@code retired_at = now()}, then inserts a freshly-generated
     * ACTIVE row. Both writes happen in one transaction; order is forced by
     * the partial unique index {@code tenant_signing_key_one_active_per_tenant}.
     *
     * @throws IllegalStateException if the tenant has no ACTIVE key to rotate.
     */
    RotationOutcome rotateForTenant(long tenantId);

    /**
     * Deletes every {@code RETIRED} row across all tenants whose
     * {@code retired_at} is older than {@code grace} (DB clock). Returns one
     * {@link PrunedKey} per deleted row so the orchestration layer can publish
     * a per-key {@code SigningKeyPrunedEvent} for audit + metrics.
     *
     * <p>Threshold is computed in the database (via {@code CURRENT_TIMESTAMP -
     * make_interval(...)}) to stay coherent with the {@code retired_at} value
     * written by {@link #rotateForTenant(long)}.
     */
    List<PrunedKey> pruneRetiredOlderThan(Duration grace);

    /**
     * Returns the IDs of every tenant whose {@code ACTIVE} signing key is older
     * than {@code age} (DB clock). The scheduled rotation driver iterates this
     * list and rotates each tenant in its own transaction.
     */
    List<Long> findTenantIdsWithActiveKeyOlderThan(Duration age);

    record RotationOutcome(String oldKid, String newKid) {}

    record PrunedKey(long tenantId, String kid) {}
}
