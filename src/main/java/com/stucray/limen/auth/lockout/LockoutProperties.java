package com.stucray.limen.auth.lockout;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import java.time.Duration;

/**
 * Knobs for {@link LoginAttemptTracker} and the auth-provider pre-check.
 *
 * <ul>
 *   <li>{@code threshold} — consecutive failed attempts before the account is
 *       locked. Defaults to 5; PRD #120 user story 21 calls for "small enough
 *       to defeat credential stuffing, large enough to absorb fat-finger".</li>
 *   <li>{@code window} — how long the lock holds. Defaults to 15 minutes; the
 *       admin "Unlock account" path bypasses this for legitimate users who
 *       can't wait.</li>
 * </ul>
 */
@ConfigurationProperties("limen.lockout")
@Validated
public record LockoutProperties(
    @Min(MIN_THRESHOLD) int threshold,
    @NotNull Duration window
) {
    private static final int MIN_THRESHOLD = 1;

    public LockoutProperties {
        if (threshold < MIN_THRESHOLD) {
            throw new IllegalArgumentException(
                "limen.lockout.threshold must be >= " + MIN_THRESHOLD + "; got " + threshold);
        }
        if (window == null || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException(
                "limen.lockout.window must be a positive duration; got " + window);
        }
    }
}
