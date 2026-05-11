# Test Coverage Snapshot

<!-- coverage:auto:start -->
**Generated:** 2026-05-11 from commit `eb941f2` (current `main`). Δ columns compare against the PR #59 baseline (commit `e2fcdb0`) and the previous snapshot in `test-coverage-history.jsonl`.

**Run:** `./mvnw verify` — 468 tests, all passing. JaCoCo analyzes 178 production classes.

## Headline numbers

| Metric       | Coverage | Δ from baseline | Δ from prev | Covered / Total |
|--------------|---------:|----------------:|------------:|----------------:|
| Instructions | 93.5 % | +9.5 % 🟢 | +5.8 % 🟢 | 12,175 / 13,021 |
| Branches     | 77.4 % | +7.4 % 🟢 | +7.4 % 🟢 | 565 / 730 |
| Lines        | 93.9 % | +7.8 % 🟢 | +6.1 % 🟢 | 2,484 / 2,644 |
| Methods      | 94.5 % | +5.4 % 🟢 | +3.7 % 🟢 | 657 / 695 |

Detailed HTML drill-down: `target/site/jacoco/index.html` (gitignored — regenerate with `./mvnw clean test`). Per-class CSV: `target/site/jacoco/jacoco.csv`.

## Per-package summary

Sorted by line coverage, weakest first. Δ Line (base) compares each package against the PR #59 baseline; Δ Line (prev) compares against the previous snapshot in docs/reports/test-coverage-history.jsonl.

| Package | Line % | Δ Line (base) | Δ Line (prev) | Branch % | Method % | Missed lines |
|---------|-------:|--------------:|--------------:|---------:|---------:|-------------:|
| com.stucray.limen | 33.3 % | +0.0 % ⚪ | +0.0 % ⚪ | n/a | 50.0 % | 2 |
| com.stucray.limen.email | 77.4 % | +77.4 % 🟢 | +0.0 % ⚪ | 50.0 % | 87.5 % | 7 |
| com.stucray.limen.auth.ott | 79.1 % | +79.1 % 🟢 | +0.0 % ⚪ | 62.9 % | 79.4 % | 58 |
| com.stucray.limen.security.ratelimit | 85.7 % | +85.7 % 🟢 | +0.0 % ⚪ | 53.1 % | 100.0 % | 11 |
| com.stucray.limen.auth | 87.1 % | +0.5 % 🟢 | +0.0 % ⚪ | 75.0 % | 91.8 % | 25 |
| com.stucray.limen.security | 88.2 % | -4.3 % 🔴 | +0.0 % ⚪ | 50.0 % | 100.0 % | 2 |
| com.stucray.limen.useradmin | 89.9 % | +89.9 % 🟢 | +49.3 % 🟢 | 77.6 % | 94.3 % | 14 |
| com.stucray.limen.auth.lockout | 91.1 % | +91.1 % 🟢 | +0.0 % ⚪ | 68.8 % | 100.0 % | 5 |
| com.stucray.limen.security.signing | 91.9 % | +91.9 % 🟢 | +0.0 % ⚪ | 63.6 % | 100.0 % | 9 |
| com.stucray.limen.user | 92.6 % | -7.4 % 🔴 | +0.0 % ⚪ | 100.0 % | 90.5 % | 2 |
| com.stucray.limen.management.web | 95.2 % | +0.0 % ⚪ | +0.0 % ⚪ | 75.0 % | 100.0 % | 1 |
| com.stucray.limen.oauth2 | 95.8 % | +8.0 % 🟢 | +0.0 % ⚪ | 82.8 % | 98.7 % | 15 |
| com.stucray.limen.memberships | 98.1 % | +98.1 % 🟢 | +0.0 % ⚪ | 83.8 % | 91.0 % | 6 |
| com.stucray.limen.audit.dispatch | 98.8 % | +98.8 % 🟢 | +0.0 % ⚪ | 89.1 % | 100.0 % | 2 |
| com.stucray.limen.auth.login | 99.4 % | +99.4 % 🟢 | +0.0 % ⚪ | 87.1 % | 96.4 % | 1 |
| com.stucray.limen.enduser.web | 100.0 % | +100.0 % 🟢 | +0.0 % ⚪ | n/a | 100.0 % | 0 |
| com.stucray.limen.web | 100.0 % | +0.0 % ⚪ | +0.0 % ⚪ | 75.0 % | 100.0 % | 0 |
| com.stucray.limen.identity | 100.0 % | +0.0 % ⚪ | +0.0 % ⚪ | 60.0 % | 100.0 % | 0 |
| com.stucray.limen.applications | 100.0 % | +100.0 % 🟢 | +0.0 % ⚪ | 83.3 % | 95.0 % | 0 |
| com.stucray.limen.management.auth | 100.0 % | +0.0 % ⚪ | +0.0 % ⚪ | 50.0 % | 100.0 % | 0 |
| com.stucray.limen.provisioning | 100.0 % | +100.0 % 🟢 | +0.0 % ⚪ | 94.4 % | 100.0 % | 0 |
| com.stucray.limen.roles | 100.0 % | +100.0 % 🟢 | +0.0 % ⚪ | 100.0 % | 100.0 % | 0 |
| com.stucray.limen.system | 100.0 % | +100.0 % 🟢 | +0.0 % ⚪ | 80.0 % | 80.0 % | 0 |
| com.stucray.limen.clients | 100.0 % | +100.0 % 🟢 | +51.5 % 🟢 | 70.6 % | 95.9 % | 0 |
| com.stucray.limen.tenant | 100.0 % | +0.0 % ⚪ | +0.0 % ⚪ | 100.0 % | 100.0 % | 0 |
| com.stucray.limen.signup | 100.0 % | +100.0 % 🟢 | +29.0 % 🟢 | 100.0 % | 100.0 % | 0 |
| com.stucray.limen.audit.events | 100.0 % | +100.0 % 🟢 | +0.0 % ⚪ | n/a | 100.0 % | 0 |
| com.stucray.limen.observability | 100.0 % | +100.0 % 🟢 | +0.0 % ⚪ | 66.7 % | 100.0 % | 0 |
| com.stucray.limen.audit | 100.0 % | +100.0 % 🟢 | +0.0 % ⚪ | 100.0 % | 100.0 % | 0 |
<!-- coverage:auto:end -->

