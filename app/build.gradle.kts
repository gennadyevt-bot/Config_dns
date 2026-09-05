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
        versionCode = 18
        versionName = "4.7.9"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
        isCoreLibraryDesugaringEnabled = true
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-Xskip-metadata-version-check",
            "-Xno-metadata-version-check"
        )
    }

    buildFeatures {
        viewBinding = true
    }

    packaging {
        resources {
            pickFirsts += listOf(
                "META-INF/kotlin-stdlib-jdk7.kotlin_module",
                "META-INF/kotlin-stdlib-jdk8.kotlin_module",
                "META-INF/kotlin-stdlib.kotlin_module"
            )
            excludes += listOf(
                "META-INF/DUMMY.SF",
                "META-INF/*.DSA",
                "META-INF/*.RSA",
                "META-INF/*.SF"
            )
        }
    }
}

configurations.all {
    resolutionStrategy {
        force("org.jetbrains.kotlin:kotlin-stdlib:2.0.21")
        force("org.jetbrains.kotlin:kotlin-stdlib-common:2.0.21")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk7:2.0.21")
        force("org.jetbrains.kotlin:kotlin-stdlib-jdk8:2.0.21")
        force("androidx.core:core-ktx:1.12.0")
        force("androidx.core:core:1.12.0")
        force("androidx.appcompat:appcompat:1.6.1")
        force("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.recyclerview:recyclerview:1.3.2")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("com.wireguard.android:tunnel:1.0.20260102")
    implementation("com.zaneschepke:amneziawg-android:2.3.7")
    implementation("com.journeyapps:zxing-android-embedded:4.3.0")
    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs:2.0.4")
}

tasks.whenTaskAdded {
    if (name.contains("checkDebugAarMetadata") || name.contains("checkReleaseAarMetadata")) {
        enabled = false
    }
}