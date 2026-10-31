import java.io.File
import java.io.FileInputStream
import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.aster.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.aster.app"
        minSdk = 24
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    val keystorePropertiesFile = rootProject.file("keystore.properties")
    val appKeystorePropertiesFile = project.file("keystore.properties")
    val releaseProperties = Properties().apply {
        if (keystorePropertiesFile.exists()) {
            FileInputStream(keystorePropertiesFile).use { load(it) }
        } else if (appKeystorePropertiesFile.exists()) {
            FileInputStream(appKeystorePropertiesFile).use { load(it) }
        }
    }

    val releaseStoreFile: String? = System.getenv("ASTER_KEYSTORE_PATH")
        ?: (project.findProperty("ASTER_KEYSTORE_PATH") as? String)
        ?: releaseProperties.getProperty("storeFile")
        ?: releaseProperties.getProperty("keystore.path")

    val releaseStorePassword: String? = System.getenv("ASTER_KEYSTORE_PASSWORD")
        ?: (project.findProperty("ASTER_KEYSTORE_PASSWORD") as? String)
        ?: releaseProperties.getProperty("storePassword")
        ?: releaseProperties.getProperty("keystore.password")

    val releaseKeyAlias: String? = System.getenv("ASTER_KEY_ALIAS")
        ?: (project.findProperty("ASTER_KEY_ALIAS") as? String)
        ?: releaseProperties.getProperty("keyAlias")
        ?: releaseProperties.getProperty("key.alias")

    val releaseKeyPassword: String? = System.getenv("ASTER_KEY_PASSWORD")
        ?: (project.findProperty("ASTER_KEY_PASSWORD") as? String)
        ?: releaseProperties.getProperty("keyPassword")
        ?: releaseProperties.getProperty("key.password")

    val resolvedStoreFile: File? = if (!releaseStoreFile.isNullOrBlank()) {
        val rawFile = File(releaseStoreFile)
        if (rawFile.isAbsolute && rawFile.exists()) {
            rawFile
        } else {
            listOf(
                file(releaseStoreFile),
                rootProject.file(releaseStoreFile),
                rootProject.file("../$releaseStoreFile")
            ).firstOrNull { it.exists() }
        }
    } else null

    val hasReleaseSigning = resolvedStoreFile != null &&
        !releaseStorePassword.isNullOrBlank() &&
        !releaseKeyAlias.isNullOrBlank() &&
        !releaseKeyPassword.isNullOrBlank()

    signingConfigs {
        if (hasReleaseSigning) {
            create("release") {
                storeFile = resolvedStoreFile!!
                storePassword = releaseStorePassword
                keyAlias = releaseKeyAlias
                keyPassword = releaseKeyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (hasReleaseSigning) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    // composeOptions block is NOT needed with Kotlin 2 — the compose compiler
    // is bundled in the org.jetbrains.kotlin.plugin.compose Gradle plugin.
    // Setting kotlinCompilerExtensionVersion here would conflict with Kotlin 2.

    testOptions {
        unitTests {
            isIncludeAndroidResources = true
        }
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.runtime)
    implementation(libs.androidx.compose.foundation)

    // Hilt — annotation processing via KSP (not kapt) for Kotlin 2
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Room
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // Navigation & Lifecycle
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.hilt.navigation.compose)

    // Coroutines
    implementation(libs.coroutines)

    // Networking / WebSocket
    implementation(libs.okhttp)

    // Unit Testing
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation("com.squareup.okhttp3:okhttp-tls:4.12.0")

    debugImplementation(libs.androidx.compose.ui.tooling)
}
