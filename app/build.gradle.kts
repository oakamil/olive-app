import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.oakamil.olive"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.oakamil.olive"
        minSdk = 24
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildFeatures {
        compose = true
    }

    composeOptions {
        kotlinCompilerExtensionVersion = "1.5.1"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }

    sourceSets {
        getByName("main") {
            jniLibs.srcDir("src/main/jniLibs")
        }
    }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation(platform("androidx.compose:compose-bom:2023.08.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.graphics:graphics-shapes:1.0.0")
}

// Custom task to compile rust and copy to jniLibs
tasks.register<Exec>("cargoBuild") {
    // Only target arm64 for now to keep things simple
    val rustTarget = "aarch64-linux-android"
    val rustArchName = "arm64-v8a"
    val projectDir = file("src/main/rust")
    val jniDir = file("src/main/jniLibs/\${rustArchName}")

    workingDir(projectDir)
    environment("ANDROID_NDK_HOME", "/Users/oakamil/Library/Android/sdk/ndk/28.2.13676358")
    environment("CMAKE_TOOLCHAIN_FILE_aarch64_linux_android", "/Users/oakamil/Library/Android/sdk/ndk/28.2.13676358/build/cmake/android.toolchain.cmake")
    environment("CMAKE_MAKE_PROGRAM", "/opt/homebrew/bin/ninja")
    commandLine("/Users/oakamil/.cargo/bin/cargo", "ndk", "-P", "24", "-t", rustArchName, "-o", "../jniLibs", "build", "--release")
}

tasks.whenTaskAdded {
    if (name == "mergeDebugJniLibFolders" || name == "mergeReleaseJniLibFolders") {
        dependsOn("cargoBuild")
    }
}