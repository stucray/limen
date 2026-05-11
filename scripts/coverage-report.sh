#!/usr/bin/env bash
# Emit the auto-block (Generated/Run lines, Headline numbers, Per-package
# summary) for docs/reports/test-coverage.md from a JaCoCo CSV. Each table
# shows two Δ columns: one against the PR #59 baseline (commit e2fcdb0)
# and one against the previous run recorded in
# docs/reports/test-coverage-history.jsonl.
#
# Side effect: appends a JSON Lines snapshot to
# docs/reports/test-coverage-history.jsonl (resolved relative to this script).
#
# Usage:
#   ./mvnw clean test                                              # regenerate target/site/jacoco/jacoco.csv
#   scripts/coverage-report.sh                                     # print auto-block to stdout
#   scripts/coverage-report.sh --write docs/reports/test-coverage.md   # splice into markers in <path>
#   scripts/coverage-report.sh --tests 245                         # override auto-computed test count
#   scripts/coverage-report.sh path/to/jacoco.csv                  # custom CSV path
#
# When --write is given, replaces the region between
#   <!-- coverage:auto:start --> ... <!-- coverage:auto:end -->
# in <path>. Write is atomic (tmp + os-rename). This is the mode bound to
# the Maven verify phase via exec-maven-plugin (see pom.xml).

set -euo pipefail

CSV=""
TESTS=""
WRITE_PATH=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --tests)     TESTS="${2:-}"; shift 2 ;;
        --tests=*)   TESTS="${1#*=}"; shift ;;
        --write)     WRITE_PATH="${2:-}"; shift 2 ;;
        --write=*)   WRITE_PATH="${1#*=}"; shift ;;
        -h|--help)
            sed -n '2,22p' "$0"
            exit 0
            ;;
        *)
            CSV="$1"; shift
            ;;
    esac
done
CSV="${CSV:-target/site/jacoco/jacoco.csv}"

if [[ ! -f "$CSV" ]]; then
    echo "Error: $CSV not found. Run ./mvnw clean test first." >&2
    exit 1
fi

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
REPO_ROOT="$(cd "$SCRIPT_DIR/.." && pwd)"
HISTORY_FILE="$REPO_ROOT/docs/reports/test-coverage-history.jsonl"

DATE_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
DATE_DISPLAY="$(date -u +%Y-%m-%d)"
SHA="$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"

# Auto-compute test count if not supplied via --tests. Sums the
# tests="N" attribute on each surefire/failsafe testsuite XML root.
# Missing report dirs (e.g. running before any tests) leave TESTS empty,
# which renders as "tests, all passing" — same shape as before, just
# without a number.
if [[ -z "$TESTS" ]]; then
    TESTS=$(
        cat \
            "$REPO_ROOT"/target/surefire-reports/TEST-*.xml \
            "$REPO_ROOT"/target/failsafe-reports/TEST-*.xml 2>/dev/null \
        | grep -hoE '<testsuite[^>]*tests="[0-9]+"' \
        | grep -oE 'tests="[0-9]+"' \
        | awk -F'"' '{ sum += $2 } END { if (NR > 0) print sum }'
    ) || true
fi

# Class count from CSV (one row per analysed class, minus the header).
CLASS_COUNT=$(awk 'END { print NR - 1 }' "$CSV")

# Canonicalize the history file to one-object-per-line JSONL. `jq -c '.'`
# accepts both already-correct JSONL and concatenated pretty-printed JSON,
# so this repairs accidental drift (e.g. someone ran `jq .` to inspect it)
# without touching a correct file beyond a no-op rewrite.
if [[ -f "$HISTORY_FILE" && -s "$HISTORY_FILE" ]] && command -v jq >/dev/null 2>&1; then
    if ! jq -c '.' "$HISTORY_FILE" > "$HISTORY_FILE.tmp" 2>/dev/null; then
        rm -f "$HISTORY_FILE.tmp"
        echo "warning: $HISTORY_FILE failed to parse as JSON; leaving as-is" >&2
    else
        mv "$HISTORY_FILE.tmp" "$HISTORY_FILE"
    fi
fi

