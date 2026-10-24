import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

val cam = Properties().apply {
    rootProject.file("camera.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}

fun camProp(key: String, default: String) = "\"" + cam.getProperty(key, default) + "\""

android {
    namespace = "com.pux.camaratv"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.pux.camaratv"
        minSdk = 21
        targetSdk = 34
        versionCode = 6
        versionName = "2.0"

        buildConfigField("String", "CAM_HOST", camProp("camera.host", "192.168.100.71"))
        buildConfigField("int", "CAM_PORT", cam.getProperty("camera.port", "34567"))
        buildConfigField("String", "CAM_USER", camProp("camera.user", "admin"))
        buildConfigField("String", "CAM_PASS", camProp("camera.pass", ""))
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Firmado con la clave debug para poder instalarlo directo en el TV box
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}
