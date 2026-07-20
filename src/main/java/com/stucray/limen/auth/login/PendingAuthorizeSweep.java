package com.stucray.limen.auth.login;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled housekeeping that deletes expired {@link PendingAuthorizeStore}
 * rows. Wrapped in {@link SchedulerLock} so that in a multi-instance deployment
 * only one instance sweeps per tick — mirrors {@code SigningKeyRotationSchedule}.
 *
 * <p>{@link ConditionalOnProperty} with {@code matchIfMissing=true} means the
 * sweep is on by default; any environment (or the test profile) can opt out via
 * {@code limen.pending-authorize-sweep.enabled=false}. Expiry is also enforced
 * on read in {@link PendingAuthorizeStore#consume}, so this is pure cleanup.
 */
@Component
@ConditionalOnProperty(
    name = "limen.pending-authorize-sweep.enabled",
    havingValue = "true",
    matchIfMissing = true)
class PendingAuthorizeSweep {

    private final PendingAuthorizeStore store;

    PendingAuthorizeSweep(PendingAuthorizeStore store) {
        this.store = store;
    }

    @Scheduled(cron = "${limen.pending-authorize-sweep.cron}")
    @SchedulerLock(
        name = "sweep-pending-authorize",
        lockAtMostFor = "5m",
        lockAtLeastFor = "30s")
    void run() {
        store.deleteExpired();
    }
}