# Read prior run's headline totals (if any) for the "Δ from prev" column.
# Tolerate missing/empty file. Extraction is regex-based on a known shape.
PREV_INSTR=""; PREV_BRANCH=""; PREV_LINE=""; PREV_METHOD=""
if [[ -f "$HISTORY_FILE" && -s "$HISTORY_FILE" ]]; then
    PREV_RAW="$(tail -n 1 "$HISTORY_FILE")"
    PREV_INSTR=$(printf '%s' "$PREV_RAW"  | sed -nE 's/.*"instructions":([0-9.]+).*/\1/p')
    PREV_BRANCH=$(printf '%s' "$PREV_RAW" | sed -nE 's/.*"branches":([0-9.]+).*/\1/p')
    PREV_LINE=$(printf '%s' "$PREV_RAW"   | sed -nE 's/.*"lines":([0-9.]+).*/\1/p')
    PREV_METHOD=$(printf '%s' "$PREV_RAW" | sed -nE 's/.*"methods":([0-9.]+).*/\1/p')
fi

TMP_JSON="$(mktemp)"
PREV_PKG_FILE="$(mktemp)"
TMP_MD="$(mktemp)"
trap 'rm -f "$TMP_JSON" "$PREV_PKG_FILE" "$TMP_MD"' EXIT

# Dump prior run's per-package line coverage as `<pkg> <line%>` lines.
# Tolerate missing/empty history; the new package map will simply be empty
# and the per-package "Δ from prev" column will render as "—" everywhere.
if [[ -f "$HISTORY_FILE" && -s "$HISTORY_FILE" ]] && command -v jq >/dev/null 2>&1; then
    tail -n 1 "$HISTORY_FILE" \
        | jq -r '.packages | to_entries[] | "\(.key) \(.value.line)"' \
        > "$PREV_PKG_FILE" 2>/dev/null || true
fi

awk -F, \
    -v date_utc="$DATE_UTC" \
    -v date_display="$DATE_DISPLAY" \
    -v sha="$SHA" \
    -v tests="${TESTS:-}" \
    -v class_count="$CLASS_COUNT" \
    -v prev_instr="$PREV_INSTR" \
    -v prev_branch="$PREV_BRANCH" \
    -v prev_line_v="$PREV_LINE" \
    -v prev_method="$PREV_METHOD" \
    -v prev_pkg_file="$PREV_PKG_FILE" \
    -v jsonpath="$TMP_JSON" \
    '
BEGIN {
    # Baseline snapshot: commit e2fcdb0 (PR #59 initial JaCoCo run).
    base_instr  = 84.0
    base_branch = 70.0
    base_line   = 86.1
    base_method = 89.1

    # Per-package line % from the previous run (empty on first run).
    if (prev_pkg_file != "") {
        while ((getline pl < prev_pkg_file) > 0) {
            n2 = split(pl, a, " ")
            if (n2 == 2) prev_line[a[1]] = a[2]
        }
        close(prev_pkg_file)
    }

    base["com.stucray.limen"]                          = 33.3
    base["com.stucray.limen.auth"]                     = 86.6
    base["com.stucray.limen.identity"]                 = 100.0
    base["com.stucray.limen.management.applications"]  = 76.5
    base["com.stucray.limen.management.auth"]          = 100.0
    base["com.stucray.limen.management.clients"]       = 100.0
    base["com.stucray.limen.management.memberships"]   = 77.3
    base["com.stucray.limen.management.roles"]         = 68.5
    base["com.stucray.limen.management.signup"]        = 90.0
    base["com.stucray.limen.management.system"]        = 100.0
    base["com.stucray.limen.management.users"]         = 79.1
    base["com.stucray.limen.management.web"]           = 95.2
    base["com.stucray.limen.oauth2"]                   = 87.8
    base["com.stucray.limen.security"]                 = 92.5
    base["com.stucray.limen.tenant"]                   = 100.0
    base["com.stucray.limen.user"]                     = 100.0
    base["com.stucray.limen.web"]                      = 100.0
}

function emoji(d) {
    if (d >  0.05) return "🟢"
    if (d < -0.05) return "🔴"
    return "⚪"
}

