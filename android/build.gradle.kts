// SPDX-License-Identifier: AGPL-3.0-only

// Top-level build file. Plugins are declared here with `apply false` so that every module uses
// the same version; the app module applies them in app/build.gradle.kts.

buildscript {
    dependencies {
        // AGP 9 has Kotlin support built in and brings its own (older) Kotlin Gradle plugin.
        // Declaring it here raises it to the version in the catalog, which must match the
        // Compose compiler plugin below.
        classpath(libs.kotlin.gradle.plugin)
    }
}

plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
