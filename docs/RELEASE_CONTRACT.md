# Release version and execution-mode contract

## Single version source

The repository-root VERSION file is the only release-version value. Change it once for each release. Python backend version_contract.py reads it; backend health, status, scanner and settings diagnostics expose that value. Gradle reads the same file for versionName. The Android interface and native diagnostics display BuildConfig.VERSION_NAME, including the debug suffix on debug builds.

ci_release_gate.py checks source contracts before compilation and tests. After APK builds, verify_apk_version.py reads embedded package metadata with Android aapt and compares it with VERSION. No generated version file must be committed. A missing, malformed, duplicated, or disconnected source fails closed.

## Binance mode and secrets

The committed .env.example selects Binance Spot Testnet, disables Mainnet permission, and keeps DRY_RUN enabled. Backend configuration and trading start are blocked if TESTNET is absent, the mode does not match the configured client, or API credentials are missing. Mainnet additionally requires explicit TESTNET=false, ALLOW_LIVE=true, and DRY_RUN=false; none of these live-enabling values are defaults.

CI uses dedicated Testnet secrets for read-only preflight. Secret values must not be printed or added to artifacts. Python and Android endpoints remain Spot-only for this release candidate; Futures execution is a separate scope.

## Gate scope

A passing gate verifies version/configuration consistency and selected static contracts only. It does not prove strategy equivalence, profitability, safe order protection, recovery correctness, or readiness for live trading. CI gate steps do not submit orders.
