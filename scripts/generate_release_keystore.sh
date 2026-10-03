#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
KEYSTORE="${1:-release-keystore.jks}"
ALIAS="${2:-williams-release}"
if [[ -e "$KEYSTORE" ]]; then
  echo "Keystore already exists: $KEYSTORE"
  exit 1
fi
read -r -s -p "Keystore password: " STORE_PASSWORD; echo
read -r -s -p "Confirm password: " STORE_PASSWORD2; echo
[[ "$STORE_PASSWORD" == "$STORE_PASSWORD2" ]] || { echo "Passwords do not match"; exit 1; }
keytool -genkeypair -v -keystore "$KEYSTORE" -alias "$ALIAS" -keyalg RSA -keysize 4096 -validity 10000 -storepass "$STORE_PASSWORD" -keypass "$STORE_PASSWORD" -dname "CN=Williams Bot, OU=Mobile, O=Williams Bot, L=, ST=, C=BY"
cat > keystore.properties <<PROP
storeFile=$KEYSTORE
storePassword=$STORE_PASSWORD
keyAlias=$ALIAS
keyPassword=$STORE_PASSWORD
PROP
chmod 600 keystore.properties "$KEYSTORE"
echo "Created $KEYSTORE and local keystore.properties. Keep both private and backed up."
