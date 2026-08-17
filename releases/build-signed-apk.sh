#!/bin/bash
# 3dxAgent REAL-CT45P v18.0.0-UNIFIED — Build Signed Release APK
# Run this on a machine with JDK 17 + Android SDK

set -e

echo "════════════════════════════════════════════════════════════════"
echo "  3dxAgent REAL-CT45P v18.0.0-UNIFIED — SIGNED RELEASE BUILD"
echo "════════════════════════════════════════════════════════════════"

if [ ! -d "android-app" ]; then
  echo "❌ Bitte aus dem Repo-Root ausführen"
  exit 1
fi

cd android-app
chmod +x gradlew

echo "→ Preparing signing..."

# Support both environment variables and local keystore
if [ -z "$RELEASE_STORE_FILE" ]; then
  if [ -f "../releases/keystore/release.jks" ]; then
    export RELEASE_STORE_FILE="../releases/keystore/release.jks"
    echo "✅ Using local releases/keystore/release.jks"
  else
    echo "⚠️  No RELEASE_STORE_FILE set and no local keystore found."
    echo "   Building unsigned release APK (will use debug key)."
    echo ""
    echo "   To create a keystore:"
    echo "   mkdir -p ../releases/keystore"
    echo "   keytool -genkey -v -keystore ../releases/keystore/release.jks \\"
    echo "     -alias ct45p-release -keyalg RSA -keysize 2048 -validity 10000"
    echo ""
  fi
fi

echo "→ Building release APK..."
./gradlew clean assembleRelease --stacktrace

APK="app/build/outputs/apk/release/app-release.apk"
if [ -f "$APK" ]; then
  TS=$(date +%Y%m%d-%H%M)
  OUT="../releases/3dxAgent-REAL-CT45P-18.0.0-UNIFIED-signed-$TS.apk"
  mkdir -p ../releases
  cp "$APK" "$OUT"

  echo ""
  echo "✅ BUILD SUCCESSFUL"
  ls -lh "$OUT"
  echo ""
  echo "→ Verifying..."
  ../releases/verify-release.sh "$OUT" || true

  echo ""
  echo "To install on CT45P:"
  echo "  adb install -r $OUT"
else
  echo "❌ APK not found"
  exit 1
fi
