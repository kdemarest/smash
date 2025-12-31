@echo off
echo Building smash...
call gradlew.bat assembleDebug
if %ERRORLEVEL% EQU 0 (
    echo.
    echo Build successful!
    echo APK: app\build\outputs\apk\debug\app-debug.apk
) else (
    echo.
    echo Build failed!
)
