#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

KEYSTORE="${1:-release-keystore.jks}"
ALIAS="${2:-williams-release}"
PROPS="keystore.properties"

if [[ -f "$KEYSTORE" || -f "$PROPS" ]]; then
  echo "Release signing already configured. Nothing to do."
  exit 0
fi

command -v keytool >/dev/null 2>&1 || { echo "JDK keytool is required."; exit 2; }

PASSWORD="$(python3 - <<'PY'
import secrets
print(secrets.token_urlsafe(32))
PY
)"

keytool -genkeypair -noprompt \
  -keystore "$KEYSTORE" \
  -storepass "$PASSWORD" \
  -keypass "$PASSWORD" \
  -alias "$ALIAS" \
  -keyalg RSA \
  -keysize 4096 \
  -validity 10000 \
  -dname "CN=Williams Bot, OU=Mobile, O=Williams Bot, C=BY"

cat > "$PROPS" <<PROP
storeFile=$KEYSTORE
storePassword=$PASSWORD
keyAlias=$ALIAS
keyPassword=$PASSWORD
PROP
chmod 600 "$KEYSTORE" "$PROPS"

echo "Release keystore created automatically: $KEYSTORE"
echo "IMPORTANT: back up both $KEYSTORE and $PROPS securely. Do not commit them."
