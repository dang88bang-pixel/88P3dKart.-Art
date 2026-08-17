# How to Produce a Real Signed 3dxAgent REAL-CT45P APK

**Version:** v18.0.0-UNIFIED-REAL-CT45P

## 1. Prerequisites

- JDK 17+
- Android SDK (with `build-tools` containing `apksigner`)
- Git clone of the repo (branch `arena/01a00b5e-88p3dkart-art`)

## 2. Create / Prepare Keystore (once)

```bash
mkdir -p releases/keystore

keytool -genkey -v \
  -keystore releases/keystore/release.jks \
  -alias ct45p-release \
  -keyalg RSA \
  -keysize 2048 \
  -validity 10000 \
  -storepass YOUR_STRONG_PASSWORD \
  -keypass YOUR_STRONG_PASSWORD
```

**Store the password securely** (never commit it).

## 3. Local Signed Build (recommended for first time)

```bash
# Option A: Using the helper script
export RELEASE_STORE_FILE=releases/keystore/release.jks
export RELEASE_STORE_PASSWORD=YOUR_PASSWORD
export RELEASE_KEY_ALIAS=ct45p-release
export RELEASE_KEY_PASSWORD=YOUR_PASSWORD

./releases/build-signed-apk.sh
```

```bash
# Option B: Manual
cd android-app
./gradlew clean assembleRelease
cp app/build/outputs/apk/release/app-release.apk ../releases/3dxAgent-REAL-CT45P-18.0.0-signed.apk
```

## 4. Verify Signature

```bash
./releases/verify-release.sh releases/3dxAgent-REAL-CT45P-18.0.0-signed.apk
```

You should see certificate information.

## 5. CI / GitHub Actions (recommended for releases)

1. Encode your keystore:

```bash
base64 -i releases/keystore/release.jks | pbcopy   # or xclip on Linux
```

2. In your GitHub repo go to:
   **Settings → Secrets and variables → Actions**

Add the following secrets:
- `RELEASE_STORE_BASE64` → (the base64 output)
- `RELEASE_STORE_PASSWORD`
- `RELEASE_KEY_ALIAS` (usually `ct45p-release`)
- `RELEASE_KEY_PASSWORD`

3. Trigger the workflow:
   - Go to **Actions** → "Build 3dxAgent REAL-CT45P APK"
   - Click "Run workflow"

The workflow will:
- Decode the keystore
- Build signed APK
- Upload artifact
- (Optional) create GitHub Release when manually triggered

## 6. Install on CT45P

```bash
adb install -r releases/3dxAgent-REAL-CT45P-18.0.0-signed-*.apk
```

## 7. Troubleshooting

- `No keystore found` → make sure `RELEASE_STORE_FILE` points to a valid `.jks`
- `apksigner not found` → add Android SDK `build-tools` to your `$PATH`
- Permission issues on device → make sure the app is signed with the same key as previous installs (or uninstall first)

## 8. Best Practices

- Never commit the `.jks` or passwords
- Use different keystores for debug vs release
- Rotate the key only when absolutely necessary (it breaks updates)
- Keep the keystore password in a password manager + GitHub Secrets

---

**Ready-to-use scripts:**
- `releases/build-signed-apk.sh`
- `releases/setup-signing.sh`
- `releases/verify-release.sh`
