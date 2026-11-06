import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

// Keep the existing emulator app and its records while testing the corrected source.
val validationBuild = providers.gradleProperty("stepdrive.validation")
    .map(String::toBoolean).getOrElse(false)
val contestBuild = providers.gradleProperty("stepdrive.contest")
    .map(String::toBoolean).getOrElse(false)
val forceDemo = providers.gradleProperty("stepdrive.forceDemo")
    .map(String::toBoolean).getOrElse(false)
check(!(validationBuild && contestBuild)) { "Choose either stepdrive.validation or stepdrive.contest" }
check(!(contestBuild && forceDemo)) { "The contest package must use the available Pleos services" }

// Private signing material is intentionally absent from source distributions.
val keystoreProps = rootProject.file("signing/keystore.properties").takeIf { it.isFile }?.let { f ->
    Properties().apply { f.inputStream().use { load(it) } }
}
val releaseSigningReady = keystoreProps != null &&
    listOf("storeFile", "storePassword", "keyAlias", "keyPassword").all {
        !keystoreProps.getProperty(it).isNullOrBlank()
    } && rootProject.file(keystoreProps.getProperty("storeFile")).isFile

val requireReleaseSigning by tasks.registering {
    doLast {
        check(releaseSigningReady) {
            "Release requires a private keystore and complete signing/keystore.properties; debug signing is never used. See README.md."
        }
    }
}
tasks.matching { it.name == "preReleaseBuild" }.configureEach { dependsOn(requireReleaseSigning) }

android {
    namespace = "kr.stepdrive.app"
    compileSdk = 35

    defaultConfig {
        applicationId = when {
            contestBuild -> "kr.stepdrive.app.contest"
            validationBuild -> "kr.stepdrive.app.validation"
            else -> "kr.stepdrive.app"
        }
        // Pleos Connect Emulator v2.1.0 runs Android 14 (API 34); minSdk 28 follows the contest guide.
        minSdk = 28
        targetSdk = 34
        versionCode = if (contestBuild) 17 else if (validationBuild) 16 else 13
        versionName = if (contestBuild) "0.2.5-contest1" else if (validationBuild) "0.2.5-fixed-test4" else "0.2.5"
        testInstrumentationRunner = "kr.stepdrive.app.validation.ValidationInstrumentation"
        manifestPlaceholders["appLabel"] = if (contestBuild) "스텝드라이브 제출" else if (validationBuild) "스텝드라이브 검증" else "@string/app_name"

        // `-Pstepdrive.forceDemo=true` builds a variant that always replays the scripted drive
        // (handy on a plain Android emulator or tablet without Pleos services).
        buildConfigField("boolean", "FORCE_DEMO", forceDemo.toString())
    }

    signingConfigs {
        if (releaseSigningReady) {
            val props = checkNotNull(keystoreProps)
            create("release") {
                storeFile = rootProject.file(props.getProperty("storeFile"))
                storePassword = props.getProperty("storePassword")
                keyAlias = props.getProperty("keyAlias")
                keyPassword = props.getProperty("keyPassword")
            }
        }
    }
    buildTypes {
        release {
            isDebuggable = false
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("release")
        }
    }
    // Run the separate instrumentation APK against the exact signed contest release.
    testBuildType = if (contestBuild) "release" else "debug"

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions { unitTests.isReturnDefaultValues = true }
    // Android Automotive car API (speed, gear selection, UX restrictions).
    useLibrary("android.car")
    packaging {
        resources.excludes += setOf("/META-INF/{AL2.0,LGPL2.1}", "META-INF/DEPENDENCIES")
    }
}

tasks.withType<Test>().configureEach {
    val testTemp = layout.buildDirectory.dir("test-tmp")
    doFirst { testTemp.get().asFile.mkdirs() }
    systemProperty("java.io.tmpdir", testTemp.get().asFile.absolutePath)
}

dependencies {
    implementation(project(":core"))
    testImplementation(libs.junit)
    testImplementation(kotlin("test"))

    // Pleos Connect SDK — the five SDKs listed in the contest's development guide
    implementation(libs.pleos.vehicle)
    implementation(libs.pleos.navihelper)
    implementation(libs.pleos.fusedlocation)
    implementation(libs.pleos.tts)
    implementation(libs.pleos.stt)

    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
