"""Canonical application/backend release version."""
from __future__ import annotations
import re
from pathlib import Path

VERSION_FILE = Path(__file__).resolve().parent / "VERSION"
_PATTERN = re.compile(r"^[0-9]+\.[0-9]+\.[0-9]+$")

def read_version(path: Path | str = VERSION_FILE) -> str:
    source = Path(path)
    if not source.is_file():
        raise RuntimeError(f"Canonical version source is missing: {source}")
    values = [line.strip() for line in source.read_text(encoding="utf-8").splitlines() if line.strip()]
    if len(values) != 1 or not _PATTERN.fullmatch(values[0]):
        raise RuntimeError("VERSION must contain exactly one MAJOR.MINOR.PATCH value.")
    return values[0]

APP_VERSION = read_version()
