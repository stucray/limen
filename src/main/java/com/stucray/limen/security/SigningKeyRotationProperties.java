package com.stucray.limen.security;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Knobs for the scheduled signing-key rotation job.
 *
 * <ul>
 *   <li>{@code enabled} — kill switch for the {@code @Scheduled} wrapper. The
 *       PRD pins this to {@code matchIfMissing=true} so absence-of-property
 *       defaults to enabled; tests flip it to false in
 *       {@code application-test.yaml}.</li>
 *   <li>{@code cron} — Spring cron expression for the daily run. Default 3am
 *       UTC; explicit timezone matters because Spring's {@code @Scheduled}
 *       does not infer one.</li>
 *   <li>{@code keyAge} — how old an ACTIVE key must be before the next run
 *       rotates it. Default 30 days.</li>
 *   <li>{@code gracePeriod} — how long a RETIRED key sticks around in JWKS
 *       responses before pruning. Default 24 hours.</li>
 * </ul>
 */
@ConfigurationProperties("limen.signing-key-rotation")
@Validated
record SigningKeyRotationProperties(
    @DefaultValue("true") boolean enabled,
    @DefaultValue("0 0 3 * * *") @NotBlank String cron,
    @DefaultValue("30d") @NotNull Duration keyAge,
    @DefaultValue("24h") @NotNull Duration gracePeriod
) {
    public SigningKeyRotationProperties {
        if (cron == null || cron.isBlank()) {
            throw new IllegalArgumentException(
                "limen.signing-key-rotation.cron must be a non-blank Spring cron expression; got " + cron);
        }
        if (keyAge == null || keyAge.isZero() || keyAge.isNegative()) {
            throw new IllegalArgumentException(
                "limen.signing-key-rotation.key-age must be a positive duration; got " + keyAge);
        }
        if (gracePeriod == null || gracePeriod.isZero() || gracePeriod.isNegative()) {
            throw new IllegalArgumentException(
                "limen.signing-key-rotation.grace-period must be a positive duration; got " + gracePeriod);
        }
    }
}
