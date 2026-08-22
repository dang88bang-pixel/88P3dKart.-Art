#!/bin/bash
# 3dxAgent REAL-CT45P — Deploy + alle Runtime-Berechtigungen aktiv setzen
set -euo pipefail

APK="${1:-}"
PACKAGE="com.example.agent"

if [ -z "$APK" ]; then
  echo "Usage: ./releases/ct45p-deploy.sh path/to/signed.apk"
  echo ""
  echo "Installiert die APK, erteilt ALLE dangerous Permissions und startet die App."
  exit 1
fi

if [ ! -f "$APK" ]; then
  echo "❌ APK nicht gefunden: $APK"
  exit 1
fi

echo "=== Installing $APK ==="
adb install -r -g "$APK"

echo "=== Granting ALL runtime permissions ==="
PERMISSIONS=(
  android.permission.ACCESS_FINE_LOCATION
  android.permission.ACCESS_COARSE_LOCATION
  android.permission.ACCESS_BACKGROUND_LOCATION
  android.permission.BLUETOOTH_SCAN
  android.permission.BLUETOOTH_CONNECT
  android.permission.BLUETOOTH_ADVERTISE
  android.permission.UWB_RANGING
  android.permission.NEARBY_WIFI_DEVICES
  android.permission.POST_NOTIFICATIONS
  android.permission.CAMERA
  android.permission.RECORD_AUDIO
  android.permission.BODY_SENSORS
  android.permission.BODY_SENSORS_BACKGROUND
  android.permission.ACTIVITY_RECOGNITION
  android.permission.READ_MEDIA_IMAGES
  android.permission.READ_MEDIA_VIDEO
  android.permission.READ_MEDIA_AUDIO
  android.permission.READ_EXTERNAL_STORAGE
  android.permission.WRITE_EXTERNAL_STORAGE
  android.permission.NFC
  android.permission.HIGH_SAMPLING_RATE_SENSORS
)

for perm in "${PERMISSIONS[@]}"; do
  if adb shell pm grant "$PACKAGE" "$perm" 2>/dev/null; then
    echo "  ✓ $perm"
  else
    echo "  · $perm (nicht grantbar / nicht deklariert)"
  fi
done

echo "=== Battery optimization exemption ==="
adb shell dumpsys deviceidle whitelist +$PACKAGE 2>/dev/null || true
adb shell cmd appops set $PACKAGE RUN_IN_BACKGROUND allow 2>/dev/null || true
adb shell cmd appops set $PACKAGE RUN_ANY_IN_BACKGROUND allow 2>/dev/null || true
adb shell cmd appops set $PACKAGE START_FOREGROUND allow 2>/dev/null || true

echo "=== Starting app ==="
adb shell am start -n $PACKAGE/.EnrollmentActivity || \
  adb shell am start -n $PACKAGE/.MainActivity

echo ""
echo "✅ Deployed. Permissions aktiv, Dauerbetrieb startet nach dem ersten Launch."
echo "   adb logcat | grep -E 'ThreeAgent|Tactical|MainActivity|BTScan|BleToken'"
