plugins {
    id("com.android.application")
}

android {
    namespace = "org.photoall.film"
    compileSdk = 36
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "org.photoall.film"
        minSdk = 29
        targetSdk = 36
        versionCode = 68
        versionName = "0.2.2"
        ndk { abiFilters += "arm64-v8a" }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.31.6"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // spectra/profile LUTs are already compact binary data
    androidResources { noCompress += "lut" }
}

dependencies {
    val camerax = "1.6.2" // 1.5+ for RAW (DNG) capture
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.activity:activity:1.10.1")
}
