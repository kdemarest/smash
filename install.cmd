@echo off
echo Installing smash...
adb install -r app\build\outputs\apk\debug\app-debug.apk
if %ERRORLEVEL% EQU 0 goto :success
goto :failure

:success
echo.
echo Install successful!
echo.
echo Granting permissions...
adb shell pm grant com.smash.app android.permission.RECEIVE_SMS
adb shell pm grant com.smash.app android.permission.SEND_SMS
adb shell pm grant com.smash.app android.permission.READ_SMS
adb shell pm grant com.smash.app android.permission.READ_PHONE_STATE
adb shell pm grant com.smash.app android.permission.READ_CONTACTS
adb shell pm grant com.smash.app android.permission.POST_NOTIFICATIONS
echo.
echo Launch the app - it will prompt for:
echo   - Default SMS app
echo   - Battery optimization exemption
goto :end

:failure
echo.
echo Install failed! Make sure:
echo   - Device is connected (adb devices)
echo   - USB debugging is enabled
echo   - APK exists (run build.cmd first)
goto :end

:end
