plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.kotlin.plugin.serialization")
}

/**
 * Release signing material comes from the environment (CI secrets) or gradle.properties /
 * -P flags — never from the repo. A release build with none of it configured used to fall
 * back silently to `app-release-unsigned.apk`; now it fails unless
 * `-PallowUnsignedRelease=true` is passed for a local smoke build.
 */
fun releaseSecret(name: String): String? =
    System.getenv(name)?.takeIf { it.isNotBlank() }
        ?: (project.findProperty(name) as String?)?.takeIf { it.isNotBlank() }

val releaseStoreFilePath = releaseSecret("ANDROID_KEYSTORE_FILE")
val releaseStorePassword = releaseSecret("ANDROID_KEYSTORE_PASSWORD")
val releaseKeyAlias = releaseSecret("ANDROID_KEY_ALIAS")
val releaseKeyPassword = releaseSecret("ANDROID_KEY_PASSWORD")
val releaseSigningConfigured =
    releaseStoreFilePath != null && releaseStorePassword != null &&
        releaseKeyAlias != null && releaseKeyPassword != null
val allowUnsignedRelease = (project.findProperty("allowUnsignedRelease") as String?)?.toBoolean() ?: false
val disableMinify = (project.findProperty("disableMinify") as String?)?.toBoolean() ?: false

/** `v1.2.3` (git tag) and `1.2.3` are both accepted; continuous builds pass `0.1.<run>`. */
fun resolveVersionName(): String =
    ((project.findProperty("versionName") as String?)?.trim()?.removePrefix("v")?.takeIf { it.isNotBlank() })
        ?: "0.1.0"

android {
    namespace = "com.copix.androidtaktracker"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.copix.androidtaktracker"
        minSdk = 26
        targetSdk = 35
        // CI passes -PversionCode (monotonic GitHub run number) / -PversionName (tag or 0.1.<run>).
        versionCode = (project.findProperty("versionCode") as String?)?.toIntOrNull() ?: 1
        versionName = resolveVersionName()
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables {
            useSupportLibrary = true
        }
    }

    signingConfigs {
        create("release") {
            if (releaseSigningConfigured) {
                storeFile = file(releaseStoreFilePath!!)
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
                // minSdk 26 devices verify v2/v3 (v1 JAR signing is unnecessary at this minSdk);
                // v4 is opt-in and not needed for APK/AAB distribution.
                enableV2Signing = true
                enableV3Signing = true
            }
        }
    }

    buildTypes {
        release {
            // Shrink unused code/resources but keep names: BouncyCastle, Tink/protobuf and the
            // optional Headwind reflection all look classes up by name. See proguard-rules.pro.
            isMinifyEnabled = !disableMinify
            isShrinkResources = !disableMinify
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    bundle {
        // Universal/MDM/sideload APKs derived from the bundle must keep every locale.
        language { enableSplit = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
        aidl = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "META-INF/LICENSE.md"
            excludes += "META-INF/LICENSE-notice.md"
            excludes += "META-INF/DEPENDENCIES"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
            pickFirsts += "META-INF/versions/9/OSGI-INF/MANIFEST.MF"
        }
    }
}

dependencies {
    implementation(project(":core"))
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    val composeBom = platform("androidx.compose:compose-bom:2024.10.01")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.navigation:navigation-compose:2.8.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("com.google.android.material:material:1.12.0")

    // CameraX + ML Kit barcode (QR enrollment)
    val cameraX = "1.4.0"
    implementation("androidx.camera:camera-core:$cameraX")
    implementation("androidx.camera:camera-camera2:$cameraX")
    implementation("androidx.camera:camera-lifecycle:$cameraX")
    implementation("androidx.camera:camera-view:$cameraX")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // Location
    implementation("com.google.android.gms:play-services-location:21.3.0")

    // Background work
    implementation("androidx.work:work-runtime-ktx:2.10.0")

    // Encrypted secrets (Keystore)
    implementation("androidx.security:security-crypto:1.1.0-alpha06")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Icons
    implementation("androidx.compose.material:material-icons-extended")

    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")

    testImplementation("junit:junit:4.13.2")
}

gradle.taskGraph.whenReady {
    val releasePackaging = setOf(":app:packageRelease", ":app:packageReleaseBundle")
    val buildingRelease = allTasks.any { it.path in releasePackaging }
    if (buildingRelease && !releaseSigningConfigured && !allowUnsignedRelease) {
        throw GradleException(
            "Release signing is not configured. Set ANDROID_KEYSTORE_FILE, ANDROID_KEYSTORE_PASSWORD, " +
                "ANDROID_KEY_ALIAS and ANDROID_KEY_PASSWORD (env or -P), or pass -PallowUnsignedRelease=true " +
                "for a local unsigned smoke build. See docs/release.md.",
        )
    }
}