## Closed in this round

Four small additions, all targeting security-adjacent oauth2 / signup branches that the existing integration tests didn't exercise. Combined effect: branch coverage 74.5 → 79.5 % (+5.0 pp), `oauth2` package branch 71.2 → 81.8 %, `management.signup` package line 90.0 → 100.0 %.

- `oauth2.OAuth2TenantLoginController` — unknown-slug redirect (the previously-deferred high-priority gap) and known-slug happy path, in `OAuth2TenantLoginControllerUnitTest`. Class is now 100 % line / 100 % branch.
- `oauth2.TenantJwkSource` — all four arms of `resolveTenantId` (issuer-with-slug → repo, issuer-without-slug → fallback to `TenantScope`, issuer-with-unknown-slug → fallback, no context + no scope → `IllegalStateException`) plus the no-active-key throw, in `TenantJwkSourceUnitTest`. JWT signing-key resolution now branch-covered.
- `oauth2.TenantIssuerContextFilter` — the no-tenant-scope short-circuit and the default-port branches in `buildBaseUrl` (http+80, https+443, http+8080, https+8443), in `TenantIssuerContextFilterUnitTest`. The `iss` claim built into tokens is now exercised across the port-handling matrix.
- `management.signup.SignupService` — the previously-untested validation rejections (slug too short, slug too long, blank organization name, blank username, username too long, blank password) added as a parameterized test method on `SignupIntegrationTest`. All input rules now exercised.

## Previously-closed (rolled forward from prior rounds)

- `auth.TenantUserDetailsService` — happy/unknown-tenant/unknown-user paths and the unsupported `loadUserByUsername` are exercised by `TenantUserDetailsServiceUnitTest`.
- `oauth2.MembershipGateFilter` — missing `client_id`, unknown `client_id`, single- and multi-redirect-uri fallbacks, and the unbound-`TenantScope` short-circuit are exercised by `MembershipGateFilterUnitTest`.
- `auth.login.PasswordChangeController` (consolidated 2026-05-07; previously two thin per-surface controllers) — mismatched-password and blank-password redisplay paths, plus the no-saved-request fallback for the OAuth2 surface.
- Management `*Controller` form-error redisplay paths in `MembersController.update()`, `ClientMembersController.update()`, `RolesController.update()`, `RolesController.delete()` (FK ON DELETE RESTRICT), and `ApplicationController.create()`.

## Remaining gaps

### Medium-priority

| Class | Line % | Notes |
|-------|-------:|-------|
| `management.users.UserManagementController` | 71 % | 10 missed lines, 0 missed branches — likely 1–2 unreachable / dead methods. Read before testing; may be deletion candidates. |
| `oauth2.TenantAwareOAuth2AuthorizationService` | 74 % | 11 missed lines, 7 missed branches — probable JSON (de)serialization edges and lookup-miss paths in `findById` / `findByToken`. |
| `auth.TenantPersistentTokenBasedRememberMeServices` | 79 % | 13 missed lines, 6 missed branches — token-expiry branch, swallow-on-DB-failure path in `onLoginSuccess`, and the `instanceof TenantUserDetails` else-branch in `logout`. |
| `security.JdbcSigningKeyStore` | 87 % | 6 missed lines with full branch coverage — likely one straight-line method (rotation or error helper). Marginal value. |

## Likely-noise (informational, no action recommended)

- `auth.TenantUserDetailsMixin` and `auth.TenantAuthTokenMixin` (0 %): abstract Jackson mixin classes. Their effects are observed indirectly via OAuth2 authorization (de)serialization round-trips.
- `LimenApplication` (33 %): Spring Boot entry point.

## Re-running this report

`mvn verify` regenerates the auto-block above (everything between the
`<!-- coverage:auto:start -->` / `<!-- coverage:auto:end -->` markers).
