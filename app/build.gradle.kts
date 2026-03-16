import java.util.Base64
import java.util.Properties

data class ReleaseSigningConfig(
    val storeFile: File,
    val storePassword: String,
    val keyAlias: String,
    val keyPassword: String
)

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.dagger.hilt.android")
    id("com.google.devtools.ksp")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
keystorePropertiesFile.exists().also { exists ->
    if (exists) {
        keystorePropertiesFile.inputStream().use(keystoreProperties::load)
    }
}

fun signingValue(propertyName: String, envName: String): String? {
    return System.getenv(envName)?.takeIf { it.isNotBlank() }
        ?: keystoreProperties.getProperty(propertyName)?.takeIf { it.isNotBlank() }
}

val inlineKeystoreBase64 = System.getenv("VORTEX_SIGNING_STORE_BASE64")?.trim()?.takeIf { it.isNotEmpty() }
val configuredStorePath = signingValue("storeFile", "VORTEX_SIGNING_STORE_FILE")
val configuredStoreFile = when {
    inlineKeystoreBase64 != null -> {
        rootProject.layout.buildDirectory.file("signing/release-signing.jks").get().asFile.apply {
            parentFile.mkdirs()
            writeBytes(Base64.getDecoder().decode(inlineKeystoreBase64))
        }
    }
    configuredStorePath != null -> rootProject.file(configuredStorePath)
    else -> null
}
val configuredStorePassword = signingValue("storePassword", "VORTEX_SIGNING_STORE_PASSWORD")
val configuredKeyAlias = signingValue("keyAlias", "VORTEX_SIGNING_KEY_ALIAS")
val configuredKeyPassword = signingValue("keyPassword", "VORTEX_SIGNING_KEY_PASSWORD")

val releaseSigningConfig = if (
    configuredStoreFile == null &&
    configuredStorePassword == null &&
    configuredKeyAlias == null &&
    configuredKeyPassword == null
) {
    null
} else {
    requireNotNull(configuredStoreFile) { "Release signing requires a keystore file path or VORTEX_SIGNING_STORE_BASE64." }
    require(configuredStoreFile.exists()) { "Release signing keystore not found at ${configuredStoreFile.path}." }

    ReleaseSigningConfig(
        storeFile = configuredStoreFile,
        storePassword = requireNotNull(configuredStorePassword) {
            "Release signing requires storePassword or VORTEX_SIGNING_STORE_PASSWORD."
        },
        keyAlias = requireNotNull(configuredKeyAlias) {
            "Release signing requires keyAlias or VORTEX_SIGNING_KEY_ALIAS."
        },
        keyPassword = requireNotNull(configuredKeyPassword) {
            "Release signing requires keyPassword or VORTEX_SIGNING_KEY_PASSWORD."
        }
    )
}

android {
    namespace = "com.vortex.emulator"
    compileSdk = 35

    signingConfigs {
        if (releaseSigningConfig != null) {
            create("release") {
                storeFile = releaseSigningConfig.storeFile
                storePassword = releaseSigningConfig.storePassword
                keyAlias = releaseSigningConfig.keyAlias
                keyPassword = releaseSigningConfig.keyPassword
            }
        }
    }

    defaultConfig {
        applicationId = "com.vortex.emulator"
        minSdk = 26
        targetSdk = 35
        versionCode = 2
        versionName = "2.1-Galaxy"

        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a", "x86_64")
        }

        externalNativeBuild {
            cmake {
                cppFlags += ""
            }
        }

        vectorDrawables {
            useSupportLibrary = true
        }
    }

    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    buildTypes {
        release {
            if (releaseSigningConfig != null) {
                signingConfig = signingConfigs.getByName("release")
            }
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
        debug {
            isDebuggable = true
            applicationIdSuffix = ".debug"
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

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    // Core Android
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.core:core-splashscreen:1.0.1")

    // Compose BOM
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.compose.animation:animation")
    implementation("com.google.android.material:material:1.12.0")

    // Navigation
    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.hilt:hilt-navigation-compose:1.2.0")

    // Hilt DI
    implementation("com.google.dagger:hilt-android:2.55")
    ksp("com.google.dagger:hilt-android-compiler:2.55")

    // DataStore
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    // Coil (image loading for game covers)
    implementation("io.coil-kt:coil-compose:2.7.0")

    // Coroutines
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")

    // Room (local DB for game library)
    implementation("androidx.room:room-runtime:2.6.1")
    implementation("androidx.room:room-ktx:2.6.1")
    ksp("androidx.room:room-compiler:2.6.1")

    // DocumentFile (SAF)
    implementation("androidx.documentfile:documentfile:1.0.1")

    // OkHttp (WebSocket for lobby signaling + HTTP)
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Debugging
    debugImplementation("androidx.compose.ui:ui-tooling")
    debugImplementation("androidx.compose.ui:ui-test-manifest")
}
