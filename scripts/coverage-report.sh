#!/usr/bin/env bash
# Emit the "Headline numbers" + "Per-package summary" markdown tables for
# docs/test-coverage.md from a JaCoCo CSV. Δ columns compare against the
# PR #59 baseline (commit e2fcdb0).
#
# Side effect: appends a JSON Lines snapshot to docs/test-coverage-history.jsonl
# (resolved relative to this script). The trailing "## Δ from last run" block
# on stdout is for the chat summary only — do NOT paste it into
# docs/test-coverage.md.
#
# Usage:
#   ./mvnw clean test                                    # regenerate target/site/jacoco/jacoco.csv
#   scripts/coverage-report.sh                           # print tables to stdout
#   scripts/coverage-report.sh --tests 245               # also record test count in JSONL
#   scripts/coverage-report.sh path/to/jacoco.csv        # custom CSV path

set -euo pipefail

CSV=""
TESTS=""
while [[ $# -gt 0 ]]; do
    case "$1" in
        --tests)     TESTS="${2:-}"; shift 2 ;;
        --tests=*)   TESTS="${1#*=}"; shift ;;
        -h|--help)
            sed -n '2,16p' "$0"
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
HISTORY_FILE="$REPO_ROOT/docs/test-coverage-history.jsonl"

DATE_UTC="$(date -u +%Y-%m-%dT%H:%M:%SZ)"
SHA="$(git -C "$REPO_ROOT" rev-parse --short HEAD 2>/dev/null || echo unknown)"

# Read prior run's headline totals (if any) for the Δ-from-last-run block.
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
trap 'rm -f "$TMP_JSON"' EXIT

awk -F, \
    -v date_utc="$DATE_UTC" \
    -v sha="$SHA" \
    -v tests="${TESTS:-}" \
    -v prev_instr="$PREV_INSTR" \
    -v prev_branch="$PREV_BRANCH" \
    -v prev_line_v="$PREV_LINE" \
    -v prev_method="$PREV_METHOD" \
    -v jsonpath="$TMP_JSON" \
    '
BEGIN {
    # Baseline snapshot: commit e2fcdb0 (PR #59 initial JaCoCo run).
    base_instr  = 84.0
    base_branch = 70.0
    base_line   = 86.1
    base_method = 89.1

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

    print "## Headline numbers"; print ""
    print "| Metric       | Coverage | Δ from baseline | Covered / Total |"
    print "|--------------|---------:|----------------:|----------------:|"
    printf "| Instructions | %.1f %% | %s | %s / %s |\n", ip, delta(ip - base_instr), comma(ic), comma(im + ic)
    printf "| Branches     | %.1f %% | %s | %s / %s |\n", bp, delta(bp - base_branch), comma(bc), comma(bm + bc)
    printf "| Lines        | %.1f %% | %s | %s / %s |\n", lp, delta(lp - base_line),   comma(lc), comma(lm + lc)
    printf "| Methods      | %.1f %% | %s | %s / %s |\n", mp, delta(mp - base_method), comma(mc), comma(mm + mc)

    print ""; print "## Per-package summary"; print ""
    print "Sorted by line coverage, weakest first. Δ Line column compares each package against the PR #59 baseline."
    print ""
    print "| Package | Line % | Δ Line | Branch % | Method % | Missed lines |"
    print "|---------|-------:|-------:|---------:|---------:|-------------:|"

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
        printf "| %s | %.1f %% | %s | %s | %.1f %% | %d |\n",
            p, lp_pkg, delta(lp_pkg - bv), bp_pkg, mp_pkg, lm_p[p]
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

    # Δ-from-last-run block. Chat-summary only — do not paste into the doc.
    print ""
    print "## Δ from last run"
    print ""
    if (prev_instr == "") {
        print "_First snapshot — no prior run to compare against._"
    } else {
        print "| Metric       | Coverage | Δ from last run |"
        print "|--------------|---------:|----------------:|"
        printf "| Instructions | %.1f %% | %s |\n", ip, delta(round1(ip) - prev_instr)
        printf "| Branches     | %.1f %% | %s |\n", bp, delta(round1(bp) - prev_branch)
        printf "| Lines        | %.1f %% | %s |\n", lp, delta(round1(lp) - prev_line_v)
        printf "| Methods      | %.1f %% | %s |\n", mp, delta(round1(mp) - prev_method)
    }
}
' "$CSV"

mkdir -p "$(dirname "$HISTORY_FILE")"
cat "$TMP_JSON" >> "$HISTORY_FILE"
