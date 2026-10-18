plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val healthEndpoint = providers.gradleProperty("lucaHealthEndpoint")
    .orElse("https://example.invalid/v1/ingest")

android {
    namespace = "kr.co.luca.healthbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "kr.co.luca.healthbridge"
        minSdk = 28
        targetSdk = 36
        versionCode = 6
        versionName = "0.6.0"
        buildConfigField("String", "HEALTH_ENDPOINT", "\"${healthEndpoint.get()}\"")
    }

    buildFeatures {
        viewBinding = true
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.10.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.health.connect:connect-client:1.1.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    implementation("androidx.work:work-runtime-ktx:2.10.0")
    implementation("androidx.security:security-crypto:1.1.0-alpha06")
}
