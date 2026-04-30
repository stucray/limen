# Test Coverage Snapshot

**Generated:** 2026-04-30 from commit `ce6aa36` (current `main`, after PRs #59 / #60 / #61 / #62 / #63 — initial baseline + 29-test gap fill + remember-me/client-management hardening + colored-delta doc + JSONL history). Δ columns compare against the PR #59 baseline (commit `e2fcdb0`).

**Run:** `./mvnw clean test` — 233 tests, all passing. JaCoCo analyzes 88 production classes.

## Headline numbers

| Metric       | Coverage | Δ from baseline | Covered / Total |
|--------------|---------:|----------------:|----------------:|
| Instructions | 93.1 % | +9.1 % 🟢 | 7,011 / 7,529 |
| Branches     | 74.5 % | +4.5 % 🟢 | 298 / 400 |
| Lines        | 94.6 % | +8.5 % 🟢 | 1,465 / 1,549 |
| Methods      | 92.9 % | +3.8 % 🟢 | 367 / 395 |

Detailed HTML drill-down: `target/site/jacoco/index.html` (gitignored — regenerate with `./mvnw clean test`). Per-class CSV: `target/site/jacoco/jacoco.csv`.

## Per-package summary

Sorted by line coverage, weakest first. Δ Line column compares each package against the PR #59 baseline.

| Package | Line % | Δ Line | Branch % | Method % | Missed lines |
|---------|-------:|-------:|---------:|---------:|-------------:|
| com.stucray.limen | 33.3 % | +0.0 % ⚪ | n/a | 50.0 % | 2 |
| com.stucray.limen.management.users | 87.9 % | +8.8 % 🟢 | 77.8 % | 89.3 % | 11 |
| com.stucray.limen.auth | 89.6 % | +3.0 % 🟢 | 75.0 % | 90.3 % | 21 |
| com.stucray.limen.management.signup | 90.0 % | +0.0 % ⚪ | 64.3 % | 100.0 % | 5 |
| com.stucray.limen.security | 92.5 % | +0.0 % ⚪ | 66.7 % | 100.0 % | 8 |
| com.stucray.limen.oauth2 | 92.5 % | +4.7 % 🟢 | 71.2 % | 92.5 % | 27 |
| com.stucray.limen.management.applications | 94.1 % | +17.6 % 🟢 | 83.3 % | 88.2 % | 3 |
| com.stucray.limen.management.web | 95.2 % | +0.0 % ⚪ | 75.0 % | 100.0 % | 1 |
| com.stucray.limen.management.memberships | 98.2 % | +20.9 % 🟢 | 85.4 % | 91.5 % | 6 |
| com.stucray.limen.web | 100.0 % | +0.0 % ⚪ | 75.0 % | 100.0 % | 0 |
| com.stucray.limen.identity | 100.0 % | +0.0 % ⚪ | 60.0 % | 100.0 % | 0 |
| com.stucray.limen.user | 100.0 % | +0.0 % ⚪ | n/a | 100.0 % | 0 |
| com.stucray.limen.management.system | 100.0 % | +0.0 % ⚪ | 100.0 % | 72.7 % | 0 |
| com.stucray.limen.management.roles | 100.0 % | +31.5 % 🟢 | 100.0 % | 100.0 % | 0 |
| com.stucray.limen.management.auth | 100.0 % | +0.0 % ⚪ | 50.0 % | 100.0 % | 0 |
| com.stucray.limen.management.clients | 100.0 % | +0.0 % ⚪ | 69.4 % | 92.6 % | 0 |
| com.stucray.limen.tenant | 100.0 % | +0.0 % ⚪ | 100.0 % | 100.0 % | 0 |

## Closed in this round

The previously-flagged high-priority gaps are now covered:

- `auth.TenantUserDetailsService` — happy/unknown-tenant/unknown-user paths and the unsupported `loadUserByUsername` are exercised by `TenantUserDetailsServiceUnitTest`.
- `oauth2.MembershipGateFilter` — missing `client_id`, unknown `client_id`, single- and multi-redirect-uri fallbacks, and the unbound-`TenantScope` short-circuit are exercised by `MembershipGateFilterUnitTest`.
- `management.users.PasswordChangeController` and `oauth2.EndUserPasswordChangeController` — mismatched-password and blank-password redisplay paths, plus the no-saved-request fallback for the OAuth2 controller.
- Management `*Controller` form-error redisplay paths in `MembersController.update()`, `ClientMembersController.update()`, `RolesController.update()`, `RolesController.delete()` (FK ON DELETE RESTRICT), and `ApplicationController.create()`.

## Remaining gaps

### High-priority (security-adjacent)

#### `oauth2.OAuth2TenantLoginController` — line 33 %, branch 0 %
- File: `src/main/java/com/stucray/limen/oauth2/OAuth2TenantLoginController.java:19`
- Same gap as the previous report — deliberately deferred this round.
- **Suggested test:** MockMvc GET `/t/does-not-exist/login` asserting redirect to `/manage/t/system/login`.

### Medium-priority

| Class | Line % | Notes |
|-------|-------:|-------|
| `management.users.UserManagementController` | 71 % | 2 of 12 methods missed; some POST handlers (e.g., conflict on `createUser` duplicate-username) likely uncovered. |
| `oauth2.TenantAwareOAuth2AuthorizationService` | 74 % | 11 missed lines, 7 missed branches — probable error/serialization paths. |
| `auth.TenantPersistentTokenBasedRememberMeServices` | 79 % | 13 missed lines, 6 missed branches — cookie-corruption / token-collision edges. |
| `management.signup.SignupService` | 85 % | 5 missed lines, 10 missed branches — validation rejection edges. |
| `security.JdbcSigningKeyStore` | 87 % | 6 missed lines with full branch coverage — error path or rotation seam. |

## Likely-noise (informational, no action recommended)

- `auth.TenantUserDetailsMixin` and `auth.TenantAuthTokenMixin` (0 %): abstract Jackson mixin classes. Their effects are observed indirectly via OAuth2 authorization (de)serialization round-trips.
- `LimenApplication` (33 %): Spring Boot entry point.

## Findings surfaced (not test-coverage gaps but worth noting)

- **Bug: `GET /manage/t/{slug}/applications/{appId}/edit` is broken.** The controller passes `application` as a model attribute, but Thymeleaf reserves `application` for its `ApplicationAttributeMap` context object. The reserved name shadows the controller's attribute, so `${application.name()}` in `manage/applications/edit.html` fails with `EL1004E: Method name() cannot be found on type ...ApplicationAttributeMap`. Pre-existing — uncovered because no test (and presumably no user) hit the edit-form GET. Fix: rename the model attribute to `app` (matching the convention in `list.html`) and update the template references in `edit.html`. Out of scope for this coverage round.

## Re-running this report

```sh
./mvnw clean test                            # regenerate target/site/jacoco/
open target/site/jacoco/index.html           # browse the HTML report (optional)
scripts/coverage-report.sh                   # emit the two markdown tables
                                             # above (with Δ vs PR #59 baseline)
```

`scripts/coverage-report.sh` reads `target/site/jacoco/jacoco.csv`, computes deltas against the hardcoded PR #59 baseline, and prints the **Headline numbers** + **Per-package summary** tables in the format used here (right-aligned percentages with 🟢 / 🔴 / ⚪ indicators). Pipe it into the doc by replacing the two table sections.

If/when a CI pipeline is added, the JaCoCo XML at `target/site/jacoco/jacoco.xml` is the standard upload format for Codecov / Coveralls / Sonar.
