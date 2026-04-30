# Test Coverage Snapshot

**Generated:** 2026-04-30 from commit `c331f86` (current `main`, including Slice 3 of the login-pipeline refactor — `TenantAccessFilter` and `TenantPersistentTokenBasedRememberMeServices` now derive their slug regexes from registered `TenantUrlScheme` beans; `SyntheticSchemeIntegrationTest` registers a third surface and proves bean discovery). Δ columns compare against the PR #59 baseline (commit `e2fcdb0`).

**Run:** `./mvnw clean test` — 287 tests, all passing. JaCoCo analyzes 91 production classes.

## Headline numbers

| Metric       | Coverage | Δ from baseline | Covered / Total |
|--------------|---------:|----------------:|----------------:|
| Instructions | 94.6 % | +10.6 % 🟢 | 7,299 / 7,719 |
| Branches     | 80.0 % | +10.0 % 🟢 | 341 / 426 |
| Lines        | 95.6 % | +9.5 % 🟢 | 1,512 / 1,581 |
| Methods      | 94.2 % | +5.1 % 🟢 | 391 / 415 |

Detailed HTML drill-down: `target/site/jacoco/index.html` (gitignored — regenerate with `./mvnw clean test`). Per-class CSV: `target/site/jacoco/jacoco.csv`.

## Per-package summary

Sorted by line coverage, weakest first. Δ Line column compares each package against the PR #59 baseline.

| Package | Line % | Δ Line | Branch % | Method % | Missed lines |
|---------|-------:|-------:|---------:|---------:|-------------:|
| com.stucray.limen | 33.3 % | +0.0 % ⚪ | n/a | 50.0 % | 2 |
| com.stucray.limen.auth | 86.8 % | +0.2 % 🟢 | 75.0 % | 88.0 % | 22 |
| com.stucray.limen.management.users | 87.5 % | +8.4 % 🟢 | 75.0 % | 89.3 % | 11 |
| com.stucray.limen.security | 92.5 % | +0.0 % ⚪ | 66.7 % | 100.0 % | 8 |
| com.stucray.limen.oauth2 | 94.4 % | +6.6 % 🟢 | 83.1 % | 96.7 % | 18 |
| com.stucray.limen.management.web | 95.2 % | +0.0 % ⚪ | 75.0 % | 100.0 % | 1 |
| com.stucray.limen.management.memberships | 98.2 % | +20.9 % 🟢 | 85.4 % | 91.5 % | 6 |
| com.stucray.limen.auth.login | 99.2 % | +99.2 % 🟢 | 83.3 % | 100.0 % | 1 |
| com.stucray.limen.web | 100.0 % | +0.0 % ⚪ | 75.0 % | 100.0 % | 0 |
| com.stucray.limen.identity | 100.0 % | +0.0 % ⚪ | 60.0 % | 100.0 % | 0 |
| com.stucray.limen.user | 100.0 % | +0.0 % ⚪ | n/a | 100.0 % | 0 |
| com.stucray.limen.management.system | 100.0 % | +0.0 % ⚪ | 100.0 % | 72.7 % | 0 |
| com.stucray.limen.management.roles | 100.0 % | +31.5 % 🟢 | 100.0 % | 100.0 % | 0 |
| com.stucray.limen.management.auth | 100.0 % | +0.0 % ⚪ | 50.0 % | 100.0 % | 0 |
| com.stucray.limen.management.signup | 100.0 % | +10.0 % 🟢 | 85.7 % | 100.0 % | 0 |
| com.stucray.limen.management.applications | 100.0 % | +23.5 % 🟢 | 83.3 % | 94.1 % | 0 |
| com.stucray.limen.management.clients | 100.0 % | +0.0 % ⚪ | 69.4 % | 92.6 % | 0 |
| com.stucray.limen.tenant | 100.0 % | +0.0 % ⚪ | 100.0 % | 100.0 % | 0 |

## Closed in this round

Four small additions, all targeting security-adjacent oauth2 / signup branches that the existing integration tests didn't exercise. Combined effect: branch coverage 74.5 → 79.5 % (+5.0 pp), `oauth2` package branch 71.2 → 81.8 %, `management.signup` package line 90.0 → 100.0 %.

- `oauth2.OAuth2TenantLoginController` — unknown-slug redirect (the previously-deferred high-priority gap) and known-slug happy path, in `OAuth2TenantLoginControllerUnitTest`. Class is now 100 % line / 100 % branch.
- `oauth2.TenantJwkSource` — all four arms of `resolveTenantId` (issuer-with-slug → repo, issuer-without-slug → fallback to `TenantScope`, issuer-with-unknown-slug → fallback, no context + no scope → `IllegalStateException`) plus the no-active-key throw, in `TenantJwkSourceUnitTest`. JWT signing-key resolution now branch-covered.
- `oauth2.TenantIssuerContextFilter` — the no-tenant-scope short-circuit and the default-port branches in `buildBaseUrl` (http+80, https+443, http+8080, https+8443), in `TenantIssuerContextFilterUnitTest`. The `iss` claim built into tokens is now exercised across the port-handling matrix.
- `management.signup.SignupService` — the previously-untested validation rejections (slug too short, slug too long, blank organization name, blank username, username too long, blank password) added as a parameterized test method on `SignupIntegrationTest`. All input rules now exercised.

## Previously-closed (rolled forward from prior rounds)

- `auth.TenantUserDetailsService` — happy/unknown-tenant/unknown-user paths and the unsupported `loadUserByUsername` are exercised by `TenantUserDetailsServiceUnitTest`.
- `oauth2.MembershipGateFilter` — missing `client_id`, unknown `client_id`, single- and multi-redirect-uri fallbacks, and the unbound-`TenantScope` short-circuit are exercised by `MembershipGateFilterUnitTest`.
- `management.users.PasswordChangeController` and `oauth2.EndUserPasswordChangeController` — mismatched-password and blank-password redisplay paths, plus the no-saved-request fallback for the OAuth2 controller.
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

```sh
./mvnw clean test                            # regenerate target/site/jacoco/
open target/site/jacoco/index.html           # browse the HTML report (optional)
scripts/coverage-report.sh                   # emit the two markdown tables
                                             # above (with Δ vs PR #59 baseline)
```

`scripts/coverage-report.sh` reads `target/site/jacoco/jacoco.csv`, computes deltas against the hardcoded PR #59 baseline, and prints the **Headline numbers** + **Per-package summary** tables in the format used here (right-aligned percentages with 🟢 / 🔴 / ⚪ indicators). Pipe it into the doc by replacing the two table sections.

If/when a CI pipeline is added, the JaCoCo XML at `target/site/jacoco/jacoco.xml` is the standard upload format for Codecov / Coveralls / Sonar.
