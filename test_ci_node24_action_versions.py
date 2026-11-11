"""Keep GitHub-hosted CI on supported Node.js 24 action majors.

The 2026-11-11 APK run raised two emulator-job annotations from
actions/download-artifact@v4 and actions/upload-artifact@v4. It also
surfaced Node 20 annotations in Python/campaign jobs. These tests
prevent the same warning from returning after workflow refactoring.
They do NOT suppress genuine Binance Demo credential / HTTP 451 gates.
"""
from pathlib import Path
import re

WORKFLOWS = Path(__file__).resolve().parent / ".github" / "workflows"
MIN_NODE24_MAJOR = {
    "checkout": 5,
    "setup-java": 6,
    "setup-python": 6,
    "download-artifact": 7,
    "upload-artifact": 6,
}
ACTION_REF = re.compile(
    r"^\s*-?\s*uses:\s*actions/("
    + "|".join(map(re.escape, MIN_NODE24_MAJOR))
    + r")@v(\d+)(?:\b|$)",
    flags=re.MULTILINE,
)


def test_builtin_actions_do_not_reintroduce_node20_warning():
    files = sorted(WORKFLOWS.glob("*.yml"))
    assert files, "Missing GitHub Actions workflow files"
    seen = set()
    violations = []
    for file in files:
        for match in ACTION_REF.finditer(file.read_text(encoding="utf-8")):
            action, major = match.groups()
            seen.add(action)
            if int(major) < MIN_NODE24_MAJOR[action]:
                violations.append(f"{file.name}: actions/{action}@v{major} uses deprecated Node 20")
    assert seen == set(MIN_NODE24_MAJOR), f"Expected action coverage: {set(MIN_NODE24_MAJOR) - seen}"
    assert not violations, "\n".join(violations)
