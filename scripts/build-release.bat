@echo off
rem Build the release APK: compile relay -> stage into jniLibs -> assembleRelease -> zipalign -> sign.
rem
rem Required environment variables (never commit real values):
rem   KEYSTORE   path to the release keystore (.jks)
rem   KS_PASS    keystore password
rem   KEY_PASS   key password (defaults to KS_PASS)
rem Optional:
rem   KS_ALIAS   key alias
rem   OUT        output APK path (default: %~dp0..\carvpn.apk)
setlocal
cd /d "%~dp0.."

if "%KEYSTORE%"=="" ( echo KEYSTORE is required & exit /b 1 )
if "%KS_PASS%"==""  ( echo KS_PASS is required & exit /b 1 )
if "%KEY_PASS%"=="" set "KEY_PASS=%KS_PASS%"
if "%OUT%"=="" set "OUT=%CD%\carvpn.apk"
if "%ANDROID_HOME%"=="" set "ANDROID_HOME=%ANDROID_SDK_ROOT%"
if "%ANDROID_HOME%"=="" ( echo ANDROID_HOME is required & exit /b 1 )
if "%BUILD_TOOLS%"=="" set "BUILD_TOOLS=%ANDROID_HOME%\build-tools\34.0.0"

echo [1/5] assembleDebug ^(build native relay^)
call gradlew.bat assembleDebug --console=plain || goto :fail

echo [2/5] stage relay executable into jniLibs
if not exist "app\src\main\jniLibs\armeabi-v7a" mkdir "app\src\main\jniLibs\armeabi-v7a"
for /d %%D in ("app\build\intermediates\cxx\Debug\*") do (
  if exist "%%D\obj\local\armeabi-v7a\carvpnrelay" (
    copy /y "%%D\obj\local\armeabi-v7a\carvpnrelay" "app\src\main\jniLibs\armeabi-v7a\libcarvpnrelay.so" >nul
  )
)

echo [3/5] assembleRelease
call gradlew.bat assembleRelease --console=plain || goto :fail

echo [4/5] zipalign
"%BUILD_TOOLS%\zipalign.exe" -p -f 4 "app\build\outputs\apk\release\app-release-unsigned.apk" "%OUT%.unsigned" || goto :fail

echo [5/5] sign ^(v1 + v2^)
set "ALIAS_ARG="
if not "%KS_ALIAS%"=="" set "ALIAS_ARG=--ks-key-alias %KS_ALIAS%"
call "%BUILD_TOOLS%\apksigner.bat" sign --ks "%KEYSTORE%" %ALIAS_ARG% ^
  --ks-pass "pass:%KS_PASS%" --key-pass "pass:%KEY_PASS%" --min-sdk-version 21 ^
  --v1-signing-enabled true --v2-signing-enabled true --v3-signing-enabled false ^
  --out "%OUT%" "%OUT%.unsigned" || goto :fail
del /q "%OUT%.unsigned" 2>nul

echo RELEASE OK: %OUT%
exit /b 0

:fail
echo RELEASE FAILED
exit /b 1
