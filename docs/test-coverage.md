# Test Coverage Snapshot

**Generated:** 2026-04-30 from commit `e2fcdb0` (with the uncommitted JaCoCo `pom.xml` config and the in-progress `ClientManagementIntegrationTest.java` applied).

**Run:** `./mvnw clean test` — 204 tests, all passing. JaCoCo analyzes 88 production classes.

## Headline numbers

| Metric       | Coverage | Covered / Total |
|--------------|---------:|----------------:|
| Instructions |   84.0 % | 6,323 / 7,529   |
| Branches     |   70.0 % | 280 / 400       |
| Lines        |   86.1 % | 1,333 / 1,549   |
| Methods      |   89.1 % | 352 / 395       |

Detailed HTML drill-down: `target/site/jacoco/index.html` (gitignored — regenerate with `./mvnw clean test`). Per-class CSV: `target/site/jacoco/jacoco.csv`.

## Per-package summary

Sorted by line coverage, weakest first.

| Package                                        | Line %  | Branch % | Method % | Missed lines |
|------------------------------------------------|--------:|---------:|---------:|-------------:|
| com.stucray.limen (`LimenApplication` only)    |  33.3 % | n/a      |  50.0 %  |   2 |
| com.stucray.limen.management.roles             |  68.5 % | 100.0 %  |  89.5 %  |  23 |
| com.stucray.limen.management.applications      |  76.5 % |  66.7 %  |  82.4 %  |  12 |
| com.stucray.limen.management.memberships       |  77.3 % |  82.9 %  |  83.1 %  |  75 |
| com.stucray.limen.management.users             |  79.1 % |  66.7 %  |  85.7 %  |  19 |
| com.stucray.limen.auth                         |  86.6 % |  75.0 %  |  83.9 %  |  27 |
| com.stucray.limen.oauth2                       |  87.8 % |  62.9 %  |  91.0 %  |  44 |
| com.stucray.limen.management.signup            |  90.0 % |  64.3 %  | 100.0 %  |   5 |
| com.stucray.limen.security                     |  92.5 % |  66.7 %  | 100.0 %  |   8 |
| com.stucray.limen.management.web               |  95.2 % |  75.0 %  | 100.0 %  |   1 |
| com.stucray.limen.identity                     | 100.0 % |  50.0 %  | 100.0 %  |   0 |
| com.stucray.limen.management.auth              | 100.0 % |  50.0 %  | 100.0 %  |   0 |
| com.stucray.limen.management.clients           | 100.0 % |  69.4 %  |  92.6 %  |   0 |
| com.stucray.limen.management.system            | 100.0 % | 100.0 %  |  72.7 %  |   0 |
| com.stucray.limen.tenant                       | 100.0 % | 100.0 %  | 100.0 %  |   0 |
| com.stucray.limen.user                         | 100.0 % | n/a      | 100.0 %  |   0 |
| com.stucray.limen.web                          | 100.0 % |  75.0 %  | 100.0 %  |   0 |

## High-priority gaps

Security- and tenant-isolation-critical paths come first. These are the gaps most likely to mask a real bug.

### `auth.TenantUserDetailsService` — line 40 %, method 20 %
- File: `src/main/java/com/stucray/limen/auth/TenantUserDetailsService.java:32`
- Only the constructor is exercised (transitively, by Spring DI). `loadByUsernameAndSlug` — the production code path used to rehydrate a remember-me cookie into a `UserDetails` — is **never called by any test directly**. The two `orElseThrow` lambdas (unknown tenant, unknown user) are also untested.
- The single grep hit (`TenantPersistentTokenBasedRememberMeServicesUnitTest`) only references the *type*, not the method.
- **Suggested test:** unit or `@SpringBootTest` slice that calls `loadByUsernameAndSlug` for: (a) success, (b) unknown slug → `UsernameNotFoundException("Unknown tenant: …")`, (c) known slug + unknown username → `UsernameNotFoundException(username)`.

### `oauth2.OAuth2TenantLoginController` — line 33 %, method 50 %
- File: `src/main/java/com/stucray/limen/oauth2/OAuth2TenantLoginController.java:19`
- The `GET /t/{slug}/login` happy path is hit, but the **unknown-tenant fallback** at line 22–24 (`return "redirect:/manage/t/system/login"`) is uncovered. A regression here would silently send users to the wrong tenant's login.
- **Suggested test:** `@WebMvcTest` (or extend an existing OAuth2 integration test) hitting `/t/does-not-exist/login` and asserting the redirect target.

### `oauth2.MembershipGateFilter` — line 78 %, branch 54 %
- File: `src/main/java/com/stucray/limen/oauth2/MembershipGateFilter.java`
- This is the filter that gates access to OAuth2 client apps based on tenant membership — branch coverage here matters more than line coverage. With 12 of 26 branches missed, several reject/allow combinations are untested.
- **Suggested test:** drive each (member, non-member, system-tenant, missing-membership) × (interactive, programmatic) cell explicitly.

