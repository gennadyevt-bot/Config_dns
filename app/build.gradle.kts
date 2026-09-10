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
        versionCode = 28
        versionName = "5.0.7"
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


    kotlinOptions {
        jvmTarget = "1.8"
    }
}
dependencies {
    implementation("com.zaneschepke:amneziawg-android:2.3.7")
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.drawerlayout:drawerlayout:1.2.0")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    implementation("com.google.zxing:core:3.5.3")
    implementation("com.jcraft:jsch:0.1.55")
}
