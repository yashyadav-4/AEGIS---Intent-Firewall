#!/bin/bash
# AEGIS-ZERO: Friction UI Hardware Test Script

echo "Checking for connected emulators/devices..."
DEVICE_COUNT=$(adb devices | grep -v "List" | grep "device$" | wc -l)

if [ "$DEVICE_COUNT" -eq 0 ]; then
    echo "❌ CRITICAL ERROR: No Android Emulator or Physical Device connected."
    echo "Please start an emulator via Android Studio or plug in a debugging-enabled device, then re-run this script."
    exit 1
fi

echo "✅ Device found. Assembling Debug APK..."
cd android && ./gradlew installDebug

echo "✅ Deployment successful. Triggering Aegis-Zero High-Friction Warning UI..."
# Use ADB to forcefully start the FrictionWarningActivity
adb shell am start -n com.intentfirewall/.FrictionWarningActivity -a android.intent.action.MAIN -c android.intent.category.LAUNCHER

echo "⚠️ Check your emulator screen NOW."
echo "You should see the unbypassable 5-second countdown on the 'Proceed anyway' button."
echo "Attempt to press the hardware back button or swipe it away (it should be blocked)."