### `oauth2.EndUserPasswordChangeController` — line 81 %, branch 60 %
- File: `src/main/java/com/stucray/limen/oauth2/EndUserPasswordChangeController.java`
- Three of five methods missed (likely the GET form, error redisplay, and an alternate POST branch). End-user password change is a security-sensitive flow; validation and rejection branches deserve explicit coverage.
- **Suggested test:** integration tests for invalid current-password, mismatched new/confirm, and policy-rejected new password.

### `auth.TenantPersistentTokenBasedRememberMeServices` — line 79 %, branch 73 %
- File: `src/main/java/com/stucray/limen/auth/TenantPersistentTokenBasedRememberMeServices.java`
- Has both unit and integration tests, but 13 missed lines and 6 missed branches remain — likely cookie-corruption / slug-mismatch / token-collision edges. Worth a careful pass given the security weight.

### `security.JdbcSigningKeyStore` — line 87 %
- File: `src/main/java/com/stucray/limen/security/JdbcSigningKeyStore.java`
- 6 lines uncovered with full branch coverage — probably an error-path or a key-rotation seam. Low risk, but worth a glance since it underpins JWT signing.

## Medium-priority gaps (controllers / UI)

These are mostly Thymeleaf-rendering or form-handler endpoints in the management UI. Coverage is shallow because integration tests focus on the golden path; not every form/error rendering is exercised.

| Class | Line % | Notes |
|-------|-------:|-------|
| `management.users.PasswordChangeController` | 47 % | 2 of 3 methods covered, 8/15 lines missed. Likely the validation-error redisplay path. |
| `management.roles.RolesController` | 50 % | 2 of 7 methods missed, 23/46 lines uncovered. Form rendering / error paths. |
| `management.memberships.ClientMembersController` | 52 % | 4 of 12 methods missed, 40/83 lines. Mirrors `MembersController`. |
| `management.memberships.MembersController` | 53 % | Same shape — 4 of 12 methods missed, 33/70 lines. |
| `management.applications.ApplicationController` | 61 % | 2 of 7 methods missed, 11 lines. |
| `management.users.UserManagementController` | 71 % | 2 of 12 methods missed, 10 lines. |
| `oauth2.TenantAwareOAuth2AuthorizationService` | 74 % | 11 missed lines, 7 missed branches — probable error/serialization paths. |
| `management.signup.SignupService` | 85 % | 5 missed lines, 10 missed branches — validation rejection edges. |

For the four `*Controller` classes above, the existing tests focus on POST success and GET list. Likely missing: invalid-form redisplay, role/permission rejection, and edge cases like deleting the last admin / removing yourself.

## Likely-noise (informational, no action recommended)

- `auth.TenantUserDetailsMixin` and `auth.TenantAuthTokenMixin` (0 %): abstract Jackson mixin classes with `@JsonCreator` constructors that are never directly invoked. Their effects are observed indirectly via OAuth2 authorization (de)serialization round-trips. JaCoCo can't see that.
- `LimenApplication` (33 %): Spring Boot entry point; the `main` method runs only at boot. Not worth synthetic coverage.

## Suggested next steps (in priority order)

1. Add a unit test for `TenantUserDetailsService.loadByUsernameAndSlug` covering happy path, unknown tenant, unknown user — small, high-value.
2. Add the unknown-tenant case to `OAuth2TenantLoginController` — one extra MockMvc assertion.
3. Expand `MembershipGateFilter` tests to cover the missing branch combinations.
4. Add validation-error / policy-rejection cases to `EndUserPasswordChangeController` and `PasswordChangeController`.
5. Pad out the management `*Controller` integration tests with form-error redisplay assertions (cheap volume gain, but lower per-test value than items 1–4).

## Re-running this report

```sh
./mvnw clean test                            # regenerate target/site/jacoco/
open target/site/jacoco/index.html           # browse the HTML report
awk -F, 'NR>1 {im+=$4;ic+=$5;bm+=$6;bc+=$7;lm+=$8;lc+=$9;mm+=$12;mc+=$13} \
  END {printf "instr %.1f%% branch %.1f%% line %.1f%% method %.1f%%\n", \
       100*ic/(im+ic), 100*bc/(bm+bc), 100*lc/(lm+lc), 100*mc/(mm+mc)}' \
  target/site/jacoco/jacoco.csv              # quick headline numbers
```

If/when a CI pipeline is added, the JaCoCo XML at `target/site/jacoco/jacoco.xml` is the standard upload format for Codecov / Coveralls / Sonar.
