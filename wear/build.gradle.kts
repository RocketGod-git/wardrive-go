plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.rocketgod.warble.wear"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.rocketgod.wardrive"
        minSdk = 30
        targetSdk = 36
        versionCode = 1000573
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
    implementation("androidx.activity:activity-compose:1.9.3")

    implementation("androidx.fragment:fragment:1.8.6")

    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.material:material-icons-core")

    implementation("androidx.wear.compose:compose-material:1.4.0")
    implementation("androidx.wear.compose:compose-foundation:1.4.0")

    implementation("com.google.android.gms:play-services-wearable:18.2.0")

    implementation("androidx.wear:wear:1.3.0")

    implementation("androidx.wear.tiles:tiles:1.4.0")
    implementation("androidx.wear.protolayout:protolayout:1.2.0")
    implementation("androidx.wear.protolayout:protolayout-material:1.2.0")
    implementation("androidx.concurrent:concurrent-futures:1.1.0")

    implementation("androidx.wear.watchface:watchface-complications-data-source-ktx:1.2.1")
}
