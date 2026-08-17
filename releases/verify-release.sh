#!/bin/bash
set -e
APK="$1"
if [ -z "$APK" ]; then
  echo "Usage: $0 path/to.apk"
  exit 1
fi
echo "=== Verifying $APK ==="
ls -lh "$APK"
if command -v apksigner >/dev/null 2>&1; then
  apksigner verify --verbose --print-certs "$APK" || true
else
  echo "apksigner not in PATH (from Android SDK build-tools)"
fi
echo "Done."
