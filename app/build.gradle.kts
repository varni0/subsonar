import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Release signing: credentials live OUTSIDE the repo. Lookup order: -PsubsonarKeystoreProps=/path/to/keystore.properties,
// ../subsonar-keystore/keystore.properties, then the legacy ../subpilot-keystore/keystore.properties (retired key; fallback only).
val keystorePropsFile = listOfNotNull(
    (findProperty("subsonarKeystoreProps") ?: findProperty("subpilotKeystoreProps")) as String?,
    "../subsonar-keystore/keystore.properties",
    "../subpilot-keystore/keystore.properties",
).map { rootProject.file(it) }.firstOrNull { it.exists() } ?: rootProject.file("../subsonar-keystore/keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use { load(it) }
}

android {
    namespace = "app.subsonar"
    compileSdk = 37

    defaultConfig {
        applicationId = "app.subsonar"
        minSdk = 26
        targetSdk = 37
        versionCode = 2
        versionName = "1.1.0"

        // ---- Monetization feature flags (see MONETIZATION.md) ----
        // false = everything unlocked, no paywall, no ads (v1 "growth" phase).
        buildConfigField("boolean", "MONETIZATION_ENABLED", "false")
        buildConfigField("boolean", "ADS_ENABLED", "false")
        // Free-tier item cap once monetization is on (ignored while MONETIZATION_ENABLED=false).
        buildConfigField("int", "FREE_SUBSCRIPTION_LIMIT", "10")

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (keystoreProps.getProperty("storeFile") != null) {
            create("release") {
                storeFile = file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfigs.findByName("release")?.let { signingConfig = it }
        }
        debug {
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    testOptions { unitTests { isIncludeAndroidResources = true } }
    // Exported Room schemas are visible to MigrationTestHelper in Robolectric unit tests (debug variant only; not shipped in release).
    sourceSets { getByName("debug").assets.srcDir("$projectDir/schemas") }

    // Keep every language in the bundle: the in-app language picker must work for languages that are not the device language
    // (with per-language splits Play would only deliver the device languages on Android < 13).
    bundle { language { enableSplit = false } }

    lint {
        abortOnError = true
        // A missing or surplus translation must never reach Play (see docs/LOCALIZATION.md).
        error += setOf("MissingTranslation", "ExtraTranslation", "StringFormatInvalid", "StringFormatCount", "StringFormatMatches", "MissingQuantity", "UnusedQuantity")
        checkReleaseBuilds = true
        // Version-bump nags are informational; dependencies are pinned in gradle/libs.versions.toml.
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion")
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat) // per-app language (AppCompatDelegate.setApplicationLocales) incl. API < 33
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.navigation.compose)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)

    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    implementation(libs.androidx.work.runtime.ktx)
    implementation(libs.androidx.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.json)

    // Robolectric: used for the Room migration test and the screenshot renderer (no emulator needed).
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.test.ext.junit)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.work.testing)
    testImplementation(platform(libs.androidx.compose.bom))
    testImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}

tasks.withType<Test>().configureEach {
    // Robolectric needs reflective access on JDK 17.
    jvmArgs(
        listOf("lang", "io", "util", "text", "net", "nio", "lang.reflect", "util.concurrent", "time").map { "--add-opens=java.base/java.$it=ALL-UNNAMED" } +
            listOf("--add-opens=java.base/sun.nio.ch=ALL-UNNAMED", "--add-opens=java.base/jdk.internal.access=ALL-UNNAMED"),
    )
    systemProperty("robolectric.graphicsMode", "NATIVE")
    // Optional: location of the full (incl. GPL) sample corpus; GPL samples are never copied into the repo.
    systemProperty("csvSamples.dir", findProperty("csvSamples.dir")?.toString() ?: "/workspace/csv-samples")
    // Opt-in screenshot rendering: ./gradlew testDebugUnitTest -Pshots.out=/tmp/shots --tests '*ImportScreenshots*'
    findProperty("shots.out")?.let { systemProperty("shots.out", it.toString()) }
    // Opt-in Play Store renders (StoreScreenshotsTest): -Pstore.shots=DIR [-Pstore.name=ProductName]
    findProperty("store.shots")?.let { systemProperty("store.shots", it.toString()) }
    findProperty("store.name")?.let { systemProperty("store.name", it.toString()) }
    maxHeapSize = "3g"
}
