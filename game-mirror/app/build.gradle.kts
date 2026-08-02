plugins {
    id("com.android.application")
}

android {
    namespace = "com.example.gamemirror"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.gamemirror"
        minSdk = 30
        targetSdk = 34
        versionCode = 8
        versionName = "1.0.2"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
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

    buildFeatures {
        aidl = true
    }
}

dependencies {
    implementation("io.github.libxposed:api:102")
}