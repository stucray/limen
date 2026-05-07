"""Make `render-pmd-report.py` (hyphenated CLI script) importable as
`render_pmd_report` so tests can call its parse/sort/render functions
directly. Hyphens are valid in shell-script names but illegal in Python
module names; importlib bridges the gap without forcing a rename of
the CLI script.
"""

from __future__ import annotations

import importlib.util
import sys
from pathlib import Path

_SCRIPT_PATH = Path(__file__).resolve().parent.parent / "render-pmd-report.py"
_spec = importlib.util.spec_from_file_location("render_pmd_report", _SCRIPT_PATH)
assert _spec is not None and _spec.loader is not None, _SCRIPT_PATH
_module = importlib.util.module_from_spec(_spec)
sys.modules["render_pmd_report"] = _module
_spec.loader.exec_module(_module)
