---
name: coverage-report
description: Regenerate the JaCoCo test-coverage snapshot in docs/reports/test-coverage.md. Use when the user asks to refresh / update / regenerate the coverage report or coverage doc, asks "what's the current test coverage", or has just landed test changes and wants the doc updated.
---

# Coverage report regeneration

Refresh `docs/reports/test-coverage.md` from a fresh JaCoCo run. Two helpers do the heavy lifting:

- **JaCoCo plugin** (already configured in `pom.xml`) — running `./mvnw clean test` produces `target/site/jacoco/{index.html,jacoco.csv,jacoco.xml}`.
- **`scripts/coverage-report.sh`** — reads `target/site/jacoco/jacoco.csv`, compares totals + per-package line coverage against both the hardcoded PR #59 baseline (commit `e2fcdb0`) and the most recent snapshot in `docs/reports/test-coverage-history.jsonl`, and emits the **Headline numbers** + **Per-package summary** markdown tables in the canonical format (right-aligned percentages, 🟢 / 🔴 / ⚪ indicators after the `%`; `—` in the prev column when there's no prior run). It also appends a new JSON Lines snapshot to the history file.

## Steps

1. **Regenerate JaCoCo data.** Run `./mvnw clean test` from the project root. Docker must be running (Testcontainers spins up Postgres). All tests must pass — if any fail, surface that to the user before proceeding.

2. **Generate the markdown tables.** Run `scripts/coverage-report.sh --tests N` (where `N` comes from grepping the maven output for `Tests run: N`) and capture stdout. Verify it produced both sections (`## Headline numbers` and `## Per-package summary`) and that each table includes `Δ from baseline` / `Δ Line (base)` *and* `Δ from prev` / `Δ Line (prev)` columns. The script also appends a snapshot row to `docs/reports/test-coverage-history.jsonl` as a side effect.

3. **Update `docs/reports/test-coverage.md`.** Replace the two existing tables with the script's `## Headline numbers` and `## Per-package summary` sections. Also update:
   - The **Generated:** line at the top — bump the date and the commit SHA (`git rev-parse --short HEAD`).
   - The **Run:** line — update the test count to match `--tests N`.
   - The "Closed in this round" / "Remaining gaps" sections only if the user is doing a *substantive* coverage push. For a routine refresh, leave them alone.

4. **Show the user a short summary.** Headline numbers, plus both deltas (baseline + prev) for each metric. Don't paste the whole per-package table — they can read the doc.

5. **Stage both files for commit.** `docs/reports/test-coverage.md` and `docs/reports/test-coverage-history.jsonl` should be committed together as part of the same refresh.

## Caveats

- The script's baseline is hardcoded at PR #59 (commit `e2fcdb0`). Don't change it casually — it's the reference point that makes Δ values meaningful across snapshots. If the user wants a new baseline, edit `scripts/coverage-report.sh` deliberately and call it out.
- `docs/reports/test-coverage-history.jsonl` is **append-only**. The script writes one new line per run, and this file is committed alongside `docs/reports/test-coverage.md` on every refresh. If a snapshot needs to be retracted (e.g. a botched run got committed), edit the file by hand and surface the edit to the user — don't silently drop entries.
- The script normalizes the file via `jq -c '.'` before reading it, so accidental pretty-printing (e.g. from a `jq .` debug inspection or an editor reformatter) is repaired on the next run instead of corrupting future appends. If the script ever reports `failed to parse as JSON`, the file has real damage — investigate before re-running.
- Don't skip `clean` on the maven run — leftover `jacoco.exec` from a prior partial run can produce stale numbers.
- If a class shows surprising coverage (e.g., a controller dropped from 100 % to 50 %), that's signal — surface it to the user before mechanically pasting the new tables.
- Production code changes (renames, new classes, deletions) shift which classes appear and may invalidate the per-package baseline rows. If you see new packages or vanished ones, flag it.

## Output format

The doc currently uses:

```
| Metric       | Coverage | Δ from baseline | Δ from prev | Covered / Total |
|--------------|---------:|----------------:|------------:|----------------:|
| Lines        | 95.6 % | +9.5 % 🟢 | +0.0 % ⚪ | 1,513 / 1,583 |
```

Right-aligned columns, emoji *after* the `%` so number-width changes don't shift the indicator. ⚪ for zero deltas (within ±0.05 pp) so the emoji column stays uniform. `—` in the `Δ from prev` cell when there's no prior run, or for a per-package row whose package didn't exist in the prior snapshot. Don't tweak the format without asking — the user iterated to land on this specifically.
