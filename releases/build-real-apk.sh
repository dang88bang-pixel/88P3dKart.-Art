#!/bin/bash
# 3dxAgent REAL-CT45P v18.0.0-UNIFIED — Official Build Script
# Run this on a machine with: JDK 17 + Android SDK (or Android Studio)
set -e

echo "════════════════════════════════════════════════════════════════"
echo "  3dxAgent REAL-CT45P v18.0.0-UNIFIED"
echo "  Alle Branches zusammengeführt + 100% REAL Hardware Chains"
echo "  (IMU, UART/BLE NUS, ADB WiFi, USB OTG, WS Tactical)"
echo "════════════════════════════════════════════════════════════════"

if [ ! -d "android-app" ]; then
  echo "❌ Bitte aus dem Repo-Root (88P3dKart.-Art) ausführen!"
  exit 1
fi

echo "→ JDK prüfen..."
if ! command -v java >/dev/null 2>&1; then
  echo "❌ JDK 17 nicht gefunden. Installiere z.B.:"
  echo "   sudo apt install openjdk-17-jdk"
  echo "   oder: brew install openjdk@17"
  exit 1
fi
java -version

echo "→ In android-app wechseln..."
cd android-app
chmod +x gradlew

echo "→ Clean + REAL Release Build (keine Simulationen)..."
./gradlew clean assembleRelease --stacktrace

APK="app/build/outputs/apk/release/app-release.apk"
if [ -f "$APK" ]; then
  mkdir -p ../releases
  TS=$(date +%Y%m%d-%H%M)
  OUT="../releases/3dxAgent-REAL-CT45P-18.0.0-UNIFIED-$TS.apk"
  cp "$APK" "$OUT"
  echo ""
  echo "✅ ERFOLG! Neue APK erstellt:"
  ls -lh "$OUT"
  echo ""
  echo "Install on CT45P:"
  echo "  adb install -r $OUT"
  echo ""
  echo "Oder per sideload:"
  echo "  adb push $OUT /sdcard/Download/"
  echo "  (dann im Dateimanager installieren)"
else
  echo "⚠️ Build abgeschlossen, aber APK nicht gefunden."
  echo "Prüfe: ls -l app/build/outputs/apk/release/"
  exit 1
fi
