#!/bin/bash
# 3dxAgent REAL-CT45P — Quick Deploy Helper for real device

set -e

APK="$1"

if [ -z "$APK" ]; then
  echo "Usage: ./releases/ct45p-deploy.sh path/to/signed.apk"
  echo ""
  echo "This script:"
  echo "  1. Installs the APK"
  echo "  2. Grants common permissions needed by 3dxAgent"
  echo "  3. Starts the app"
  exit 1
fi

echo "=== Installing $APK ==="
adb install -r "$APK"

PACKAGE="com.example.agent"

echo "=== Granting permissions ==="
adb shell pm grant $PACKAGE android.permission.ACCESS_FINE_LOCATION 2>/dev/null || true
adb shell pm grant $PACKAGE android.permission.ACCESS_BACKGROUND_LOCATION 2>/dev/null || true
adb shell pm grant $PACKAGE android.permission.BLUETOOTH_CONNECT 2>/dev/null || true
adb shell pm grant $PACKAGE android.permission.BLUETOOTH_SCAN 2>/dev/null || true
adb shell pm grant $PACKAGE android.permission.POST_NOTIFICATIONS 2>/dev/null || true
adb shell pm grant $PACKAGE android.permission.FOREGROUND_SERVICE 2>/dev/null || true

echo "=== Starting app ==="
adb shell am start -n $PACKAGE/.MainActivity

echo ""
echo "✅ Deployed. Check logcat for 'TacticalForegroundService' and real sensor data."
echo "   adb logcat | grep -E 'Tactical|Polar|Garmin|Workshop|Uwb'"
