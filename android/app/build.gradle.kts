// SPDX-License-Identifier: AGPL-3.0-only

import groovy.json.JsonSlurper
import org.openapitools.generator.gradle.plugin.tasks.GenerateTask

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
    alias(libs.plugins.openapi.generator)
    // Reads app/google-services.json (never committed) and generates the string resources
    // Firebase needs at start-up. The build fails without the file: see android/README.md
    // "Signing in" for where it goes and how CI gets a dummy one (ADR 0012).
    alias(libs.plugins.google.services)
}

// The API client is generated from the contract on every build and is never committed or edited
// (ADR 0009). Retrofit interfaces and @Serializable models land in build/generated/openapi. The
// only supporting files kept are the ones that code imports (the UUID and date-time serializers
// and CollectionFormats); the generator's own ApiClient, docs, tests and Gradle project are not
// generated.
val generatedApiDir = layout.buildDirectory.dir("generated/openapi")
val generateApiClient = tasks.register<GenerateTask>("generateApiClient") {
    group = "build"
    description = "Generates the Kotlin API client from contracts/openapi.json."
    generatorName.set("kotlin")
    library.set("jvm-retrofit2")
    inputSpec.set(rootProject.layout.projectDirectory.file("../contracts/openapi.json"))
    outputDir.set(generatedApiDir)
    packageName.set("com.saferoute.app.core.network.generated")
    apiPackage.set("com.saferoute.app.core.network.generated.api")
    modelPackage.set("com.saferoute.app.core.network.generated.model")
    configOptions.set(
        mapOf(
            "serializationLibrary" to "kotlinx_serialization",
            "useCoroutines" to "true",
            // Response<T> instead of T: the caller sees the status (200 vs 201) and error bodies.
            "useResponseAsReturnType" to "true",
            "dateLibrary" to "java8",
            "omitGradleWrapper" to "true",
        ),
    )
    globalProperties.set(
        mapOf(
            "apis" to "",
            "models" to "",
            "supportingFiles" to "CollectionFormats.kt,OffsetDateTimeAdapter.kt,UUIDAdapter.kt",
            "apiDocs" to "false",
            "modelDocs" to "false",
            "apiTests" to "false",
            "modelTests" to "false",
        ),
    )
    cleanupOutput.set(true)
}

// The API base URL is not in the repository. It comes from the Gradle property
// `saferoute.apiBaseUrl`, which each developer keeps in their user-level gradle.properties (or
// CI passes with -P). Without it the build uses a placeholder that resolves nowhere, and the app
// reports "no server configured". The same rule is checked again at runtime by
// core/network/ApiConfig.kt, which is where it is unit-tested.
val apiBaseUrlPlaceholder = "https://api.invalid/"
val apiBaseUrlProperty = providers.gradleProperty("saferoute.apiBaseUrl").orNull?.trim().orEmpty()
val apiBaseUrlConfigured = apiBaseUrlProperty.isNotEmpty()
val apiBaseUrl = if (apiBaseUrlConfigured) apiBaseUrlProperty else apiBaseUrlPlaceholder
if (!Regex("""https://[^\s"\\]+/""").matches(apiBaseUrl)) {
    // The value itself is deliberately not printed.
    throw GradleException(
        "saferoute.apiBaseUrl is malformed: it must start with https:// and end with / " +
            "(for example https://host.example/), without spaces, quotes or backslashes.",
    )
}

// A release build must never ship pointing at the placeholder. This fails when a release task
// runs, not at configuration time, so debug builds and Android Studio sync work without the
// property.
val checkReleaseApiBaseUrl = tasks.register("checkReleaseApiBaseUrl") {
    group = "verification"
    description = "Fails a release build that has no saferoute.apiBaseUrl."
    val configured = apiBaseUrlConfigured
    doLast {
        if (!configured) {
            throw GradleException(
                "Release builds need the Gradle property saferoute.apiBaseUrl " +
                    "(user-level gradle.properties or -Psaferoute.apiBaseUrl=https://.../). " +
                    "See android/README.md.",
            )
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(checkReleaseApiBaseUrl) }

// A release build must never ship with the dummy Firebase configuration that CI writes
// (android/scripts/write-dummy-google-services, project id starting with `demo-`): sign-in
// would not work for anyone. The only build allowed to do so is the CI check that a release
// still assembles, which passes -Psaferoute.allowDummyFirebase=true (ADR 0012).
// The file's values are never printed.
val checkReleaseFirebaseConfig = tasks.register("checkReleaseFirebaseConfig") {
    group = "verification"
    description = "Fails a release build without a real google-services.json."
    val configFile = layout.projectDirectory.file("google-services.json").asFile
    val allowDummy = providers.gradleProperty("saferoute.allowDummyFirebase").orNull == "true"
    doLast {
        if (allowDummy) return@doLast
        val help = "See android/README.md, \"Signing in\"."
        if (!configFile.isFile) {
            throw GradleException("Release builds need app/google-services.json. $help")
        }
        val projectId = try {
            val root = JsonSlurper().parse(configFile) as? Map<*, *>
            (root?.get("project_info") as? Map<*, *>)?.get("project_id") as? String
        } catch (e: Exception) {
            null
        }
        if (projectId.isNullOrBlank()) {
            throw GradleException("app/google-services.json has no project id. $help")
        }
        if (projectId.startsWith("demo-")) {
            throw GradleException(
                "Release builds must not use the dummy Firebase configuration (a demo- " +
                    "project). $help",
            )
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach {
    dependsOn(checkReleaseFirebaseConfig)
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

        // Read by core/network. The URL is never shown or logged; the UI may only say whether
        // one is configured.
        buildConfigField("String", "API_BASE_URL", "\"$apiBaseUrl\"")
        buildConfigField("boolean", "API_BASE_URL_CONFIGURED", apiBaseUrlConfigured.toString())
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
        // Generates BuildConfig (DEBUG, VERSION_NAME, VERSION_CODE, API_BASE_URL), read by Home,
        // Settings and core/network.
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

androidComponents {
    onVariants { variant ->
        // Adds the generated folder to every variant's Kotlin sources. AGP also makes each task
        // that reads the sources (KSP, compile, lint) depend on generateApiClient, so a build
        // always uses the current contract.
        variant.sources.kotlin?.addGeneratedSourceDirectory(generateApiClient, GenerateTask::outputDir)
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

    // Sign-in (ADR 0012). Firebase Auth only: no Analytics, Crashlytics or App Check.
    // Only core/auth uses Firebase; the rest of the app sees PhoneAuthGateway.
    implementation(platform(libs.firebase.bom))
    implementation(libs.firebase.auth)
    // Lets a coroutine wait for a Play services Task with `.await()`.
    implementation(libs.kotlinx.coroutines.play.services)
    // Small key-value storage for the onboarding and session flags (core/session).
    implementation(libs.androidx.datastore.preferences)

    // Networking. Only core/network uses these (ADR 0009).
    implementation(libs.okhttp)
    implementation(libs.retrofit)
    implementation(libs.retrofit.converter.kotlinx.serialization)
    implementation(libs.kotlinx.serialization.json)

    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.hilt.android.testing)
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.robolectric)
    testImplementation(libs.turbine)
    kspTest(libs.hilt.compiler)
}
