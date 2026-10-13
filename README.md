# Williams Bot

Williams Bot contains a Python Spot backend and an Android runtime/cockpit. The project remains under staged audit; a passing CI gate is not authorization for live trading.

## Release contract

The canonical backend/APK version is maintained in the repository-root VERSION file. See docs/RELEASE_CONTRACT.md for version propagation, embedded APK metadata checks, and safe Binance mode requirements.

The checked-in configuration template defaults to Binance Spot Testnet with Mainnet disabled and dry-run enabled. Never commit API credentials, private keys, or populated .env files.
