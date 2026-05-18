# Test Coverage Snapshot

<!-- coverage:auto:start -->
**Generated:** 2026-05-18 from commit `6dfe949` (current `main`). Δ columns compare against the PR #59 baseline (commit `e2fcdb0`) and the previous snapshot in `test-coverage-history.jsonl`.

**Run:** `./mvnw verify` — 568 tests, all passing. JaCoCo analyzes 188 production classes.

## Headline numbers

| Metric       | Coverage | Δ from baseline | Δ from prev | Covered / Total |
|--------------|---------:|----------------:|------------:|----------------:|
| Instructions | 94.0 % | +10.0 % 🟢 | +0.1 % 🟢 | 12,826 / 13,648 |
| Branches     | 78.4 % | +8.4 % 🟢 | +0.0 % ⚪ | 619 / 790 |
| Lines        | 94.4 % | +8.3 % 🟢 | +0.1 % 🟢 | 2,610 / 2,765 |
| Methods      | 95.1 % | +6.0 % 🟢 | +0.1 % 🟢 | 696 / 732 |

Detailed HTML drill-down: `target/site/jacoco/index.html` (gitignored — regenerate with `./mvnw clean test`). Per-class CSV: `target/site/jacoco/jacoco.csv`.

## Per-package summary

Sorted by line coverage, weakest first. Δ Line (base) compares each package against the PR #59 baseline; Δ Line (prev) compares against the previous snapshot in docs/reports/test-coverage-history.jsonl.

| Package | Line % | Δ Line (base) | Δ Line (prev) | Branch % | Method % | Missed lines |
|---------|-------:|--------------:|--------------:|---------:|---------:|-------------:|
| com.stucray.limen | 33.3 % | +0.0 % ⚪ | +0.0 % ⚪ | n/a | 50.0 % | 2 |
| com.stucray.limen.auth.ott | 81.0 % | +81.0 % 🟢 | +0.0 % ⚪ | 69.0 % | 81.7 % | 58 |
| com.stucray.limen.email | 81.8 % | +81.8 % 🟢 | +0.0 % ⚪ | 100.0 % | 88.9 % | 6 |
| com.stucray.limen.security.ratelimit | 85.7 % | +85.7 % 🟢 | +0.0 % ⚪ | 53.1 % | 100.0 % | 11 |
| com.stucray.limen.auth | 87.1 % | +0.5 % 🟢 | +0.0 % ⚪ | 75.0 % | 91.8 % | 25 |
| com.stucray.limen.security | 88.9 % | -3.6 % 🔴 | +0.0 % ⚪ | 35.7 % | 100.0 % | 2 |
| com.stucray.limen.auth.lockout | 91.1 % | +91.1 % 🟢 | +0.0 % ⚪ | 68.8 % | 100.0 % | 5 |
| com.stucray.limen.security.signing | 93.1 % | +93.1 % 🟢 | +0.0 % ⚪ | 68.8 % | 100.0 % | 10 |
| com.stucray.limen.useradmin | 94.2 % | +94.2 % 🟢 | +0.0 % ⚪ | 79.3 % | 97.1 % | 8 |
| com.stucray.limen.oauth2.sas | 95.0 % | +95.0 % 🟢 | +0.6 % 🟢 | 78.9 % | 97.9 % | 11 |
| com.stucray.limen.management.web | 95.2 % | +0.0 % ⚪ | +0.0 % ⚪ | 75.0 % | 100.0 % | 1 |
| com.stucray.limen.user | 96.3 % | -3.7 % 🔴 | +0.0 % ⚪ | 100.0 % | 95.2 % | 1 |
| com.stucray.limen.oauth2 | 97.7 % | +9.9 % 🟢 | +0.0 % ⚪ | 88.9 % | 100.0 % | 4 |
| com.stucray.limen.memberships | 98.1 % | +98.1 % 🟢 | +0.0 % ⚪ | 83.8 % | 91.0 % | 6 |
| com.stucray.limen.auth.login | 98.6 % | +98.6 % 🟢 | +0.0 % ⚪ | 86.8 % | 96.9 % | 3 |
| com.stucray.limen.audit.dispatch | 98.8 % | +98.8 % 🟢 | +0.0 % ⚪ | 89.1 % | 100.0 % | 2 |
| com.stucray.limen.enduser.web | 100.0 % | +100.0 % 🟢 | +0.0 % ⚪ | n/a | 100.0 % | 0 |
| com.stucray.limen.web | 100.0 % | +0.0 % ⚪ | +0.0 % ⚪ | 75.0 % | 100.0 % | 0 |
| com.stucray.limen.identity | 100.0 % | +0.0 % ⚪ | +0.0 % ⚪ | 70.0 % | 100.0 % | 0 |
| com.stucray.limen.applications | 100.0 % | +100.0 % 🟢 | +0.0 % ⚪ | 83.3 % | 95.0 % | 0 |
| com.stucray.limen.management.auth | 100.0 % | +0.0 % ⚪ | +0.0 % ⚪ | n/a | 100.0 % | 0 |
| com.stucray.limen.provisioning | 100.0 % | +100.0 % 🟢 | +0.0 % ⚪ | 94.4 % | 100.0 % | 0 |
| com.stucray.limen.roles | 100.0 % | +100.0 % 🟢 | +0.0 % ⚪ | 100.0 % | 100.0 % | 0 |
| com.stucray.limen.system | 100.0 % | +100.0 % 🟢 | +0.0 % ⚪ | 80.0 % | 80.0 % | 0 |
| com.stucray.limen.clients | 100.0 % | +100.0 % 🟢 | +0.0 % ⚪ | 72.7 % | 96.2 % | 0 |
| com.stucray.limen.tenant | 100.0 % | +0.0 % ⚪ | +0.0 % ⚪ | 100.0 % | 100.0 % | 0 |
| com.stucray.limen.signup | 100.0 % | +100.0 % 🟢 | +0.0 % ⚪ | 100.0 % | 100.0 % | 0 |
| com.stucray.limen.audit.events | 100.0 % | +100.0 % 🟢 | +0.0 % ⚪ | n/a | 100.0 % | 0 |
| com.stucray.limen.observability | 100.0 % | +100.0 % 🟢 | +0.0 % ⚪ | 66.7 % | 100.0 % | 0 |
| com.stucray.limen.audit | 100.0 % | +100.0 % 🟢 | +0.0 % ⚪ | 100.0 % | 100.0 % | 0 |
<!-- coverage:auto:end -->

