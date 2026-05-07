"""Pytest suite for scripts/render-pmd-report.py.

Exercises the load-bearing layers: parse + sort + summary + render.
Goldens (JSON + MD) are committed alongside the fixture XML; regenerate
them with::

    python3 scripts/render-pmd-report.py \\
        --in scripts/tests/fixtures/sample-pmd.xml \\
        --out-md scripts/tests/fixtures/expected-code-quality.md \\
        --out-json scripts/tests/fixtures/expected-code-quality.json \\
        --ruleset pmd-ruleset.xml

The drift gate is intentionally not unit-tested — it requires a real git
repo to assert against and is covered by the mvn verify smoke + the
real CI run.
"""

from __future__ import annotations

import random
from pathlib import Path

import pytest

from render_pmd_report import (
    Finding,
    compute_summary,
    parse_pmd_xml,
    render_json,
    render_markdown,
    sort_findings,
)

FIXTURES = Path(__file__).resolve().parent / "fixtures"
SAMPLE_XML = FIXTURES / "sample-pmd.xml"
EXPECTED_JSON = FIXTURES / "expected-code-quality.json"
EXPECTED_MD = FIXTURES / "expected-code-quality.md"
RULESET = "pmd-ruleset.xml"


# --- parse -----------------------------------------------------------------


def test_parse_returns_all_findings_and_tool_version():
    findings, version = parse_pmd_xml(SAMPLE_XML)
    assert version == "7.24.0"
    assert len(findings) == 4


def test_parse_normalises_attribute_quirks():
    findings, _ = parse_pmd_xml(SAMPLE_XML)
    f = next(
        x
        for x in findings
        if x.rule == "CyclomaticComplexity" and x.method == "processRequest"
    )
    assert f.ruleset == "Design"
    assert f.priority == 3
    assert f.package == "com.example.sample.auth"
    assert f.klass == "SampleService"
    assert f.file == "src/main/java/com/example/sample/auth/SampleService.java"
    assert f.begin_line == 42
    assert f.end_line == 42
    assert "cyclomatic complexity of 11" in f.message


def test_parse_handles_class_level_finding_without_method():
    findings, _ = parse_pmd_xml(SAMPLE_XML)
    too_many = next(x for x in findings if x.rule == "TooManyMethods")
    assert too_many.method == ""
    assert too_many.klass == "SampleController"


def test_parse_handles_multi_violation_file_element():
    findings, _ = parse_pmd_xml(SAMPLE_XML)
    in_sample_service = [f for f in findings if f.klass == "SampleService"]
    assert len(in_sample_service) == 2
    assert {f.method for f in in_sample_service} == {"processRequest", "validateInput"}


def test_parse_extracts_repo_relative_path_from_absolute():
    findings, _ = parse_pmd_xml(SAMPLE_XML)
    for f in findings:
        assert f.file.startswith("src/main/java/")
        assert "/abs/path/" not in f.file


def test_parse_missing_file_raises_clear_error(tmp_path):
    nonexistent = tmp_path / "no-such.xml"
    with pytest.raises(SystemExit) as ei:
        parse_pmd_xml(nonexistent)
    assert "not found" in str(ei.value)


# --- sort ------------------------------------------------------------------


def test_sort_is_deterministic_under_arbitrary_input_order():
    findings, _ = parse_pmd_xml(SAMPLE_XML)
    a = list(findings)
    random.Random(1).shuffle(a)
    b = list(findings)
    random.Random(2).shuffle(b)
    assert sort_findings(a) == sort_findings(b) == sort_findings(findings)


def test_sort_tiebreaker_is_begin_line():
    later = Finding(
        rule="R", ruleset="x", priority=3, package="p", klass="C",
        method="m", file="f.java", begin_line=20, end_line=20, message="",
    )
    earlier = Finding(
        rule="R", ruleset="x", priority=3, package="p", klass="C",
        method="m", file="f.java", begin_line=10, end_line=10, message="",
    )
    assert sort_findings([later, earlier]) == [earlier, later]


# --- summary ---------------------------------------------------------------


def test_compute_summary_shape_for_fixture():
    findings, version = parse_pmd_xml(SAMPLE_XML)
    summary = compute_summary(findings, version, RULESET)
    assert summary["tool"] == "pmd"
    assert summary["tool_version"] == "7.24.0"
    assert summary["ruleset"] == RULESET
    assert summary["total_findings"] == 4


def test_compute_summary_handles_empty_input():
    summary = compute_summary([], "7.24.0", RULESET)
    assert summary["total_findings"] == 0
    assert summary["by_priority"] == {}


# --- render (golden files) -------------------------------------------------


def test_render_json_matches_golden():
    findings, version = parse_pmd_xml(SAMPLE_XML)
    findings = sort_findings(findings)
    summary = compute_summary(findings, version, RULESET)
    assert render_json(findings, summary) == EXPECTED_JSON.read_text(encoding="utf-8")


def test_render_markdown_matches_golden():
    findings, version = parse_pmd_xml(SAMPLE_XML)
    findings = sort_findings(findings)
    summary = compute_summary(findings, version, RULESET)
    assert render_markdown(findings, summary) == EXPECTED_MD.read_text(encoding="utf-8")
