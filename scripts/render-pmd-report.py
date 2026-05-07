#!/usr/bin/env python3
"""Render PMD XML findings as committed Markdown + JSON reports.

Reads PMD's XML report (``target/pmd.xml``), normalises every violation into
a ``Finding`` dataclass, and writes:

* ``--out-md``  — human digest: headlines, per-package, per-rule, top-N hot-spots.
* ``--out-json`` — machine-readable: ``summary`` object + flat ``findings`` array.

Both files are written via tmp + ``os.replace`` so a crash mid-write never
leaves a half-written file in the working tree. Sort key is
``(rule, package, class, method, begin_line)``. ``begin_line`` is a tiebreaker
only — adding a method earlier in a file shifts ``begin_line`` on later
findings even though no semantic change occurred, and we don't want the
JSON diff to churn for that.

Usage::

    python3 scripts/render-pmd-report.py \\
        --in target/pmd.xml \\
        --out-md docs/reports/code-quality.md \\
        --out-json docs/reports/code-quality.json

Stdlib only — no ``pip install`` required.
"""

from __future__ import annotations

import argparse
import json
import os
import sys
import xml.etree.ElementTree as ET
from collections import Counter
from dataclasses import dataclass
from pathlib import Path
from typing import Iterable

PMD_NS = "{http://pmd.sourceforge.net/report/2.0.0}"
HOT_SPOT_LIMIT = 10
# Reports live at docs/reports/<file>.md, two levels below the repo root, so
# relative GitHub source links use this prefix. If the report path moves,
# update this constant.
LINK_PREFIX = "../../"


@dataclass(frozen=True)
class Finding:
    rule: str
    ruleset: str
    priority: int
    package: str
    klass: str  # serialised as "class" in JSON; "klass" avoids the keyword
    method: str  # "" if class-level
    file: str  # repo-relative path (e.g. "src/main/java/...")
    begin_line: int
    end_line: int
    message: str


def parse_pmd_xml(xml_path: Path) -> tuple[list[Finding], str]:
    """Return ``(findings, tool_version)`` parsed from ``xml_path``.

    Raises ``SystemExit`` with a clear message if the file is missing,
    so the failure mode is obvious rather than an opaque parse error.
    """
    if not xml_path.is_file():
        raise SystemExit(
            f"Error: {xml_path} not found. Run `mvn pmd:pmd` (or `mvn verify`) first."
        )
    tree = ET.parse(xml_path)
    root = tree.getroot()
    tool_version = root.attrib.get("version", "")
    findings: list[Finding] = []
    for file_el in root.findall(f"{PMD_NS}file"):
        rel_path = _to_repo_relative(file_el.attrib.get("name", ""))
        for v in file_el.findall(f"{PMD_NS}violation"):
            findings.append(
                Finding(
                    rule=v.attrib.get("rule", ""),
                    ruleset=v.attrib.get("ruleset", ""),
                    priority=int(v.attrib.get("priority", "0")),
                    package=v.attrib.get("package", ""),
                    klass=v.attrib.get("class", ""),
                    method=v.attrib.get("method", ""),
                    file=rel_path,
                    begin_line=int(v.attrib.get("beginline", "0")),
                    end_line=int(v.attrib.get("endline", "0")),
                    message=(v.text or "").strip(),
                )
            )
    return findings, tool_version


def _to_repo_relative(path: str) -> str:
    if not path:
        return ""
    idx = path.find("/src/")
    if idx >= 0:
        return path[idx + 1 :]
    return path


def sort_findings(findings: Iterable[Finding]) -> list[Finding]:
    return sorted(
        findings,
        key=lambda f: (f.rule, f.package, f.klass, f.method, f.begin_line),
    )


def compute_summary(
    findings: list[Finding], tool_version: str, ruleset_path: str
) -> dict:
    by_priority: Counter[int] = Counter(f.priority for f in findings)
    return {
        "tool": "pmd",
        "tool_version": tool_version,
        "ruleset": ruleset_path,
        "total_findings": len(findings),
        "by_priority": {str(k): by_priority[k] for k in sorted(by_priority)},
    }


def render_json(findings: list[Finding], summary: dict) -> str:
    payload = {
        "summary": summary,
        "findings": [_finding_to_dict(f) for f in findings],
    }
    return json.dumps(payload, indent=2) + "\n"


def _finding_to_dict(f: Finding) -> dict:
    return {
        "rule": f.rule,
        "ruleset": f.ruleset,
        "priority": f.priority,
        "package": f.package,
        "class": f.klass,
        "method": f.method,
        "file": f.file,
        "begin_line": f.begin_line,
        "end_line": f.end_line,
        "message": f.message,
    }


