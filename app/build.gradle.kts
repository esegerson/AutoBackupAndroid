import java.util.Properties

val localProperties = Properties().apply {
    val localPropertiesFile = rootProject.file("local.properties")
    if (localPropertiesFile.exists()) {
        localPropertiesFile.inputStream().use { load(it) }
    }
}

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "net.esegerson.autobackup"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "net.esegerson.autobackup"
        minSdk = 33
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        val smbPassword = localProperties.getProperty("SMB_PASSWORD")?.trim()?.removeSurrounding("\"") ?: ""
        val escapedPassword = smbPassword.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "SMB_PASSWORD", "\"$escapedPassword\"")

        val smbUsername = localProperties.getProperty("SMB_USERNAME")?.trim()?.removeSurrounding("\"") ?: ""
        val escapedUsername = smbUsername.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "SMB_USERNAME", "\"$escapedUsername\"")

        val smbHost = localProperties.getProperty("SMB_HOST")?.trim()?.removeSurrounding("\"") ?: ""
        val escapedHost = smbHost.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "SMB_HOST", "\"$escapedHost\"")

        val targetSsids = localProperties.getProperty("TARGET_WIFI_SSIDS")?.trim()?.removeSurrounding("\"") ?: ""
        val escapedSsids = targetSsids.replace("\\", "\\\\").replace("\"", "\\\"")
        buildConfigField("String", "TARGET_WIFI_SSIDS", "\"$escapedSsids\"")
    }

    buildTypes {
        release {
            optimization {
                enable = false
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.appcompat)

    // SMB2/SMB3 Library
    implementation(libs.smbj)

    // WorkManager (Kotlin Coroutines)
    implementation(libs.workmanager.ktx)
}