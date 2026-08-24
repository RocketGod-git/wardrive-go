plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

android {
    namespace = "com.rocketgod.warble"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rocketgod.wardrive"
        minSdk = 26
        targetSdk = 36
        versionCode = 573
        versionName = "2.0.34"
        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        create("release") {
            fun env(name: String) = System.getenv(name)?.takeIf { it.isNotBlank() }
            val ksPath = env("ANDROID_KEYSTORE_PATH")
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = env("ANDROID_KEYSTORE_PASSWORD")
                keyAlias = env("ANDROID_KEY_ALIAS")
                keyPassword = env("ANDROID_KEY_PASSWORD")
            }
        }
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = if (System.getenv("ANDROID_KEYSTORE_PATH")?.isNotBlank() == true)
                signingConfigs.getByName("release") else signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }

    buildFeatures { compose = true; buildConfig = true }
    packaging { resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" } }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")

    implementation("com.google.guava:guava:31.1-android")
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.fragment:fragment:1.8.6")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")

    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    implementation("com.google.android.gms:play-services-location:21.3.0")
    implementation("com.google.android.gms:play-services-wearable:18.2.0")
    implementation("org.osmdroid:osmdroid-android:6.1.20")
    implementation("com.google.android.play:app-update-ktx:2.1.0")
    implementation("androidx.wear:wear-remote-interactions:1.1.0")
    implementation("androidx.car.app:app:1.4.0")
}
