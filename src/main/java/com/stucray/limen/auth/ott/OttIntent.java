package com.stucray.limen.auth.ott;

import org.jspecify.annotations.Nullable;

/**
 * Distinguishes the two flows that share the OTT substrate:
 * <ul>
 *   <li>{@link #VERIFY_EMAIL} — issued at signup or via the resend-verification
 *       form; consume marks {@code users.email_verified=true}.</li>
 *   <li>{@link #PASSWORD_RESET} — issued from the forgot-password flow
 *       (slice #126); consume drops the user into the existing forced
 *       password-change pipeline.</li>
 * </ul>
 *
 * <p>The wire value is the lowercase-hyphenated form persisted in
 * {@code one_time_tokens.intent} and matches the migration's CHECK constraint.
 */
public enum OttIntent {

    VERIFY_EMAIL("verify-email"),
    PASSWORD_RESET("password-reset");

    private final String wire;

    OttIntent(String wire) {
        this.wire = wire;
    }

    String wire() {
        return wire;
    }

    static @Nullable OttIntent fromWire(String value) {
        for (OttIntent i : values()) {
            if (i.wire.equals(value)) return i;
        }
        return null;
    }
}
