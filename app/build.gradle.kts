import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    // Uncomment after dropping google-services.json into app/
    // id("com.google.gms.google-services")
}

android {
    namespace = "com.heirloom.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.wimlabs.heirloom"
        minSdk = 26
        targetSdk = 36
        versionCode = 7
        versionName = "1.0.0-rc7"
        vectorDrawables { useSupportLibrary = true }
    }

    // Optional key for local development against a gated development Worker.
    // Production uses Play Integrity and server-issued sessions instead.
    val appSharedSecret = (project.findProperty("HEIRLOOM_APP_KEY") as? String) ?: ""
    // Public Google Cloud project number linked to Heirloom in Play Console.
    // Debug/sideload builds may leave this as 0 and use the development Worker.
    val playIntegrityProjectNumber =
        (project.findProperty("HEIRLOOM_PLAY_PROJECT_NUMBER") as? String) ?: "787543109204"

    // Play upload key — configured per-machine, never in the repo. In
    // ~/.gradle/gradle.properties set:
    //   HEIRLOOM_UPLOAD_STORE=<absolute path to .jks>
    //   HEIRLOOM_UPLOAD_PASSWORD=<store+key password>
    // Without these, release builds stay unsigned (CI just runs assembleDebug).
    val uploadStore = project.findProperty("HEIRLOOM_UPLOAD_STORE") as? String
    val uploadPassword = project.findProperty("HEIRLOOM_UPLOAD_PASSWORD") as? String
    if (uploadStore != null && uploadPassword != null) {
        signingConfigs {
            create("upload") {
                storeFile = file(uploadStore)
                storePassword = uploadPassword
                keyAlias = "upload"
                keyPassword = uploadPassword
            }
        }
    }

    buildTypes {
        debug {
            buildConfigField("String", "WORKER_BASE_URL", "\"https://heirloom-worker-dev.gugosf.workers.dev\"")
            buildConfigField("String", "APP_SHARED_SECRET", "\"$appSharedSecret\"")
            buildConfigField(
                "long",
                "PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER",
                "0L",
            )
        }
        release {
            if (uploadStore != null && uploadPassword != null) {
                signingConfig = signingConfigs.getByName("upload")
            }
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            buildConfigField("String", "WORKER_BASE_URL", "\"https://heirloom-worker.gugosf.workers.dev\"")
            // Production authentication is Play Integrity + a server-issued
            // session. Never package the development shared key in a release.
            buildConfigField("String", "APP_SHARED_SECRET", "\"\"")
            buildConfigField(
                "long",
                "PLAY_INTEGRITY_CLOUD_PROJECT_NUMBER",
                "${playIntegrityProjectNumber}L",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }
}

kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2026.06.00")
    implementation(composeBom)
    androidTestImplementation(composeBom)

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")

    // Compose
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    // Coil for image loading (handles content:// URIs natively, no Glide config)
    implementation("io.coil-kt:coil-compose:2.7.0")

    // OkHttp for the multipart upload to the Worker
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Coroutines + Play Services Task<T>.await() extensions used by AuthManager.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-play-services:1.9.0")

    // JSON
    implementation("org.json:json:20240303")

    // Google Play Billing
    implementation("com.android.billingclient:billing-ktx:9.1.0")
    implementation("com.google.android.play:integrity:1.6.0")

    // ML Kit Document Scanner (For perspective cropping physical photos)
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")

    testImplementation("junit:junit:4.13.2")
}
