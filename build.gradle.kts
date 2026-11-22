plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.chesscheatpro.mobile"
    compileSdk = 35
    defaultConfig {
        applicationId = "com.chesscheatpro.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 30
        versionName = "3.0.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // The bundled Stockfish build (jniLibs/arm64-v8a/libstockfish.so) is arm64 only.
        ndk { abiFilters += listOf("arm64-v8a") }
    }
    // Engine must be extracted to nativeLibraryDir: it is executed as a separate process.
    packaging { jniLibs { useLegacyPackaging = true } }
    buildFeatures { compose = true }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.10.0")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")
}
