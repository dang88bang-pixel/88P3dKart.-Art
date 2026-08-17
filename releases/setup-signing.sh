#!/bin/bash
# Helper script to prepare release signing locally or in CI

set -e
echo "=== 3dxAgent REAL-CT45P Release Signing Setup ==="

KEYSTORE_DIR="android-app/keystore"
mkdir -p "$KEYSTORE_DIR"

if [ -z "$1" ]; then
  echo "Usage: ./releases/setup-signing.sh your-keystore.jks"
  echo ""
  echo "Or set these environment variables for CI:"
  echo "  RELEASE_STORE_FILE=..."
  echo "  RELEASE_STORE_PASSWORD=..."
  echo "  RELEASE_KEY_ALIAS=..."
  echo "  RELEASE_KEY_PASSWORD=..."
  exit 1
fi

KS_FILE="$1"
cp "$KS_FILE" "$KEYSTORE_DIR/release.jks"

echo "Keystore copied to $KEYSTORE_DIR/release.jks"
echo ""
echo "Add these to your local gradle.properties or CI secrets:"
echo "RELEASE_STORE_FILE=keystore/release.jks"
echo "RELEASE_STORE_PASSWORD=yourpassword"
echo "RELEASE_KEY_ALIAS=youralias"
echo "RELEASE_KEY_PASSWORD=yourpassword"
