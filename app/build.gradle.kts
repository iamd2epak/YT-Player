import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.mutetube.app"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.mutetube.app"
        minSdk = 24
        targetSdk = 36
        // Bump both for every Play Store upload (versionCode must always increase).
        versionCode = 1
        versionName = "1.0.0"
    }

    // Release (upload) key: env vars (GitHub secrets) or a local, gitignored keystore.properties.
    // Never commit a keystore.
    val props = Properties().apply {
        rootProject.file("keystore.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
    }
    fun secret(env: String, prop: String): String? = System.getenv(env) ?: props.getProperty(prop)
    val keystore = secret("SIGNING_KEYSTORE", "storeFile")?.let { rootProject.file(it) }?.takeIf { it.exists() }
    signingConfigs {
        if (keystore != null) create("release") {
            storeFile = keystore
            storePassword = secret("SIGNING_STORE_PASSWORD", "storePassword")
            keyAlias = secret("SIGNING_KEY_ALIAS", "keyAlias")
            keyPassword = secret("SIGNING_KEY_PASSWORD", "keyPassword")
        }
    }

    buildTypes {
        release {
            // The stream extractor uses reflection + an embedded JS engine; keep it unminified for reliability.
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // Falls back to the debug key when no release key is configured.
            signingConfig = signingConfigs.findByName("release") ?: signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // Resolves online video stream URLs on-device.
    implementation("com.github.TeamNewPipe:NewPipeExtractor:v0.26.5")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Native video playback.
    implementation("androidx.media3:media3-exoplayer:1.4.1")
    implementation("androidx.media3:media3-exoplayer-hls:1.4.1")
    implementation("androidx.media3:media3-datasource-okhttp:1.4.1")
    implementation("androidx.media3:media3-ui:1.4.1")
    implementation("androidx.media3:media3-session:1.4.1") // background play notification

    coreLibraryDesugaring("com.android.tools:desugar_jdk_libs_nio:2.0.4")
}
