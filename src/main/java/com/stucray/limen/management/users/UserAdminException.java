package com.stucray.limen.management.users;

/**
 * Sealed exception type for invariant violations in {@link UserAdministrationService}.
 *
 * <p>The controller catches the parent in one {@code @ExceptionHandler} and flash-redirects
 * with {@link #userMessage} as the displayed error. The {@code operation} tag is the
 * machine-friendly identifier of the action that was rejected (used in audit logging
 * and future HTTP-status discrimination).
 *
 * <p>Subtypes are sealed so a future handler can pattern-match the four kinds and
 * (e.g.) map {@link UserNotInTenant} to 404 while {@link WouldOrphanTenant} maps to 409
 * — without losing the compile-time exhaustiveness check.
 */
public sealed abstract class UserAdminException extends RuntimeException
    permits UserAdminException.CannotTargetSelf,
            UserAdminException.WouldOrphanTenant,
            UserAdminException.TargetNotEligible,
            UserAdminException.UserNotInTenant {

    public final String userMessage;
    public final String operation;

    protected UserAdminException(String operation, String userMessage) {
        super("[" + operation + "] " + userMessage);
        this.operation = operation;
        this.userMessage = userMessage;
    }

    /** The actor and the target are the same user — actor would lock themselves out. */
    public static final class CannotTargetSelf extends UserAdminException {
        public CannotTargetSelf(String operation, String userMessage) {
            super(operation, userMessage);
        }
    }

    /** The action would leave the tenant with zero enabled tenant-owners. */
    public static final class WouldOrphanTenant extends UserAdminException {
        public WouldOrphanTenant(String operation, String userMessage) {
            super(operation, userMessage);
        }
    }

    /** Target user is in an ineligible state for this operation (e.g., disabled, unverified). */
    public static final class TargetNotEligible extends UserAdminException {
        public TargetNotEligible(String operation, String userMessage) {
            super(operation, userMessage);
        }
    }

    /** Target userId either doesn't exist or belongs to a different tenant. */
    public static final class UserNotInTenant extends UserAdminException {
        public UserNotInTenant(String operation) {
            super(operation, "User not found");
        }
    }
}
