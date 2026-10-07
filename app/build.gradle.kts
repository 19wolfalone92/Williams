import java.io.FileInputStream
import java.util.Properties
import java.util.Base64
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val keystorePropertiesFile = rootProject.file("keystore.properties")
val keystoreProperties = Properties()
if (keystorePropertiesFile.exists()) {
    FileInputStream(keystorePropertiesFile).use { keystoreProperties.load(it) }
}

val ciRunNumber = System.getenv("GITHUB_RUN_NUMBER")?.toIntOrNull()
val resolvedVersionCode = maxOf(10, ciRunNumber ?: 10)

val debugKeystoreFile = rootProject.file("build/williams-debug.p12")
val debugKeystorePassword = System.getenv("WILLIAMS_DEBUG_KEYSTORE_PASSWORD")
val debugKeystoreBase64 = System.getenv("WILLIAMS_DEBUG_KEYSTORE_B64")

if (!debugKeystoreBase64.isNullOrBlank() && !debugKeystorePassword.isNullOrBlank()) {
    debugKeystoreFile.parentFile.mkdirs()
    if (!debugKeystoreFile.exists()) {
        Base64.getDecoder().decode(debugKeystoreBase64).let {
            debugKeystoreFile.writeBytes(it)
        }
    }
}

android {
    namespace = "com.williamsbot"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.williamsbot"
        minSdk = 26
        targetSdk = 36
        versionCode = resolvedVersionCode
        versionName = "4.22.2"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlin {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_17)
        }
    }

    buildTypes {
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            isDebuggable = true

            if (debugKeystoreFile.exists() && !debugKeystorePassword.isNullOrBlank()) {
                signingConfig = signingConfigs.create("williamsDebug") {
                    storeFile = debugKeystoreFile
                    storePassword = debugKeystorePassword
                    storeType = "PKCS12"
                    keyAlias = "williams-debug"
                    keyPassword = debugKeystorePassword
                }
            }
        }

        release {
            isMinifyEnabled = false
            isShrinkResources = false
            isDebuggable = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )

            if (keystorePropertiesFile.exists()) {
                signingConfig = signingConfigs.create("release") {
                    storeFile = rootProject.file(
                        keystoreProperties.getProperty("storeFile")
                    )
                    storePassword = keystoreProperties.getProperty("storePassword")
                    keyAlias = keystoreProperties.getProperty("keyAlias")
                    keyPassword = keystoreProperties.getProperty("keyPassword")
                }
            }
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        // API 36 is the current stable Android target used by the project.
        // Lint also knows about API 37 preview and otherwise raises
        // OldTargetApi even though we intentionally do not ship against
        // the preview SDK yet.
        disable += "OldTargetApi"
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.19.1")
    implementation("androidx.activity:activity-compose:1.13.0")
    implementation(platform("androidx.compose:compose-bom:2026.09.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.10.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")
    implementation("androidx.security:security-crypto:1.1.0")
    implementation("com.google.android.gms:play-services-code-scanner:16.1.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
