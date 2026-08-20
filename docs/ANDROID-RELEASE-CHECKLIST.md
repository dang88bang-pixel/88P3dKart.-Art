# Agent-Checkliste: Android Native App — Status 1.0.0

Plattform: Honeywell CT45P (Android 13+, minSdk 31, targetSdk 34)

Legende: 🟢 umgesetzt im Repo · 🟡 teilweise / nur lokal prüfbar · 🔴 blockiert (Gerät/Play-Konto)

## Phase 1 — Code-Qualität
| ID | Status | Hinweis |
|----|--------|---------|
| 1.1.x | 🟡 | TODOs nicht flächig gelöscht; Lint lokal |
| 1.2.x | 🟡 | Kotlin + ViewBinding; keine Hilt/Koin-Pflicht |

## Phase 2 — Architektur & Dependencies
| ID | Status | Hinweis |
|----|--------|---------|
| 2.1 | 🟡 | Layer vorhanden (sensors/pipeline/ui/storage), kein striktes Hilt |
| 2.2 | 🟡 | Stabile Versionen, keine SNAPSHOTs; MultiDex an |

## Phase 3 — UI/UX
| ID | Status | Hinweis |
|----|--------|---------|
| 3.1 | 🟡 | Adaptive Icons, DayNight-Theme, CT45P 5" |
| 3.2–3.3 | 🟡 | Navigation-Komponente, Strings in strings.xml |

## Phase 4 — Tests
| ID | Status | Hinweis |
|----|--------|---------|
| 4.1 | 🟢 | JVM-Unit-Tests in CI (`testDebugUnitTest`) |
| 4.2–4.3 | 🟡 | Instrumentation braucht CT45P |

## Phase 5 — Build
| ID | Status | Hinweis |
|----|--------|---------|
| 5.1.1–5.1.2 | 🟢 | `assembleDebug` / `assembleRelease` in GitHub Actions |
| 5.1.5 | 🟢 | ABI `arm64-v8a`, `armeabi-v7a` |
| 5.2 | 🟡 | Signatur über Repo-Secrets; ohne Secrets debug-signiertes Release |
| 5.3 | 🟢 | Gradle parallel + caching |

## Phase 6 — Sicherheit
| ID | Status | Hinweis |
|----|--------|---------|
| 6.4.1 | 🟢 | `usesCleartextTraffic=false` |
| 6.4.2–6.4.4 | 🟢 | Backup aus, `networkSecurityConfig` |
| 6.4.5 | 🟢 | `exported` gesetzt |
| 6.1–6.3 | 🟡 | TLS, Encrypted prefs vorhanden wo genutzt |

## Phase 7–8 — Performance / Gerät
🟡 Gerätetests (USB-OTG, BLE, UWB, IMU) nur auf CT45P.

## Phase 9 — Play Store
🟡 Assets/Listing optional. AAB: `./gradlew bundleRelease` lokal.

## Phase 10 — Doku
🟢 README, OpenAPI, dieses Dokument, CI-APK-SETUP.

## Phase 11–13
🟡 Crashlytics/Play nicht Pflicht für Sideload auf CT45P.
🟢 Release-APK über GitHub Actions Artifact + Pre-Release-Tag.

## APK holen
1. Actions → **Build APK** → Artifact `3dxAgent-apk`
2. Releases → Tag `apk-1.0.0-*`
3. `adb install -r app-release.apk`
