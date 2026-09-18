import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Present when CI has restored the release key, or when you have copied your own
// keystore here for a local signed build. Absent in a fresh clone, where the build
// falls back to the debug key so the APK still installs.
val releaseKeystore = rootProject.file("release.keystore")

android {
    namespace = "io.snailrun"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.snailrun"
        minSdk = 31
        targetSdk = 35
        // Overridable from CI so a tag drives the version: -PversionName=1.2.0 -PversionCode=7
        versionCode = (project.findProperty("versionCode") as String?)?.toInt() ?: 1
        versionName = (project.findProperty("versionName") as String?) ?: "0.1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    signingConfigs {
        if (releaseKeystore.exists()) {
            create("release") {
                storeFile = releaseKeystore
                storePassword = System.getenv("KEYSTORE_PASSWORD")
                keyAlias = System.getenv("KEY_ALIAS")
                keyPassword = System.getenv("KEY_PASSWORD")
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // An unsigned APK cannot be installed at all, so a build without the real
            // key is debug-signed rather than left unsigned. The debug key differs per
            // machine, so moving to the real key later needs one uninstall.
            signingConfig =
                signingConfigs.getByName(if (releaseKeystore.exists()) "release" else "debug")
        }
    }

    androidResources {
        // The demo map has to be readable as a file descriptor, and a compressed asset
        // is not one. It costs nothing: the file is a SQLite database full of PNGs, and
        // deflate has nothing left to take off it.
        noCompress += "mbtiles"
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    // Room's exported schemas, as assets of the debug variant only. MigrationTest opens
    // a real version 1 database with them rather than trusting the upgrade, and the
    // release APK still ships without them.
    sourceSets.getByName("debug").assets.srcDir("$projectDir/schemas")

    testOptions.unitTests {
        isIncludeAndroidResources = true
        isReturnDefaultValues = true
    }
}

// Gradle runs on the installed JDK 21 and emits Java 17 bytecode. No jvmToolchain():
// a toolchain spec makes Gradle hunt for a JDK 17 and download one it does not need.
kotlin {
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_17)
    }
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.documentfile)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    debugImplementation(libs.compose.ui.tooling)

    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)

    implementation(libs.datastore.preferences)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.room.testing)
}
