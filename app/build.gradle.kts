plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

fun quoted(property: String, default: String = ""): String {
    val value = providers.gradleProperty(property).getOrElse(default)
    require(value.all { it.isLetterOrDigit() || it in ".-_" }) { "$property has unexpected characters" }
    return "\"$value\""
}

fun cloudProjectNumber(): Long {
    val value = providers.gradleProperty("zapakid.playCloudProjectNumber").getOrElse("64042746735")
    return requireNotNull(value.toLongOrNull()) { "zapakid.playCloudProjectNumber must be a number" }
}

android {
    namespace = "ro.zapakid.app"
    compileSdk = 37
    // Latest stable NDK, used only to strip native symbols (libsignal ships unstripped .so files).
    ndkVersion = "30.0.16248370"

    defaultConfig {
        // App id decided 2026-10-26 (its own Play Console app). test/app-id.test.js keeps the old one out.
        applicationId = "ro.zapakid.app"
        minSdk = 26
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        // Public identifiers, not secrets. Override with -P or ~/.gradle/gradle.properties; empty = not set up.
        buildConfigField("String", "API_BASE_URL", "\"https://api-staging.zapakid.ro/v1/\"")
        // The OAuth web (server) client the API checks the token audience against. The Android OAuth client
        // (package ro.zapakid.app + signing SHA-1) is registered in Google Cloud; it isn't referenced in code.
        buildConfigField(
            "String",
            "GOOGLE_SERVER_CLIENT_ID",
            quoted(
                "zapakid.googleServerClientId",
                default = "64042746735-r70nv5pmlro7hpksk1te3ur008ungss1.apps.googleusercontent.com"
            )
        )
        buildConfigField(
            "String",
            "APPLE_SERVICES_ID",
            quoted("zapakid.appleServicesId", default = "ro.zapakid.signin")
        )
        // Google Cloud project linked to the Play app ro.zapakid.app for Play Integrity (public). 0 = no token.
        buildConfigField("long", "PLAY_CLOUD_PROJECT_NUMBER", "${cloudProjectNumber()}L")
        buildConfigField(
            "String",
            "APPLE_REDIRECT_URI",
            "\"https://api-staging.zapakid.ro/v1/auth/apple/callback\""
        )
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        // libsignal-client is built for Java 21.
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
        // Required by libsignal-android (java.time and friends on API 26).
        isCoreLibraryDesugaringEnabled = true
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        // libsignal ships extra natives for its own tests; they must never reach the app.
        jniLibs.excludes += setOf("**/libsignal_jni_testing.so")
        // libsignal-client also carries desktop natives (macOS, Linux, Windows) as Java resources, for JVM unit tests.
        resources.excludes += setOf("libsignal_jni*.dylib", "libsignal_jni*.so", "signal_jni*.dll")
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        checkDependencies = true
        // Version bumps come from Dependabot, not from lint.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }

    sourceSets {
        // Fakes shared by the JVM tests and the emulator tests (stub backend, in-memory session, fake sign-in sheets).
        getByName("test").kotlin.directories.add("src/sharedTest/java")
        getByName("androidTest").kotlin.directories.add("src/sharedTest/java")
    }

    testOptions {
        unitTests.isReturnDefaultValues = false
    }
}

kotlin {
    jvmToolchain(21)
}

// Contract test: the JVM tests record their HTTP exchanges here; scripts/contract-check.js checks them against the
// API's openapi.yaml (pinned in contract/api-commit).
tasks.withType<Test>().configureEach {
    val exchanges = layout.buildDirectory.file("contract/exchanges.jsonl")
    systemProperty("contract.out", exchanges.get().asFile.path)
    doFirst { exchanges.get().asFile.delete() }
}

detekt {
    buildUponDefaultConfig = true
    config.setFrom(rootProject.file("config/detekt/detekt.yml"))
}

ktlint {
    version.set(libs.versions.ktlint.cli)
    android.set(true)
}

dependencies {
    implementation(project(":calls"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.credentials)
    implementation(libs.androidx.credentials.play.services)
    implementation(libs.googleid)
    implementation(libs.play.age.signals)
    implementation(libs.play.integrity)
    implementation(libs.libsignal.android)
    implementation(libs.play.services.blockstore)
    coreLibraryDesugaring(libs.desugar.jdk.libs)
    implementation(libs.androidx.browser)
    implementation(libs.okhttp)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.coroutines.play.services)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)

    testImplementation(libs.junit)
    testImplementation(libs.okhttp.mockwebserver)
    testImplementation(libs.kotlinx.coroutines.test)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.junit)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.espresso.core)
    androidTestImplementation(libs.kotlinx.coroutines.test)
}
