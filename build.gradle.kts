// Top-level build file. AGP 9 ships a built-in Kotlin compiler; the explicit
// Kotlin Gradle Plugin on the classpath only upgrades that compiler.
buildscript {
    repositories {
        google()
        mavenCentral()
    }
    dependencies {
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.20")
    }
}

plugins {
    alias(libs.plugins.androidApplication) apply false
}
