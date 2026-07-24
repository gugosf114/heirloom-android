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
        applicationId = "com.heirloom.app"
        minSdk = 26
        targetSdk = 36
        versionCode = 2
        versionName = "1.0.0-rc2"
        vectorDrawables { useSupportLibrary = true }
    }

    // Optional shared secret gating the worker (see worker/src/index.ts).
    // Set in ~/.gradle/gradle.properties: HEIRLOOM_APP_KEY=<value of the
    // APP_SHARED_SECRET wrangler secret>. Empty = header not sent (dev/open).
    val appSharedSecret = (project.findProperty("HEIRLOOM_APP_KEY") as? String) ?: ""

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
            buildConfigField("String", "APP_SHARED_SECRET", "\"$appSharedSecret\"")
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

    // ML Kit Document Scanner (For perspective cropping physical photos)
    implementation("com.google.android.gms:play-services-mlkit-document-scanner:16.0.0")

    testImplementation("junit:junit:4.13.2")
}
