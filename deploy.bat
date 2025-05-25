@echo off
REM Syncy P2P - Quick Deployment Script for Testing
REM This script builds the APK and provides instructions for installation

echo ======================================
echo Syncy P2P - Build and Deploy Script
echo ======================================
echo.

echo Building debug APK...
call gradlew assembleDebug

if %ERRORLEVEL% NEQ 0 (
    echo.
    echo ❌ Build failed! Please check the error messages above.
    pause
    exit /b 1
)

echo.
echo ✅ Build successful!
echo.
echo APK Location: app\build\outputs\apk\debug\app-debug.apk
echo.
echo ======================================
echo Installation Instructions:
echo ======================================
echo.
echo 1. Connect your Android device(s) via USB
echo 2. Enable USB debugging in Developer Options
echo 3. Run the following command for each device:
echo    adb install app\build\outputs\apk\debug\app-debug.apk
echo.
echo Alternative: Copy the APK to your device and install manually
echo.
echo ======================================
echo Testing Checklist:
echo ======================================
echo.
echo ☐ Install on at least 2 Android devices
echo ☐ Grant all permissions when prompted:
echo   - Location (required for Wi-Fi Direct)
echo   - Nearby devices
echo   - Storage (for file transfers)
echo   - Notifications
echo ☐ Ensure Wi-Fi is enabled on both devices
echo ☐ Keep devices within 50-100 meters of each other
echo.
echo ======================================
echo Quick Test Steps:
echo ======================================
echo.
echo 1. Open app on both devices
echo 2. Tap "Discover Peers" on both devices
echo 3. Select a peer to connect
echo 4. Send a test message
echo 5. Try sending a file
echo.
echo For detailed testing instructions, see TESTING_GUIDE.md
echo.
echo ======================================
echo Troubleshooting:
echo ======================================
echo.
echo • If devices don't discover each other:
echo   - Check location services are enabled
echo   - Restart Wi-Fi on both devices
echo   - Clear app data and retry
echo.
echo • For connection issues:
echo   - Ensure both devices accept connection
echo   - Try moving devices closer together
echo   - Restart the app
echo.
echo • View logs with: adb logcat ^| grep "SyncyP2P"
echo.
pause