## Remaining gaps

These are the current line/branch shortfalls worth investigating, ordered roughly by yield-per-test-effort. The auto-block above is the authoritative source of percentages — this section captures the *why* and what to look at first.

### Medium-priority

| Class | Line % | Notes |
|-------|-------:|-------|
| `auth.ott.OttSpringContractHandler` | 31 % | 25 missed lines, 6 missed branches — bulk of the `auth.ott` package gap. Newer post-v1 module; tests likely cover the happy path only. Read before testing; some methods may be Spring-contract scaffolding that's hard to reach without an end-to-end harness. |
| `auth.ott.ResendVerificationController` | 68 % | 6 missed lines, 5 missed branches — likely error paths (already-verified, unknown email). |
| `security.ratelimit.RateLimitFilter.CompiledRule` | 69 % | 11 missed lines, 12 missed branches — inner-class rule matcher. Probably parameterised-test material across header/path/method variants. |
| `auth.TenantPersistentTokenBasedRememberMeServices` | 72 % | 19 missed lines, 6 missed branches — token-expiry branch, swallow-on-DB-failure path in `onLoginSuccess`, and the `instanceof TenantUserDetails` else-branch in `logout`. Carried over from the v1 narrative; still open. |
| `oauth2.TenantAwareOAuth2AuthorizationService` | 74 % | 11 missed lines, 7 missed branches — probable JSON (de)serialization edges and lookup-miss paths in `findById` / `findByToken`. Carried over from the v1 narrative; still open. |
| `email.SmtpEmailSender` | 76 % | 5 missed lines, 2 missed branches — SMTP-delivery error path. Likely needs a mocked `JavaMailSender` to reach. |
| `auth.ott.TenantOttRoutingFilter` | 78 % | 6 missed lines — pre-auth filter branches. |
| `useradmin.UserManagementController` | 81 % | 8 missed lines — was 71 % as `management.users.UserManagementController` in the v1 narrative; targeted tests have closed most of the gap, but a small residue remains. |

## Likely-noise (informational, no action recommended)

- `auth.TenantUserDetailsMixin` and `auth.TenantAuthTokenMixin` (0 %) — abstract Jackson mixin classes. Their effects are observed indirectly via OAuth2 authorization (de)serialization round-trips.
- `email.SmtpEmailSender.EmailDeliveryException` and `auth.ott.OttSpringContractHandler.Lookup` (0 %) — inner exception / lookup helper types whose CSV lines are the declaration only; no executable code to cover.
- `LimenApplication` (33 %) — Spring Boot entry point.
- `@ConfigurationProperties` records (`SecurityProperties`, `LockoutProperties`, `SigningKeyRotationProperties`, `RateLimitProperties`, `BootstrapAdminProperties`) — getters are exercised via binding, but autogenerated `equals` / `hashCode` / `toString` branches inflate the missed-branch count. Marginal value to test directly.

## Re-running this report

`mvn verify` regenerates the auto-block above (everything between the
`<!-- coverage:auto:start -->` / `<!-- coverage:auto:end -->` markers).
