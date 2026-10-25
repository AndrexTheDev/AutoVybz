plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bearinmind.equalizer314"
    compileSdk = 36
    // Pinned so the native virtual-surround engine (src/main/cpp) builds the same everywhere (F-Droid reproducibility).
    ndkVersion = "27.2.12479018"

    defaultConfig {
        // AutoVybz fork: own applicationId so it installs side by side with upstream Equalizer314.
        // The Kotlin namespace stays com.bearinmind.equalizer314 to keep upstream merges painless;
        // all AutoVybz code lives in com.andrexthedev.autovybz.
        applicationId = "com.andrexthedev.autovybz"
        // Android 9: the system-wide EQ is built on DynamicsProcessing (API 28). On 7-8.1 the engine class
        // fails to load (NoClassDefFoundError, found by the API 24 emulator run), so don't install there.
        minSdk = 28
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0-alpha"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Ship only the native ABIs that exist on API 28+ devices (drops 32-bit x86;
        // CI emulators are x86_64). One universal APK, ~1.5 MB smaller.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        // Only locales the app actually translates (values-* dirs above); strips the
        // ~80 library locales AppCompat/Material/Compose would otherwise add.
        resourceConfigurations += listOf("en", "de", "es", "pt", "ru", "uk", "zh-rCN")

        externalNativeBuild {
            cmake {
                // Oboe's prefab package is built against the shared C++ runtime.
                arguments += "-DANDROID_STL=c++_shared"
            }
        }
    }

    signingConfigs {
        create("release") {
            val storeFilePath = project.findProperty("RELEASE_STORE_FILE") as String?
            if (storeFilePath != null) {
                storeFile = file(storeFilePath)
                storePassword = project.findProperty("RELEASE_STORE_PASSWORD") as String?
                keyAlias = project.findProperty("RELEASE_KEY_ALIAS") as String?
                keyPassword = project.findProperty("RELEASE_KEY_PASSWORD") as String?
            }
        }
    }

    buildTypes {
        release {
            // R8 full mode (AGP 8 default): shrink + optimize + obfuscate, plus resource
            // shrinking. Keep rules in proguard-rules.pro cover this app's only
            // name-sensitive surfaces: JNI exports (mangled C names) and enum constants,
            // which are persisted by name in preferences and preset files. Reflection is
            // framework-only, components/views are kept by AAPT's generated rules, and
            // library consumer rules cover Compose/AppCompat/Material/coroutines.
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.getByName("release")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Note: on AGP 8.3+, also set `vcsInfo { include = false }` here for
            // F-Droid reproducibility. Current AGP 8.2.0 doesn't embed VCS info
            // by default, so no disabling is needed yet.
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }

    kotlinOptions {
        jvmTarget = "1.8"
    }

    buildFeatures {
        buildConfig = true
        // AutoVybz UI (wizard, pro settings, help, about) is written in Jetpack Compose;
        // the inherited Equalizer314 screens stay classic Views.
        compose = true
        // Oboe ships as a prefab AAR consumed by src/main/cpp/autovybz (CMake find_package).
        prefab = true
    }

    composeOptions {
        // Must match the Kotlin plugin version (1.9.20 -> 1.5.4).
        kotlinCompilerExtensionVersion = "1.5.4"
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    // AGP 8.2 can't place native libraries on 16 KB boundaries in the APK; compressed ones are extracted at install, so their position doesn't matter.
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    // F-Droid rejects APKs containing the "Dependency metadata" extra signing
    // block that AGP 8.1+ embeds by default. Disable both APK and AAB variants.
    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    // Speaker / room simulator shared by the JVM tests and the on-device end-to-end tests.
    sourceSets {
        getByName("test") { java.srcDir("src/sharedTest/java") }
        getByName("androidTest") { java.srcDir("src/sharedTest/java") }
    }

    // Lint runs in CI and is summarised by tools/audit/lint_summary.py (AutoVybz code must stay error-free;
    // the inherited Equalizer314 code is reported but does not fail the build).
    lint {
        abortOnError = false
        checkReleaseBuilds = false
        xmlReport = true
        htmlReport = true
        sarifReport = false
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    // RecyclerView 1.2.0+ — needed for ViewHolder.bindingAdapterPosition.
    // Used to be pulled in transitively by androidx.media3; declaring
    // explicitly now that the media3 deps are gone.
    implementation("androidx.recyclerview:recyclerview:1.3.2")

    // Lifecycle
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")

    // LocalBroadcastManager (for MediaNotificationListener → SessionDetector communication)
    implementation("androidx.localbroadcastmanager:localbroadcastmanager:1.1.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")

    // ---- AutoVybz ----------------------------------------------------------
    // Jetpack Compose (wizard / pro / help / about screens)
    val composeBom = platform("androidx.compose:compose-bom:2024.02.00")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-core")
    implementation("androidx.activity:activity-compose:1.8.2")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.7.0")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.7.0")
    debugImplementation("androidx.compose.ui:ui-tooling")
    // Oboe: low-latency full-duplex capture/playback for the measurement engine (prefab, C++)
    implementation("com.google.oboe:oboe:1.9.0")

    // (Removed unused androidx.media3-exoplayer/ui/session dependencies.
    // They were an artefact of an earlier capture-based design that
    // never shipped. The current implementation uses Android's native
    // DynamicsProcessing API and EnvironmentalReverb directly, no
    // ExoPlayer involvement. Removing them also strips the transitively-
    // added android.permission.ACCESS_NETWORK_STATE — "View network
    // connections" on the Play Store / settings UI — which was the
    // only thing that permission was there for.)

    // Testing
    testImplementation("junit:junit:4.13.2")
    // org.json is part of the Android framework; unit tests on the JVM need a real implementation.
    testImplementation("org.json:json:20231013")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.test.espresso:espresso-core:3.5.1")
    // End-to-end / screenshot tests (tools/e2e): Compose UI test, UiAutomator for system dialogs,
    // Espresso-Intents to verify share / settings intents without leaving the app.
    androidTestImplementation(composeBom)
    androidTestImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:rules:1.5.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    androidTestImplementation("androidx.test.espresso:espresso-intents:3.5.1")
}

// F-Droid reproducible builds: disable baseline profile generation. The
// output of baseline.prof is non-deterministic across machines and would
// cause F-Droid's build reproducibility check to fail.
// configureEach, not whenTaskAdded: the latter breaks AGP 8.1+'s task wiring
// (merged_java_res/base.jar missing when R8 reads it — issuetracker 277166577).
tasks.configureEach {
    if (name.contains("ArtProfile")) {
        enabled = false
    }
}

