import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

// Signing: env vars (CI) or a local, git-ignored keystore.properties.
val localSigning = Properties().apply {
    val f = rootProject.file("keystore.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun signingValue(env: String, prop: String): String? =
    System.getenv(env)?.takeIf { it.isNotBlank() } ?: localSigning.getProperty(prop)

val keystorePath = signingValue("BENDCAM_KEYSTORE", "storeFile")
val hasReleaseKey = keystorePath != null && file(keystorePath).exists()

android {
    namespace = "com.bendcam.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bendcam.app"
        minSdk = 24
        targetSdk = 36
        versionCode = System.getenv("BENDCAM_VERSION_CODE")?.toIntOrNull() ?: 1
        versionName = System.getenv("BENDCAM_VERSION_NAME") ?: "0.1.0-local"
    }

    signingConfigs {
        if (hasReleaseKey) {
            create("release") {
                storeFile = file(keystorePath!!)
                storePassword = signingValue("BENDCAM_KEYSTORE_PASSWORD", "storePassword")
                keyAlias = signingValue("BENDCAM_KEY_ALIAS", "keyAlias")
                keyPassword = signingValue("BENDCAM_KEY_PASSWORD", "keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = if (hasReleaseKey) signingConfigs.getByName("release")
            else signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.camera.core)
    implementation(libs.androidx.camera.camera2)
    implementation(libs.androidx.camera.lifecycle)
}
