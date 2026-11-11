plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "app.howlingdog"
    compileSdk = 36

    defaultConfig {
        applicationId = "app.howlingdog"
        minSdk = 24
        targetSdk = 36
        versionCode = 4
        versionName = "1.1.0"
    }

    // Phase 8: release signing. The release keystore is minted at
    // the first signed release (fresh keystore, never the Mariner
    // or MainMenu signer). CI supplies it via environment
    // variables (KEYSTORE_FILE, KEYSTORE_PASSWORD, KEY_ALIAS,
    // KEY_PASSWORD); without them the release build is unsigned,
    // so the debug CI stays green. The keystore file itself lives
    // in GitHub Secrets (ANDROID_KEYSTORE_BASE64) and is never
    // committed to the repo.
    val releaseKeystoreFile = System.getenv("KEYSTORE_FILE")
        ?.let { file(it) }?.takeIf { it.exists() }
    if (releaseKeystoreFile != null) {
        signingConfigs {
            create("release") {
                storeFile = releaseKeystoreFile
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            if (releaseKeystoreFile != null) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        // Phase 9, item 10: the in-app updater compares against the
        // installed version via BuildConfig.VERSION_NAME.
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2025.10.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.17.0")
    implementation("androidx.activity:activity-compose:1.11.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.9.4")
    implementation("androidx.documentfile:documentfile:1.0.1")

    // Phase 2: startup-path gate (Robolectric) + input-mask unit test.
    // The gate exists because green CI and ordinary APK checks missed
    // a launch death before (AutoSpace r159, Notscape beta.48): the
    // test launches MainActivity and asserts the setup screen renders
    // without crashing — with no native code present, since the
    // bridge loads its libraries lazily on game start only.
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.14.1")
    testImplementation("androidx.test:core:1.6.1")
    testImplementation("androidx.test.ext:junit:1.2.1")
    testImplementation("androidx.compose.ui:ui-test-junit4")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
