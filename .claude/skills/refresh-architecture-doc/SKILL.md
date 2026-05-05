---
name: refresh-architecture-doc
description: Sync docs/reference/architecture.md (and rarely docs/reference/ubiquitous-language.md) with the codebase before pushing or opening a PR. Use when wrapping up a refactor, when classes have been renamed/added/deleted under src/main/java/, or when finishing dev work that may have changed what either reference doc says. Don't use for typo fixes, comment-only changes, test-only changes, or dependency bumps.
---

# Architecture / UL doc refresh

Sync the two reference docs with the current codebase shape, as part of the same branch as the code change. Co-located, not follow-up.

This skill is judgment-driven, not script-driven (unlike `coverage-report`, which has a deterministic source). The procedure is short and the same every time; the *content* of the patch is the part that needs thinking.

## Two docs, different update cadence

- **`docs/reference/architecture.md`** — references concrete classes (`UserAdministrationService`, `TenantProvisioner`, `AuditDispatcher`, etc.), URL surfaces, package paths, mermaid diagrams. Most refactors touch something in here. **Check every time.**
- **`docs/reference/ubiquitous-language.md`** — domain terms only (Tenant, Owner, Application, OTT, Magic Link). Rarely shifts; class renames don't reach this doc. **Check, but expect to leave it alone unless the refactor introduced or renamed a *domain* concept (not a service implementation).**

## Steps

1. **Identify the diff scope.** Run from the project root:
   ```
   git diff origin/main...HEAD --name-only -- src/main/java/
   ```
   If the list is empty, this skill doesn't apply — you're not changing production code. Stop.

2. **Extract candidate identifiers.** From the diff:
   - **Added classes** — new files matching `*.java`. Their class name is `<filename>` minus `.java`.
   - **Deleted classes** — files in the diff with status `D` (`git diff --diff-filter=D --name-only origin/main...HEAD -- src/main/java/`). Most likely to need a doc update.
   - **Renamed classes** — `git diff --diff-filter=R --name-status origin/main...HEAD -- src/main/java/`. Old name → new name.
   - **Method renames inside a kept class** — only relevant if the doc references the method (rare; the architecture doc usually names classes, occasionally methods like `unlockAccount`).

3. **Grep both reference docs** for each old / deleted name:
   ```
   grep -nE '\b<OldClassName>\b' docs/reference/architecture.md docs/reference/ubiquitous-language.md
   ```
   Any hit is a stale reference that this branch must fix.

4. **Decide patch shape per hit:**
   - **Mechanical rename** (old name → new name in the same conceptual slot): straight `Edit` call, possibly `replace_all: true`. Update both class names and any text that describes them ("the X service that does Y" — Y might still be accurate).
   - **Deletion with replacement** (old class is gone, a new one fills the same role): rewrite the surrounding sentence to reference the replacement. Read the doc context first so the new sentence is coherent.
   - **Deletion with no replacement** (the concept itself went away — e.g., `PasswordResetSessionMarker` deleted because the intent now travels on `TenantOttAuthentication`): rewrite the surrounding paragraph to describe the new mechanism. This is the prose case, not a string substitution.
   - **New architectural concept** (a new deep module, a new entry point, a new pipeline): consider whether the architecture doc's overview section, mermaid diagrams, or a numbered subsection (§4.x) needs to mention it. Most often: yes for new top-level deep modules, no for new helper classes.

5. **Mermaid diagrams.** If a diagram in `architecture.md` lists boxed beans (e.g., the "Domain services" subgraph in §4 or the audit-listener diagram in §4.8), check whether any boxed identifier was renamed/deleted. Update the box label and any edges.

6. **Apply the edits.** Use the `Edit` tool. After each edit, re-run the grep from step 3 to confirm the hit is gone. Don't leave half-updated docs.

7. **Stage but don't commit.** Run `git add docs/reference/architecture.md` (and the UL if touched). Surface to the user what changed and let them bundle into the same commit/push as the code change. Don't open a separate PR for the doc fix — the whole point is co-location.

## Sanity check before declaring done

Run the grep once more for each candidate identifier. Zero hits in the docs = clean. The diff for this branch should now include `docs/reference/architecture.md` (and possibly UL) alongside the `src/main/java/` changes.

## Caveats

- **The UL is mostly stable.** If a refactor introduces a new domain term (a new actor, a new lifecycle entity), update it. If the refactor only renames an implementation class (`UserManagementService` → `UserAdministrationService`), the UL is unaffected — those are not domain terms.
- **Don't auto-rewrite prose unless you understand the section.** If a paragraph describes *why* something works the way it does, a class rename may not invalidate the *why*. Read the section before editing. Misleading prose is worse than stale class names.
- **Don't expand the doc speculatively.** If the refactor introduces a `*Provisioner` deep module, mention it in the existing class diagram and possibly the surface table — but don't write a new §4.x subsection unless the user asked. The architecture doc is reference, not a changelog.
- **External classes are real references.** Spring classes (`OneTimeTokenService`, `RegisteredClient`, `Authentication`, etc.) appear in the doc deliberately. They won't be in `src/main/java/`. If a grep finds them and you can't locate them in the code, they're probably external — leave them alone.
- **Mermaid syntax is fragile.** When editing diagrams, preserve quoting around labels with parens (`["text<br/>(parens)"]`) and keep cylinder shapes balanced (`[("...")]`). Render the doc in GitHub preview if you're unsure — broken mermaid won't fail the build but will look wrong on the page.

## When in doubt

If the change is ambiguous (does this rename count as "the docs need updating" or "this is internal cleanup"?), it's cheap to grep — run step 3 and let the result decide. Zero hits → no action. Hits → action required.
