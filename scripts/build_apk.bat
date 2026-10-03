@echo off
setlocal
cd /d "%~dp0.."
where gradle >nul 2>nul
if %ERRORLEVEL%==0 (
  set GRADLE=gradle
) else if exist gradlew.bat (
  set GRADLE=gradlew.bat
) else (
  echo Gradle is not installed and the binary Gradle Wrapper is not bundled.
  echo Open this project in Android Studio, or install Gradle 8.9.
  exit /b 2
)
%GRADLE% --version
%GRADLE% :app:assembleDebug
if exist keystore.properties (
  %GRADLE% :app:assembleRelease
  echo Release APK: app\build\outputs\apk\release\app-release.apk
) else (
  echo Debug APK: app\build\outputs\apk\debug\app-debug.apk
  echo No keystore.properties found; release signing was skipped.
)
