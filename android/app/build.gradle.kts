// SPDX-License-Identifier: AGPL-3.0-only

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    // `namespace` is the Kotlin/Java package of generated code (R, BuildConfig).
    // `applicationId` is the app's identity on a device and on Google Play; it can never change
    // after the first upload (ADR 0008).
    namespace = "com.saferoute.app"
    compileSdk {
        version = release(libs.versions.compileSdk.get().toInt())
    }

    defaultConfig {
        applicationId = "com.saferoute.app"
        minSdk = libs.versions.minSdk.get().toInt()
        targetSdk = libs.versions.targetSdk.get().toInt()
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            optimization {
                enable = false
            }
        }
        // Unsigned on purpose: there is no keystore anywhere in this repository. Release signing
        // and code shrinking (R8) arrive with P022.
        release {
            optimization {
                enable = false
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        // Generates BuildConfig (DEBUG, VERSION_NAME, VERSION_CODE), read by Home and Settings.
        buildConfig = true
    }

    androidResources {
        // Only ship the languages the app supports; drops other languages' strings from libraries.
        localeFilters += listOf("en", "bn")
    }

    lint {
        // Errors fail the build; warnings are reported but don't.
        abortOnError = true
        warningsAsErrors = false
    }

    testOptions {
        unitTests {
            // Robolectric needs the merged resources and manifest to run UI tests on the JVM.
            isIncludeAndroidResources = true
        }
    }
}

kotlin {
    // Compiles Kotlin and Java with JDK 17, whichever JDK runs Gradle itself.
    jvmToolchain(libs.versions.jdk.get().toInt())
}

dependencies {
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.core.splashscreen)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.foundation)
    implementation(libs.androidx.compose.material.icons.core)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    debugImplementation(libs.androidx.compose.ui.tooling)
    // Adds an empty activity to the debug manifest so that Compose tests have a host.
    debugImplementation(libs.androidx.compose.ui.test.manifest)

    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)
    // Navigation routes are @Serializable objects (type-safe navigation).
    implementation(libs.kotlinx.serialization.core)

    implementation(libs.hilt.android)
    implementation(libs.androidx.hilt.lifecycle.viewmodel.compose)
    ksp(libs.hilt.compiler)

    implementation(libs.kotlinx.coroutines.android)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.hilt.android.testing)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    kspTest(libs.hilt.compiler)
}
