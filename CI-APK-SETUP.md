# APK-Bauen aus CI / lokal

Diese Anleitung beschreibt, wie du die App zu einer signierten Release-APK baust.

## Warum kein Build im Sandbox-Container?

Die Build-Sandbox hat **kein JDK, kein Android-SDK und kein Internet** zu den
Google-/Maven-Mirrors (`dl.google.com`, `repo1.maven.org`, `services.gradle.org`
sind blockiert). Selbst mit Workarounds kann hier keine APK erzeugt werden.

Stattdessen läuft der Build in **GitHub Actions** (siehe
`.github/workflows/build-apk.yml`).

## Schritt 1 — Workflow-Datei aktivieren (einmalig)

Die Workflow-Datei `.github/workflows/build-apk.yml` liegt im Working-Tree
dieser Sandbox, konnte aber **nicht per `git push` übertragen** werden,
weil das Sandbox-Token keine `workflows`-Permission hat. Bitte einmalig:

```bash
cd /home/user/88P3dKart.-Art
git add .github/workflows/build-apk.yml
git -c user.name="dein-name" -c user.email="deine@email" \
  commit -m "ci(android): GitHub-Actions-Workflow für signierte Release-APK"
git push origin arena/01a00304-88p3dkart-art
```

> Falls du den Sandbox-Token mit `workflows`-Scope ausstatten willst, kann
> der Push auch direkt aus der Sandbox erfolgen — sag einfach Bescheid.

## Schritt 2 — APK bauen

Es gibt zwei Wege:

### a) Per Git-Tag (Release-Pipeline)

```bash
git tag v2.0.0
git push origin v2.0.0
```

→ Workflow läuft, hängt `app-release.apk` an das Release `v2.0.0` an.
→ Download: https://github.com/dang88bang-pixel/88P3dKart.-Art/releases/tag/v2.0.0

### b) Manuell über die GitHub-UI

GitHub → Actions → `build-apk` → Run workflow → Branch wählen → Run.

→ Die APK landet als **Artifact** `3dxAgent-release-apk` (30 Tage gültig).

## Was der Workflow tut

| Step | Zweck |
|------|-------|
| Checkout | Repo klonen |
| Set up JDK 17 | Temurin JDK |
| Cache Gradle | Build-Cache |
| Ensure release keystore | Generiert `release.jks` falls fehlt, committet ihn zurück |
| Inject keystore credentials | Schreibt Alias/Password in `~/.gradle/gradle.properties` |
| Make gradlew executable | `chmod +x` |
| Build release APK | `./gradlew assembleRelease` |
| Locate APK | Pfad bestimmen |
| Upload APK to release | Bei Tag-Trigger: ans Release anhängen |
| Upload APK as artifact | Bei Manual-Trigger: 30-Tage-Artifact |

## Signatur

- Beim **ersten** Workflow-Run wird automatisch ein Keystore generiert:
  - Datei: `android-app/keystore/release.jks`
  - Alias: `3dxagent`
  - Passwörter: `ChangeMe123` (im Klartext im Workflow — **Produktion später durch Secrets ersetzen!**)
- Folge-Runs verwenden den existierenden Keystore.
- Der Keystore wird per CI-Commit zurück ins Repo geschrieben (Whitelist via `.gitignore`).

## Produktion härten (später)

1. Keystore + Passwörter in Repository-Secrets verschieben:
   - `RELEASE_KEYSTORE_BASE64` (base64 von `release.jks`)
   - `RELEASE_KEY_ALIAS`
   - `RELEASE_KEYSTORE_PASSWORD`
   - `RELEASE_KEY_PASSWORD`
2. Workflow-Schritt "Ensure release keystore exists" deaktivieren (Secret statt Generierung).
3. `isMinifyEnabled = true` in `app/build.gradle.kts` setzen + ProGuard-Regeln für Java-WebSocket/UsbSerial prüfen.
4. Custom Signing-Config statt der generischen `signingConfigs { release { ... } }`-Vorlage.

## Lokal bauen (Alternative zu CI)

Auf einem Entwickler-System mit JDK 17 + Android SDK:

```bash
cd android-app
./gradlew assembleRelease \
  -PRELEASE_STORE_FILE=keystore/release.jks \
  -PRELEASE_STORE_PASSWORD=ChangeMe123 \
  -PRELEASE_KEY_ALIAS=3dxagent \
  -PRELEASE_KEY_PASSWORD=ChangeMe123

# APK liegt unter: app/build/outputs/apk/release/app-release.apk
```

## Bekannte Limitierungen (Audit-Stand)

- **minSdk = 31** (Android 12+) — wegen UWB. Ältere Geräte werden nicht unterstützt.
- **isMinifyEnabled = false** — R8/ProGuard noch nicht aktiviert, weil einige Dependencies
  (Java-WebSocket, UsbSerial) Keep-Regeln brauchen. APK ist ~8–12 MB größer als nötig.
- **Hardware-spezifische Permissions** (BLUETOOTH_SCAN, UWB_RANGING, NEARBY_WIFI_DEVICES)
  werden zur Laufzeit in `MainActivity` angefordert — der User muss sie akzeptieren.
