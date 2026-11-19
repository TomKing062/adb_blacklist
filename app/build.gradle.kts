plugins {
    alias(libs.plugins.androidApplication)
}

android {
    namespace = "com.qutick.adbblacklist"
    compileSdk = 37
    buildToolsVersion = "37.0.0"

    defaultConfig {
        applicationId = "com.qutick.adbblacklist"
        // Android 11-17 share the same ADB debugging manager APIs used here.
        // Current testing is only done on Android 17.
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            // Hook entry points and class names must survive for LSPosed.
            isMinifyEnabled = false
        }
    }

    buildFeatures {
        buildConfig = false
    }

    androidResources {
        localeFilters += listOf("en", "zh")
    }

    dependenciesInfo {
        includeInApk = false
        includeInBundle = false
    }

    packaging {
        resources {
            excludes += setOf(
                "META-INF/*.version",
                "META-INF/LICENSE*",
                "META-INF/NOTICE*",
                "META-INF/native-image/**",
                "META-INF/version-control-info.textproto",
                "kotlin-tooling-metadata.json",
                "kotlin/**",
                "DebugProbesKt.bin",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xno-param-assertions",
            "-Xno-call-assertions",
            "-Xno-receiver-assertions",
        )
    }
}

configurations.all {
    exclude("org.jetbrains.kotlin", "kotlin-stdlib-jdk7")
    exclude("org.jetbrains.kotlin", "kotlin-stdlib-jdk8")
}

dependencies {
    implementation(platform(libs.kotlin.bom))
    implementation(libs.core)
    compileOnly(libs.libxposed.api)
    implementation(libs.appcompat)
    implementation(libs.material)
    implementation(libs.activity)
}
