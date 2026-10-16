plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

// Release signing material must be supplied from an owner-controlled location.
// Never load a committed keystore or password file from the source tree.
val releaseKeystorePath = providers.environmentVariable("COMPASS_RELEASE_KEYSTORE").orNull
val releaseKeyAlias = providers.environmentVariable("COMPASS_RELEASE_KEY_ALIAS").orNull
val releaseStorePassword = providers.environmentVariable("COMPASS_RELEASE_STORE_PASSWORD").orNull
val releaseKeyPassword = providers.environmentVariable("COMPASS_RELEASE_KEY_PASSWORD").orNull
val externalSigningConfigured = listOf(
    releaseKeystorePath,
    releaseKeyAlias,
    releaseStorePassword,
    releaseKeyPassword
).all { !it.isNullOrBlank() }

android {
    namespace = "com.compass.vault"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.compass.vault"
        minSdk = 26          // Android 8.0 Oreo
        targetSdk = 35       // Android 15
        versionCode = 3
        versionName = "0.2"
        resourceConfigurations += listOf("en")
    }

    signingConfigs {
        create("release") {
            if (externalSigningConfigured) {
                storeFile = file(releaseKeystorePath!!)
                keyAlias = releaseKeyAlias!!
                storePassword = releaseStorePassword!!
                keyPassword = releaseKeyPassword!!
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            isShrinkResources = false
            if (externalSigningConfigured) signingConfig = signingConfigs.getByName("release")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    packaging {
        resources.excludes += setOf("META-INF/*.kotlin_module", "META-INF/versions/**")
    }

    androidResources {
        // The bundled Inter font is ~860 KB; keep it uncompressed so the
        // platform can mmap it straight from the APK.
        noCompress += "ttf"
    }
}

dependencies {
    // The ONLY third-party dependency in the whole app.
    // Everything else is the Android framework itself, which keeps the
    // attack surface (and the supply chain) as small as possible.
    implementation(libs.kotlinx.coroutines.core)
}
