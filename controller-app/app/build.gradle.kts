plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.hma.nexalloy"
    compileSdk { version = release(37) }

    signingConfigs {
        getByName("debug") {
            enableV1Signing = true
            enableV2Signing = true
            enableV3Signing = true
            enableV4Signing = false
        }
        val store = providers.environmentVariable("HMA_KEYSTORE_PATH").orNull
        val password = providers.environmentVariable("HMA_KEYSTORE_PASSWORD").orNull
        val alias = providers.environmentVariable("HMA_KEY_ALIAS").orNull
        val keyPassword = providers.environmentVariable("HMA_KEY_PASSWORD").orNull
        if (!store.isNullOrBlank() && !password.isNullOrBlank() &&
            !alias.isNullOrBlank() && !keyPassword.isNullOrBlank()) {
            create("hmaRelease") {
                storeFile = file(store)
                storePassword = password
                keyAlias = alias
                this.keyPassword = keyPassword
                enableV1Signing = true
                enableV2Signing = true
                enableV3Signing = true
                enableV4Signing = false
            }
        }
    }

    defaultConfig {
        applicationId = "com.hma.nexalloy"
        minSdk = 33
        targetSdk = 37
        versionCode = 10000
        versionName = "1.0.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            signingConfig = signingConfigs.findByName("hmaRelease")
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures { compose = true }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.icons)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
