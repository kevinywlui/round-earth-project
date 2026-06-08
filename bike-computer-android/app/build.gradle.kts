plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.roundearth.bikecomputer"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.roundearth.bikecomputer"
        minSdk = 26
        targetSdk = 35
        // Overridable from CI (e.g. -PversionName=1.2.0 -PversionCode=7) so each
        // tagged release carries a distinct, increasing version for Obtainium.
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "1.0"
    }

    // Release signing key, supplied by CI from GitHub Secrets (decoded to a file
    // whose path is passed via SIGNING_KEYSTORE_FILE). The key itself never lives
    // in the repo. A constant key is what lets Android install updates over a
    // previous install. Absent locally, where the build falls back to debug.
    signingConfigs {
        create("release") {
            System.getenv("SIGNING_KEYSTORE_FILE")?.let { path ->
                storeFile = file(path)
                storePassword = System.getenv("SIGNING_STORE_PASSWORD")
                keyAlias = System.getenv("SIGNING_KEY_ALIAS")
                keyPassword = System.getenv("SIGNING_KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Use the release key when CI provides it, else fall back to the debug
            // key so a local `assembleRelease` still yields an installable APK.
            signingConfig = if (System.getenv("SIGNING_KEYSTORE_FILE") != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }

    kotlinOptions {
        jvmTarget = "11"
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            // Mockito 5's default inline mock-maker (used to mock the final android.bluetooth.*
            // classes in the Tier-2 BLE tests) self-attaches a Byte Buddy agent; JDK 21 warns
            // and may fail without this flag. See docs/testing-plan.md §3.
            all { it.jvmArgs("-XX:+EnableDynamicAgentLoading") }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.10.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.6")
    implementation("androidx.lifecycle:lifecycle-process:2.8.6")
    implementation("androidx.activity:activity-compose:1.9.2")
    implementation("androidx.datastore:datastore-preferences:1.1.1")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
    // Tier 1 JVM tests: in-memory Room exercised under a Robolectric runtime, with
    // coroutines-test driving the suspend DAO and the repository's collect loop.
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.room:room-testing:2.6.1")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
    // Tier 2 BLE state-machine tests: Mockito 5's default inline mock-maker mocks the
    // final android.bluetooth.* classes; the test drives the captured BluetoothGattCallback.
    testImplementation("org.mockito:mockito-core:5.14.2")
    testImplementation("org.mockito.kotlin:mockito-kotlin:5.4.0")
}
