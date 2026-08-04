plugins {
    id("com.android.application")
}

val releaseKeystorePath = providers.environmentVariable("CGE_KEYSTORE_PATH").orNull
val releaseKeystorePassword = providers.environmentVariable("CGE_KEYSTORE_PASSWORD").orNull
val releaseKeyAlias = providers.environmentVariable("CGE_KEY_ALIAS").orNull
val releaseKeyPassword = providers.environmentVariable("CGE_KEY_PASSWORD").orNull
val releaseSigningConfigured = listOf(
    releaseKeystorePath,
    releaseKeystorePassword,
    releaseKeyAlias,
    releaseKeyPassword,
).all { !it.isNullOrBlank() }

android {
    namespace = "io.github.yunshan.colorosglance"
    compileSdk = 36

    defaultConfig {
        applicationId = "io.github.yunshan.colorosglance"
        minSdk = 26
        targetSdk = 36
        versionCode = 12
        versionName = "0.1.11"
    }

    signingConfigs {
        if (releaseSigningConfigured) {
            create("release") {
                storeFile = file(releaseKeystorePath!!)
                storePassword = releaseKeystorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isDebuggable = false
            isMinifyEnabled = false
            if (releaseSigningConfigured) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = false
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = true
    }

    packaging {
        resources {
            merges += "META-INF/xposed/*"
        }
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }
}

configurations.configureEach {
    exclude(group = "org.jetbrains.kotlin", module = "kotlin-stdlib")
}

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
}
