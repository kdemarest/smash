@echo off
echo Installing smash...
adb install -r app\build\outputs\apk\debug\app-debug.apk
if %ERRORLEVEL% EQU 0 (
    echo.
    echo Install successful!
    echo.
    echo Grant permissions with:
    echo   adb shell pm grant com.smash.app android.permission.RECEIVE_SMS
    echo   adb shell pm grant com.smash.app android.permission.SEND_SMS
    echo   adb shell pm grant com.smash.app android.permission.READ_SMS
    echo   adb shell pm grant com.smash.app android.permission.READ_PHONE_STATE
    echo   adb shell pm grant com.smash.app android.permission.POST_NOTIFICATIONS
) else (
    echo.
    echo Install failed! Make sure:
    echo   - Device is connected (adb devices)
    echo   - USB debugging is enabled
    echo   - APK exists (run build.cmd first)
)
