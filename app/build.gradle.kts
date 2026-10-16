plugins {
    id("com.android.application")
}

// Release signing is supplied by CI through environment variables.
// When they are absent (local dev builds) we fall back to the debug key so
// that `./gradlew assembleRelease` always produces an installable APK.
val ciStorePath: String? = System.getenv("TUMAN_KEYSTORE")
val ciStore: java.io.File? = ciStorePath?.let { file(it) }?.takeIf { it.exists() }

android {
    namespace = "com.tuman.free"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.tuman.free"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0.0"
    }

    signingConfigs {
        if (ciStore != null) {
            create("release") {
                storeFile = ciStore
                storePassword = System.getenv("TUMAN_STORE_PASSWORD")
                keyAlias = System.getenv("TUMAN_KEY_ALIAS")
                keyPassword = System.getenv("TUMAN_KEY_PASSWORD")
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = false
                enableV4Signing = false
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
            signingConfig = if (ciStore != null) {
                signingConfigs.getByName("release")
            } else {
                signingConfigs.getByName("debug")
            }
        }
        debug {
            applicationIdSuffix = ".dev"
            versionNameSuffix = "-dev"
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    // tpws is shipped as a "native library" (jniLibs/lib<abi>/libtpws.so) so that
    // the installer extracts it into the read-only, executable nativeLibraryDir.
    // This is the only reliable way to exec() a bundled binary on Android 10+,
    // where executing files from the writable app data directory is forbidden (W^X).
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
        }
    }

    lint {
        abortOnError = false
        checkReleaseBuilds = false
    }
}

// Sources contain Cyrillic literals and comments - pin the encoding so the build
// never depends on the platform default of the machine running Gradle.
tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

// The project deliberately has NO third-party dependencies:
// no AppCompat, no Material Components, no Compose, no Hilt, no Kotlin stdlib.
// Everything is built on the framework APIs available since API 24-26.
dependencies {
}
