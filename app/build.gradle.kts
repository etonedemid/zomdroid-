import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
}

val localProperties = Properties().apply {
    val file = rootProject.file("local.properties")
    if (file.exists()) {
        file.inputStream().use { load(it) }
    }
}

fun signingProp(key: String): String? =
    System.getenv(key) ?: localProperties[key]?.toString()

val releaseStoreFile = signingProp("RELEASE_STORE_FILE")
val releaseStorePassword = signingProp("RELEASE_STORE_PASSWORD")
val releaseKeyAlias = signingProp("RELEASE_KEY_ALIAS")
val releaseKeyPassword = signingProp("RELEASE_KEY_PASSWORD")

val hasSigningConfig = listOf(releaseStoreFile, releaseStorePassword, releaseKeyAlias, releaseKeyPassword)
    .all { it != null }

android {
    namespace = "com.zomdroid"
    compileSdk = 35

    signingConfigs {
        if (hasSigningConfig) {
            create("release") {
                storeFile = file(requireNotNull(releaseStoreFile) { "RELEASE_STORE_FILE must not be null" })
                storePassword = requireNotNull(releaseStorePassword) { "RELEASE_STORE_PASSWORD must not be null" }
                keyAlias = requireNotNull(releaseKeyAlias) { "RELEASE_KEY_ALIAS must not be null" }
                keyPassword = requireNotNull(releaseKeyPassword) { "RELEASE_KEY_PASSWORD must not be null" }
            }
        }
    }

    defaultConfig {
        applicationId = "com.zomdroid"
        minSdk = 30
        targetSdk = 35
        versionCode = 4
        versionName = "1.4"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters.add("arm64-v8a")
        }
    }

    applicationVariants.all {
        val variant = this
        outputs.all {
            val outputImpl = this as com.android.build.gradle.internal.api.BaseVariantOutputImpl
            outputImpl.outputFileName = "zomdroid-${variant.buildType.name}-${variant.versionName}.apk"
        }
    }

    buildTypes {
        if (hasSigningConfig) {
            release {
                isMinifyEnabled = false
                proguardFiles(
                    getDefaultProguardFile("proguard-android-optimize.txt"),
                    "proguard-rules.pro"
                )
                signingConfig = signingConfigs.getByName("release")
            }
        }
        debug {
            isDebuggable = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        viewBinding = true
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }
    ndkVersion = "28.0.13004108"
}

dependencies {
    implementation(libs.gson)
    implementation(files("jars/fmod.jar"))
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.constraintlayout)
    implementation(libs.navigation.fragment)
    implementation(libs.navigation.ui)
    implementation(libs.commons.io)
    implementation(libs.commons.compress)
    implementation(libs.xz)
    implementation(libs.legacy.support.v4)
    testImplementation(libs.junit)
    androidTestImplementation(libs.ext.junit)
    androidTestImplementation(libs.espresso.core)
}