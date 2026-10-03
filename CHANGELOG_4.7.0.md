# Williams Bot 4.8.0

## 24/7 VPS architecture
- Added Docker deployment for the Python backend.
- Added Caddy HTTPS/WSS reverse proxy.
- Added automatic restart and optional automatic bot start after VPS restart.
- Added encrypted-at-rest Binance credential storage on the VPS.
- Binance credentials are no longer persisted on the Android device.
- Added VPS backup/update scripts.

## Android
- Release versionCode 8 / versionName 4.8.0.
- Release signing can be generated automatically by the local build script.
- Remote backend is expected to use HTTPS/WSS.

## Safety
- TESTNET remains the default.
- LIVE remains disabled unless `ALLOW_LIVE=true` is explicitly enabled.