function delta(d) {
    return sprintf("%s%.1f %% %s", (d >= 0 ? "+" : ""), d, emoji(d))
}

# Round to the same precision (1 dp) we display in the markdown tables and
# persist to JSONL. Used for Δ-from-last-run so the diff matches what the
# user sees (93.0 → 93.0 reads as +0.0, not -0.0 from float drift).
function round1(x) { return sprintf("%.1f", x) + 0 }

# Δ-from-prev cell: blank-prev (first run, or new package) renders as "—",
# otherwise the diff of the rounded values to match what the user sees.
function delta_or_dash(curr, prev_str) {
    if (prev_str == "") return "—"
    return delta(round1(curr) - prev_str)
}

function comma(n,   s, len, out, i) {
    s = sprintf("%d", n); len = length(s); out = ""
    for (i = 1; i <= len; i++) {
        out = out substr(s, i, 1)
        if ((len - i) % 3 == 0 && i != len) out = out ","
    }
    return out
}

NR == 1 { next }

{
    im += $4;  ic += $5
    bm += $6;  bc += $7
    lm += $8;  lc += $9
    mm += $12; mc += $13

    pkg = $2
    im_p[pkg] += $4;  ic_p[pkg] += $5
    bm_p[pkg] += $6;  bc_p[pkg] += $7
    lm_p[pkg] += $8;  lc_p[pkg] += $9
    mm_p[pkg] += $12; mc_p[pkg] += $13
}

END {
    ip = 100 * ic / (im + ic)
    bp = 100 * bc / (bm + bc)
    lp = 100 * lc / (lm + lc)
    mp = 100 * mc / (mm + mc)

    tests_phrase = (tests == "" ? "tests" : tests " tests")
    printf "**Generated:** %s from commit `%s` (current `main`). Δ columns compare against the PR #59 baseline (commit `e2fcdb0`) and the previous snapshot in `test-coverage-history.jsonl`.\n", date_display, sha
    print ""
    printf "**Run:** `./mvnw verify` — %s, all passing. JaCoCo analyzes %d production classes.\n", tests_phrase, class_count
    print ""

    print "## Headline numbers"; print ""
    print "| Metric       | Coverage | Δ from baseline | Δ from prev | Covered / Total |"
    print "|--------------|---------:|----------------:|------------:|----------------:|"
    printf "| Instructions | %.1f %% | %s | %s | %s / %s |\n", ip, delta(ip - base_instr),  delta_or_dash(ip, prev_instr),  comma(ic), comma(im + ic)
    printf "| Branches     | %.1f %% | %s | %s | %s / %s |\n", bp, delta(bp - base_branch), delta_or_dash(bp, prev_branch), comma(bc), comma(bm + bc)
    printf "| Lines        | %.1f %% | %s | %s | %s / %s |\n", lp, delta(lp - base_line),   delta_or_dash(lp, prev_line_v), comma(lc), comma(lm + lc)
    printf "| Methods      | %.1f %% | %s | %s | %s / %s |\n", mp, delta(mp - base_method), delta_or_dash(mp, prev_method), comma(mc), comma(mm + mc)

    print ""
    print "Detailed HTML drill-down: `target/site/jacoco/index.html` (gitignored — regenerate with `./mvnw clean test`). Per-class CSV: `target/site/jacoco/jacoco.csv`."

    print ""; print "## Per-package summary"; print ""
    print "Sorted by line coverage, weakest first. Δ Line (base) compares each package against the PR #59 baseline; Δ Line (prev) compares against the previous snapshot in docs/reports/test-coverage-history.jsonl."
    print ""
    print "| Package | Line % | Δ Line (base) | Δ Line (prev) | Branch % | Method % | Missed lines |"
    print "|---------|-------:|--------------:|--------------:|---------:|---------:|-------------:|"

    n = 0
    for (p in lm_p) pkgs[++n] = p
    # Insertion sort by line coverage ascending.
    for (i = 2; i <= n; i++) {
        k = pkgs[i]
        lk = 100 * lc_p[k] / (lm_p[k] + lc_p[k])
        j = i - 1
        while (j >= 1) {
            lj = 100 * lc_p[pkgs[j]] / (lm_p[pkgs[j]] + lc_p[pkgs[j]])
            if (lj > lk) { pkgs[j+1] = pkgs[j]; j-- } else break
        }
        pkgs[j+1] = k
    }

    for (i = 1; i <= n; i++) {
        p = pkgs[i]
        lp_pkg = 100 * lc_p[p] / (lm_p[p] + lc_p[p])
        bp_pkg = (bm_p[p] + bc_p[p] > 0) ? sprintf("%.1f %%", 100 * bc_p[p] / (bm_p[p] + bc_p[p])) : "n/a"
        mp_pkg = 100 * mc_p[p] / (mm_p[p] + mc_p[p])
        bv = (p in base) ? base[p] : 0
        prev_v = (p in prev_line) ? prev_line[p] : ""
        printf "| %s | %.1f %% | %s | %s | %s | %.1f %% | %d |\n",
            p, lp_pkg, delta(lp_pkg - bv), delta_or_dash(lp_pkg, prev_v), bp_pkg, mp_pkg, lm_p[p]
    }

    # Build the JSON Lines entry and write it to a temp path. The shell
    # appends it to the history file after we have read the prior last line.
    json = "{\"date\":\"" date_utc "\",\"sha\":\"" sha "\","
    json = json "\"tests\":" (tests == "" ? "null" : tests + 0) ","
    json = json sprintf("\"totals\":{\"instructions\":%.1f,\"branches\":%.1f,\"lines\":%.1f,\"methods\":%.1f}", ip, bp, lp, mp)
    json = json ",\"packages\":{"
    sep = ""
    for (i = 1; i <= n; i++) {
        p = pkgs[i]
        lp_pkg = 100 * lc_p[p] / (lm_p[p] + lc_p[p])
        mp_pkg = 100 * mc_p[p] / (mm_p[p] + mc_p[p])
        json = json sep "\"" p "\":{"
        json = json sprintf("\"line\":%.1f,", lp_pkg)
        if (bm_p[p] + bc_p[p] > 0) {
            json = json sprintf("\"branch\":%.1f,", 100 * bc_p[p] / (bm_p[p] + bc_p[p]))
        } else {
            json = json "\"branch\":null,"
        }
        json = json sprintf("\"method\":%.1f,\"missed_lines\":%d}", mp_pkg, lm_p[p])
        sep = ","
    }
    json = json "}}"
    print json > jsonpath
}
' "$CSV" > "$TMP_MD"