def render_markdown(findings: list[Finding], summary: dict) -> str:
    lines: list[str] = []
    lines.append("# Code Quality Snapshot")
    lines.append("")
    lines.append(
        f"**Tool:** PMD {summary['tool_version']} · "
        f"**Ruleset:** [`{summary['ruleset']}`]({LINK_PREFIX}{summary['ruleset']}) · "
        f"**Total findings:** {summary['total_findings']}"
    )
    lines.append("")
    lines.append(
        "Auto-generated by `scripts/render-pmd-report.py` during `mvn verify`. "
        "See [`docs/process/code-quality.md`](../process/code-quality.md) for the "
        "regen flow and [`code-quality.json`](code-quality.json) for the "
        "machine-readable form."
    )
    lines.append("")

    lines.append("## Headline numbers")
    lines.append("")
    if not findings:
        lines.append("Zero findings under the current ruleset.")
        lines.append("")
    else:
        lines.append("| Priority | Count |")
        lines.append("|---------:|------:|")
        for prio_str, count in summary["by_priority"].items():
            lines.append(f"| {prio_str} | {count} |")
        lines.append(f"| **Total** | **{summary['total_findings']}** |")
        lines.append("")

    lines.append("## Per-package summary")
    lines.append("")
    if findings:
        by_pkg: Counter[str] = Counter(f.package for f in findings)
        lines.append("Sorted highest-count first.")
        lines.append("")
        lines.append("| Package | Findings |")
        lines.append("|---------|---------:|")
        for pkg, count in sorted(by_pkg.items(), key=lambda kv: (-kv[1], kv[0])):
            lines.append(f"| `{pkg}` | {count} |")
        lines.append("")
    else:
        lines.append("_No findings._")
        lines.append("")

    lines.append("## Per-rule summary")
    lines.append("")
    if findings:
        by_rule: Counter[str] = Counter(f.rule for f in findings)
        lines.append("| Rule | Findings |")
        lines.append("|------|---------:|")
        for rule, count in sorted(by_rule.items(), key=lambda kv: (-kv[1], kv[0])):
            lines.append(f"| `{rule}` | {count} |")
        lines.append("")
    else:
        lines.append("_No findings._")
        lines.append("")

    lines.append(f"## Top {HOT_SPOT_LIMIT} hot-spots")
    lines.append("")
    if findings:
        # Lower PMD priority = more severe. Then larger line-span first
        # (bigger refactor target), then alphabetical for stable ordering.
        hot = sorted(
            findings,
            key=lambda f: (
                f.priority,
                -(f.end_line - f.begin_line),
                f.package,
                f.klass,
                f.method,
            ),
        )[:HOT_SPOT_LIMIT]
        lines.append(
            "Sorted by PMD priority (lower = more severe), then by line-span."
        )
        lines.append("")
        lines.append("| Rule | Location | Method | Message |")
        lines.append("|------|----------|--------|---------|")
        for f in hot:
            lines.append(
                f"| `{_md(f.rule)}` | {_format_location(f)} "
                f"| {_md(f.method) or '—'} | {_md(f.message)} |"
            )
        lines.append("")
    else:
        lines.append("_No findings._")
        lines.append("")

    lines.append("## Re-running this report")
    lines.append("")
    lines.append("```sh")
    lines.append(
        "mvn verify   # regenerates target/pmd.xml + docs/reports/code-quality.{md,json}"
    )
    lines.append("```")
    lines.append("")
    lines.append("PMD's interactive HTML drilldown:")
    lines.append("")
    lines.append("```sh")
    lines.append("mvn pmd:pmd -Dformat=html   # writes target/pmd.html (gitignored)")
    lines.append("```")
    lines.append("")

    return "\n".join(lines)


def _md(s: str) -> str:
    return s.replace("|", "\\|").replace("\n", " ").strip()


def _format_location(f: Finding) -> str:
    href = f"{LINK_PREFIX}{f.file}#L{f.begin_line}"
    label = f"{f.klass}.java#L{f.begin_line}" if f.klass else f"{f.file}#L{f.begin_line}"
    return f"[`{label}`]({href})"


def atomic_write(path: Path, content: str) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    tmp = path.with_suffix(path.suffix + ".tmp")
    tmp.write_text(content, encoding="utf-8")
    os.replace(tmp, path)


def main() -> int:
    parser = argparse.ArgumentParser(
        description=__doc__,
        formatter_class=argparse.RawDescriptionHelpFormatter,
    )
    parser.add_argument(
        "--in",
        dest="in_path",
        required=True,
        help="Path to PMD XML input (e.g. target/pmd.xml)",
    )
    parser.add_argument(
        "--out-md", dest="out_md", required=True, help="Output Markdown path"
    )
    parser.add_argument(
        "--out-json", dest="out_json", required=True, help="Output JSON path"
    )
    parser.add_argument(
        "--ruleset",
        default="pmd-ruleset.xml",
        help="Ruleset path recorded in the summary (default: pmd-ruleset.xml)",
    )
    args = parser.parse_args()

    in_path = Path(args.in_path)
    out_md = Path(args.out_md)
    out_json = Path(args.out_json)

    findings, tool_version = parse_pmd_xml(in_path)
    findings = sort_findings(findings)
    summary = compute_summary(findings, tool_version, args.ruleset)

    atomic_write(out_json, render_json(findings, summary))
    atomic_write(out_md, render_markdown(findings, summary))
    return 0


if __name__ == "__main__":
    sys.exit(main())
