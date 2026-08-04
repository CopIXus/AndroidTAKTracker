plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.serialization")
}

android {
    namespace = "com.copix.androidtaktracker.core"
    compileSdk = 34

    defaultConfig {
        minSdk = 26
        // Instrumented tests would need a runner; unit tests under src/test are plain JVM/JUnit4.
        consumerProguardFiles("consumer-rules.pro")
    }

    compileOptions {
        // java.time (Instant/Duration/DateTimeFormatter) needs desugaring below minSdk 26;
        // isCoreLibraryDesugaringEnabled kept on defensively if minSdk is ever lowered.
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    // PKCS#10 CSR for Marti :8446 enrollment (Android has no sun.security.pkcs10).
    implementation("org.bouncycastle:bcprov-jdk18on:1.78.1")
    implementation("org.bouncycastle:bcpkix-jdk18on:1.78.1")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.1.2")

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.9.0")
}
