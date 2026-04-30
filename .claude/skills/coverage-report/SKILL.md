---
name: coverage-report
description: Regenerate the JaCoCo test-coverage snapshot in docs/test-coverage.md. Use when the user asks to refresh / update / regenerate the coverage report or coverage doc, asks "what's the current test coverage", or has just landed test changes and wants the doc updated.
---

# Coverage report regeneration

Refresh `docs/test-coverage.md` from a fresh JaCoCo run. Two helpers do the heavy lifting:

- **JaCoCo plugin** (already configured in `pom.xml`) — running `./mvnw clean test` produces `target/site/jacoco/{index.html,jacoco.csv,jacoco.xml}`.
- **`scripts/coverage-report.sh`** — reads `target/site/jacoco/jacoco.csv`, compares totals + per-package line coverage against the hardcoded PR #59 baseline (commit `e2fcdb0`), and emits the **Headline numbers** + **Per-package summary** markdown tables in the canonical format (right-aligned percentages, 🟢 / 🔴 / ⚪ indicators after the `%`).

## Steps

1. **Regenerate JaCoCo data.** Run `./mvnw clean test` from the project root. Docker must be running (Testcontainers spins up Postgres). All tests must pass — if any fail, surface that to the user before proceeding.

2. **Generate the markdown tables.** Run `scripts/coverage-report.sh` and capture stdout. Verify it produced both tables (look for the `## Headline numbers` and `## Per-package summary` headers).

3. **Update `docs/test-coverage.md`.** Replace the two existing tables with the script's output. Also update:
   - The **Generated:** line at the top — bump the date and the commit SHA (`git rev-parse --short HEAD`).
   - The **Run:** line — update the test count (grep the maven output for `Tests run: N`).
   - The "Closed in this round" / "Remaining gaps" sections only if the user is doing a *substantive* coverage push. For a routine refresh, leave them alone.

4. **Show the user a short summary.** Headline numbers and the deltas from the previous snapshot. Don't paste the whole table — they can read the doc.

## Caveats

- The script's baseline is hardcoded at PR #59 (commit `e2fcdb0`). Don't change it casually — it's the reference point that makes Δ values meaningful across snapshots. If the user wants a new baseline, edit `scripts/coverage-report.sh` deliberately and call it out.
- Don't skip `clean` on the maven run — leftover `jacoco.exec` from a prior partial run can produce stale numbers.
- If a class shows surprising coverage (e.g., a controller dropped from 100 % to 50 %), that's signal — surface it to the user before mechanically pasting the new tables.
- Production code changes (renames, new classes, deletions) shift which classes appear and may invalidate the per-package baseline rows. If you see new packages or vanished ones, flag it.

## Output format

The doc currently uses:

```
| Metric       | Coverage | Δ from baseline | Covered / Total |
|--------------|---------:|----------------:|----------------:|
| Lines        | 94.6 % | +8.5 % 🟢 | 1,465 / 1,549 |
```

Right-aligned columns, emoji *after* the `%` so number-width changes don't shift the indicator. ⚪ for zero deltas (within ±0.05 pp) so the emoji column stays uniform. Don't tweak the format without asking — the user iterated to land on this specifically.
