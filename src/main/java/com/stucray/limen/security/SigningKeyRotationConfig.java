package com.stucray.limen.security;

import net.javacrumbs.shedlock.provider.jdbctemplate.JdbcTemplateLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import net.javacrumbs.shedlock.core.LockProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * Wires Spring's task scheduler and ShedLock for the signing-key rotation job.
 *
 * <p>{@link EnableScheduling} activates {@code @Scheduled} on
 * {@link SigningKeyRotationSchedule}. {@link EnableSchedulerLock} activates
 * the AOP advice that consumes {@code @SchedulerLock} on the same method;
 * {@code defaultLockAtMostFor} caps how long a held lock can stay held if a
 * JVM crashes mid-run, so a stuck lock never blocks the next instance for
 * longer than this. Per-method override via {@code @SchedulerLock.lockAtMostFor}
 * is the live value at runtime.
 *
 * <p>The {@link LockProvider} bean stores lock state in the {@code shedlock}
 * table (Flyway V12) on the same Postgres as the rest of the app.
 * {@code usingDbTime()} forces ShedLock to compare lock times using the
 * database clock, sidestepping JVM-clock skew between instances.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "PT10M")
class SigningKeyRotationConfig {

    @Bean
    public LockProvider lockProvider(JdbcTemplate jdbcTemplate) {
        return new JdbcTemplateLockProvider(JdbcTemplateLockProvider.Configuration.builder()
            .withJdbcTemplate(jdbcTemplate)
            .usingDbTime()
            .build());
    }
}
