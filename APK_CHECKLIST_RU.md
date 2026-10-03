# APK preflight checklist

- [x] compileSdk 35 / targetSdk 35
- [x] minSdk 26
- [x] AGP 8.7.3 + Kotlin 2.0.21
- [x] Compose compiler plugin configured
- [x] Java/Kotlin target 17
- [x] debug/release build types
- [x] release cleartext HTTP disabled
- [x] debug local HTTP allowed for Testnet/LAN
- [x] release signing is externalized to local keystore.properties
- [x] keystore files ignored by Git
- [x] APK/AAB outputs ignored by Git
- [x] GitHub Actions debug APK workflow
- [x] build instructions for Windows/Linux/macOS via Android Studio
- [x] no Binance credentials embedded in source
- [ ] actual Android SDK/Gradle build in this execution environment (not available)
- [ ] real-device install test
- [ ] Binance Testnet end-to-end order test
