import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.devtools.ksp")
}

ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

val keystorePropsFile = rootProject.file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) keystorePropsFile.inputStream().use(::load)
}
val requiredSigningKeys = listOf("storeFile", "storePassword", "keyAlias", "keyPassword")
val releaseSigningReady = keystorePropsFile.exists() &&
    requiredSigningKeys.all { key ->
        !keystoreProps.getProperty(key).isNullOrBlank() && keystoreProps.getProperty(key) != "CHANGE_ME"
    } &&
    // The keystore file itself must exist, otherwise packaging would fail late and confusingly.
    rootProject.file(keystoreProps.getProperty("storeFile") ?: "").exists()

/**
 * Explicit, user-facing reason a distributable release artifact cannot be produced.
 * Non-packaging release tasks (lintRelease, testReleaseUnitTest) stay runnable without secrets.
 */
val RELEASE_SIGNING_ERROR =
    "Production release signing belum dikonfigurasi. Isi keystore.properties " +
        "dengan keystore produksi sebelum membuat APK/AAB release."

android {
    namespace = "com.trapezo.pos"
    compileSdk = 36

    sourceSets {
        // Room schema export location, required by MigrationTestHelper.
        getByName("androidTest").assets.srcDirs("$projectDir/schemas")
    }

    defaultConfig {
        applicationId = "com.trapezo.pos"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "1.0.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        vectorDrawables { useSupportLibrary = true }
    }

    signingConfigs {
        if (releaseSigningReady) {
            create("release") {
                storeFile = rootProject.file(keystoreProps.getProperty("storeFile"))
                storePassword = keystoreProps.getProperty("storePassword")
                keyAlias = keystoreProps.getProperty("keyAlias")
                keyPassword = keystoreProps.getProperty("keyPassword")
            }
        }
    }

    buildTypes {
        getByName("release") {
            // R8 + resource shrinking for the production artifact.
            isMinifyEnabled = true
            isShrinkResources = true
            isDebuggable = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (releaseSigningReady) signingConfig = signingConfigs.getByName("release")
        }
        getByName("debug") {
            applicationIdSuffix = ".debug"
        }
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    packaging {
        resources { excludes += "/META-INF/{AL2.0,LGPL2.1}" }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

/**
 * FAIL CLOSED: a distributable release artifact must never be produced without real
 * production signing. Only PACKAGING tasks are gated — `lintRelease`,
 * `testReleaseUnitTest` and other release analysis tasks stay runnable without secrets.
 */
if (!releaseSigningReady) {
    tasks.configureEach {
        val gated = name == "packageRelease" ||
            name == "assembleRelease" ||
            name == "bundleRelease" ||
            name == "packageReleaseBundle"
        if (gated) {
            doFirst { throw GradleException(RELEASE_SIGNING_ERROR) }
        }
    }
}

dependencies {
    implementation(platform("androidx.compose:compose-bom:2026.02.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")

    implementation("androidx.core:core-ktx:1.16.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("androidx.activity:activity-compose:1.8.2")

    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.9.4")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.9.4")

    implementation("androidx.navigation:navigation-compose:2.8.5")
    implementation("androidx.datastore:datastore-preferences:1.1.1")

    val roomVersion = "2.7.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    val cameraxVersion = "1.6.1"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.9.0")
    testImplementation("junit:junit:4.13.2")

    androidTestImplementation("junit:junit:4.13.2")
    androidTestImplementation("androidx.test:core:1.6.1")
    androidTestImplementation("androidx.test:runner:1.5.2")
    androidTestImplementation("androidx.test:monitor:1.7.2")
    androidTestImplementation("androidx.test.ext:junit:1.1.5")
    androidTestImplementation("androidx.room:room-testing:2.7.1")

    debugImplementation("androidx.compose.ui:ui-tooling")
}
