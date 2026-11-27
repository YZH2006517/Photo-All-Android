import java.util.Properties

plugins {
    id("com.android.application")
}

// Kept out of the repository: the problem-report address (cloud/report) and its token.
val localProps = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

android {
    namespace = "org.photoall.film"
    compileSdk = 36
    // The same debug key the released APKs have always used (a new key would stop updates),
    // signed with v1 (JAR) as well as v2/v3: some phones' installers and security scanners look
    // only for the META-INF signature files and call a v2-only APK unsigned.
    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
        }
    }
    ndkVersion = "28.2.13676358"

    defaultConfig {
        applicationId = "org.photoall.film"
        minSdk = 29
        targetSdk = 36
        versionCode = 153
        versionName = "0.3.1-beta9"
        ndk { abiFilters += "arm64-v8a" }
        buildConfigField("String", "REPORT_URL", "\"${localProps.getProperty("report.url", "")}\"")
        buildConfigField("String", "REPORT_TOKEN", "\"${localProps.getProperty("report.token", "")}\"")
    }

    buildFeatures { buildConfig = true }

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
    // models stay uncompressed in the APK: opened in place, copied out by length (Detail)
    androidResources { noCompress += listOf("lut", "onnx", "data") }
}

dependencies {
    val camerax = "1.6.2" // 1.5+ for RAW (DNG) capture
    implementation("androidx.camera:camera-core:$camerax")
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    // 拼贴相机: the subject cut out on the phone by U²-Net (u2netp, Apache-2.0, assets/segment), run by ONNX Runtime (MIT)
    implementation("com.microsoft.onnxruntime:onnxruntime-android:1.22.0")
    implementation("androidx.activity:activity:1.10.1")
}
