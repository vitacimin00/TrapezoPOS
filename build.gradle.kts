// Top-level build file for Trapezo POS
plugins {
    id("com.android.application") version "9.3.1" apply false
    // AGP 9.3 uses its integrated Kotlin 2.2 toolchain; do not apply Kotlin Android twice.
    id("org.jetbrains.kotlin.plugin.compose") version "2.2.10" apply false
    id("com.google.devtools.ksp") version "2.2.10-2.0.2" apply false
}
