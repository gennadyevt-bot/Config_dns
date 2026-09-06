plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.config.app"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.config.app"
        minSdk = 24
        targetSdk = 34
        versionCode = 27
        versionName = "5.0.6"
    }

    signingConfigs {
        create("release") {
            val ksPath = System.getenv("UPLOAD_KEYSTORE_PATH")
                ?: (project.findProperty("UPLOAD_KEYSTORE_PATH") as String?)
            if (ksPath != null) {
                storeFile = file(ksPath)
                storePassword = System.getenv("UPLOAD_STORE_PASSWORD") ?: ""
                keyAlias = System.getenv("UPLOAD_KEY_ALIAS") ?: "configvpn-upload"
                keyPassword = System.getenv("UPLOAD_KEY_PASSWORD") ?: ""
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }
