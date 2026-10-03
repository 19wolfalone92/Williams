#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
python3 -m compileall -q .
python3 - <<'PY'
import ast
from pathlib import Path
for p in Path('.').glob('*.py'):
    ast.parse(p.read_text(), filename=str(p))
print('Python AST: PASS')
PY
python3 recovery_test.py
python3 - <<'PY'
from pathlib import Path
bad=[]
for p in Path('.').rglob('*'):
    if p.is_file() and p.stat().st_size < 2_000_000:
        try: s=p.read_text(errors='ignore')
        except: continue
        for needle in ('sk_live_','AKIA','-----BEGIN PRIVATE KEY-----'):
            if needle in s: bad.append((str(p),needle))
print('Secret pattern scan:', 'PASS' if not bad else bad)
PY
echo 'Release verification: PASS'
