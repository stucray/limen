package com.stucray.limen.security;

import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Thin {@code @Scheduled} wrapper that fires {@link SigningKeyRotator#runScheduledRotation()}
 * on the configured cron. Wrapped in {@link SchedulerLock} so that in a
 * multi-instance deployment only one instance executes the body per cron tick
 * — the lock name is the unique identifier across instances and is recorded
 * in the {@code shedlock} table for audit + manual-recovery purposes.
 *
 * <p>{@link ConditionalOnProperty} with {@code matchIfMissing=true} means the
 * schedule is on by default; tests flip {@code limen.signing-key-rotation.enabled}
 * to {@code false} in {@code application-test.yaml} so this bean never
 * registers for the test suite, and any environment can opt out the same way.
 */
@Component
@ConditionalOnProperty(
    name = "limen.signing-key-rotation.enabled",
    havingValue = "true",
    matchIfMissing = true)
public class SigningKeyRotationSchedule {

    private final SigningKeyRotator rotator;

    public SigningKeyRotationSchedule(SigningKeyRotator rotator) {
        this.rotator = rotator;
    }

    @Scheduled(cron = "${limen.signing-key-rotation.cron}")
    @SchedulerLock(
        name = "rotate-signing-keys",
        lockAtMostFor = "10m",
        lockAtLeastFor = "30s")
    public void run() {
        rotator.runScheduledRotation();
    }
}
