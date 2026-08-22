plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.example.agent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.agent"
        minSdk = 31 // Android 12+ für UWB + BLE 5.0
        targetSdk = 34
        versionCode = 6
        versionName = "1.0.0"
        multiDexEnabled = true
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        create("release") {
            // Credentials werden in ~/.gradle/gradle.properties
            // (lokal) bzw. in den GitHub-Actions-Secrets (CI)
            // hinterlegt. Siehe .github/workflows/build-apk.yml.
            val ksFile = providers.gradleProperty("RELEASE_STORE_FILE")
            val ksPass = providers.gradleProperty("RELEASE_STORE_PASSWORD")
            val ksAlias = providers.gradleProperty("RELEASE_KEY_ALIAS")
            val keyPass = providers.gradleProperty("RELEASE_KEY_PASSWORD")
            if (ksFile.isPresent && ksPass.isPresent && ksAlias.isPresent && keyPass.isPresent) {
                storeFile = rootProject.file(ksFile.get())
                storePassword = ksPass.get()
                keyAlias = ksAlias.get()
                keyPassword = keyPass.get()
            }
        }
    }

    testOptions {
        // JVM-Unit-Tests: android.util.Log u. ä. als No-Op (kein Robolectric nötig)
        unitTests.isReturnDefaultValues = true
    }

    buildTypes {
        release {
            // ProGuard ist hier noch aus, weil einige der Reflection-
            // Abhängigkeiten (Java-WebSocket, FelHR85-UsbSerial) mit
            // Keep-Regeln abgesichert werden müssen, bevor man es
            // aktiviert. Die Defaults aus proguard-rules.pro reichen
            // für assembleRelease — das Ergebnis ist eine nicht-
            // obsfukierte, aber korrekt signierte APK.
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Nur wenn der Keystore konfiguriert ist, signieren wir
            // release. Sonst fällt Gradle auf den Debug-Key zurück
            // (oder bricht — abhängig von der Android-Version).
            val ksFile = providers.gradleProperty("RELEASE_STORE_FILE")
            if (ksFile.isPresent) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { viewBinding = true }
}

dependencies {
    // Core
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.multidex:multidex:2.0.1")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-ktx:1.8.2")
    implementation("androidx.fragment:fragment-ktx:1.6.2")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.cardview:cardview:1.0.0")
    implementation("androidx.swiperefreshlayout:swiperefreshlayout:1.1.0")
    implementation("androidx.viewpager2:viewpager2:1.0.0")
    implementation("androidx.preference:preference-ktx:1.2.1")
    implementation("androidx.documentfile:documentfile:1.0.1")
    implementation("androidx.coordinatorlayout:coordinatorlayout:1.2.0")

    // Lifecycle / Work / Security
    implementation("androidx.lifecycle:lifecycle-viewmodel-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.7.0")
    implementation("androidx.lifecycle:lifecycle-service:2.7.0")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    // USB-Serial (LiDAR & mmWave)
    implementation("com.github.felHR85:UsbSerial:6.1.0")

    // Lokaler WebSocket-Server (Offline-Modus)
    implementation("org.java-websocket:Java-WebSocket:1.5.3")

    // Persistenz (SQLite WAL)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // Netzwerk
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("com.squareup.okhttp3:logging-interceptor:4.12.0")
    implementation("com.squareup.retrofit2:retrofit:2.9.0")
    implementation("com.squareup.retrofit2:converter-gson:2.9.0")
    implementation("com.google.code.gson:gson:2.10.1")

    // Serialisierung
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.6.0")

    // Navigation
    implementation("androidx.navigation:navigation-fragment-ktx:2.7.6")
    implementation("androidx.navigation:navigation-ui-ktx:2.7.6")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    testImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
}
