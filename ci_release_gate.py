#!/usr/bin/env python3
"""Fail-closed Williams release contract gate."""
from __future__ import annotations
import sys
from pathlib import Path
from release_contract import ReleaseContractError, validate_release_contract

def main() -> int:
    try:
        version = validate_release_contract(Path(__file__).resolve().parent)
    except (ReleaseContractError, OSError, SyntaxError, ValueError) as exc:
        print(f"WILLIAMS RELEASE CONTRACT GATE: FAIL: {exc}", file=sys.stderr)
        return 1
    print(f"WILLIAMS RELEASE CONTRACT GATE: PASS (VERSION={version})")
    return 0

if __name__ == "__main__":
    raise SystemExit(main())