mkdir -p "$(dirname "$HISTORY_FILE")"
cat "$TMP_JSON" >> "$HISTORY_FILE"

if [[ -z "$WRITE_PATH" ]]; then
    cat "$TMP_MD"
    exit 0
fi

# Splice mode: replace the region between
#   <!-- coverage:auto:start -->
#   <!-- coverage:auto:end -->
# in WRITE_PATH with the freshly-generated block. Markers are preserved.
# Write is atomic (tmp + mv) so a crash mid-splice never leaves a
# half-written doc in the working tree.
if [[ ! -f "$WRITE_PATH" ]]; then
    echo "Error: $WRITE_PATH not found." >&2
    exit 1
fi
if ! grep -q '<!-- coverage:auto:start -->' "$WRITE_PATH" \
   || ! grep -q '<!-- coverage:auto:end -->' "$WRITE_PATH"; then
    echo "Error: $WRITE_PATH is missing coverage:auto:start / coverage:auto:end markers." >&2
    exit 1
fi

TMP_OUT="$(mktemp)"
trap 'rm -f "$TMP_JSON" "$PREV_PKG_FILE" "$TMP_MD" "$TMP_OUT"' EXIT

awk -v block="$TMP_MD" '
    /<!-- coverage:auto:start -->/ {
        print
        while ((getline line < block) > 0) print line
        skip = 1
        next
    }
    /<!-- coverage:auto:end -->/ {
        skip = 0
        print
        next
    }
    !skip { print }
' "$WRITE_PATH" > "$TMP_OUT"

mv "$TMP_OUT" "$WRITE_PATH"
