plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.gamemirror"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.example.gamemirror"
        minSdk = 35
        targetSdk = 35
        versionCode = 5
        versionName = "1.0.4"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }

        externalNativeBuild {
            cmake {
                cppFlags += listOf("-std=c++17", "-Wall", "-O2", "-fno-exceptions", "-fno-rtti")
                arguments += listOf("-DANDROID_STL=c++_shared")
            }
        }
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

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildFeatures {
        aidl = true
    }
}

dependencies {
    implementation("io.github.libxposed:api:102")
    implementation("org.jetbrains.kotlin:kotlin-stdlib:1.9.24")
}