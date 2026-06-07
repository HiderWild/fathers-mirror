import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val releaseSigningProperties = Properties().apply {
    val propertiesFile = rootProject.file("release-signing.properties")
    if (propertiesFile.exists()) {
        load(FileInputStream(propertiesFile))
    }
}

val hasReleaseSigning = listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all { key ->
    val value = releaseSigningProperties[key]?.toString() ?: return@all false
    value.isNotBlank() && !value.contains("<CHANGE_ME>")
}

android {
    namespace = "com.laobademirror"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.laobademirror"
        minSdk = 33
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                keyAlias = releaseSigningProperties["keyAlias"] as String
                keyPassword = releaseSigningProperties["keyPassword"] as String
                storeFile = file(releaseSigningProperties["storeFile"] as String)
                storePassword = releaseSigningProperties["storePassword"] as String
            }
        }
    }

    buildTypes {
        getByName("debug")
        getByName("release") {
            isMinifyEnabled = false
            isShrinkResources = false
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            } else {
                // 开发阶段若未填写 release-signing.properties，则回退到 debug 签名，避免构建被阻塞
                signingConfig = signingConfigs.getByName("debug")
            }
        }
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.activity:activity-ktx:1.9.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.3")

    implementation("androidx.camera:camera-core:1.4.0")
    implementation("androidx.camera:camera-camera2:1.4.0")
    implementation("androidx.camera:camera-lifecycle:1.4.0")
    implementation("androidx.camera:camera-view:1.4.0")

    implementation("androidx.exifinterface:exifinterface:1.3.7")
}
