#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "$0")/.." && pwd)"
cd "$ROOT"
python3 -m compileall -q .
python3 - <<'PY'
from pathlib import Path
import zipfile, sys
bad=[]
for p in Path('.').rglob('*'):
    if any(x in p.parts for x in ['.git','__pycache__','build']): continue
    if p.name in {'.env','keystore.properties','credential_master.key','binance_credentials.enc'} or p.suffix in {'.jks','.apk','.aab'}:
        bad.append(str(p))
print('runtime/secrets:', bad or 'CLEAN')
assert not bad
PY
python3 recovery_test.py
printf 'FULL STATIC VERIFICATION: PASS\n'
