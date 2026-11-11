plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
    id("com.google.protobuf")
}

android {
    namespace = "com.oakamil.olive"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.oakamil.olive"
        minSdk = 29
        targetSdk = 36
        versionCode = 37
        versionName = "0.9.0"
    }

    buildFeatures {
        compose = true
    }

    androidResources {
        noCompress += listOf("npz", "bsp", "db")
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
            isMinifyEnabled = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("debug")
            ndk {
                debugSymbolLevel = "FULL"
            }
        }
    }
}

dependencies {
    implementation(project(":shared"))
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.6.2")
    implementation("androidx.activity:activity-compose:1.8.0")
    implementation(platform("androidx.compose:compose-bom:2025.12.01"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-graphics")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    implementation("androidx.graphics:graphics-shapes:1.0.0")
    implementation("com.github.requery:sqlite-android:3.49.0")
    implementation("com.github.ernestp:AndroidUSBCamera:3.6.0")
    
    // gRPC
    implementation("io.grpc:grpc-okhttp:1.66.0")
    implementation("io.grpc:grpc-protobuf-lite:1.66.0")
    implementation("io.grpc:grpc-kotlin-stub:1.4.1")
    implementation("io.grpc:grpc-stub:1.66.0")
    implementation("com.google.protobuf:protobuf-kotlin-lite:4.26.1")
    compileOnly("org.apache.tomcat:annotations-api:6.0.53")
    testImplementation("junit:junit:4.13.2")
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.26.1"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.66.0"
        }
        create("grpckotlin") {
            artifact = "io.grpc:protoc-gen-grpc-kotlin:1.4.1:jdk8@jar"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.builtins {
                create("java") {
                    option("lite")
                }
                create("kotlin") {
                    option("lite")
                }
            }
        }
    }
}

// Custom task to compile rust and copy to jniLibs
tasks.register<Exec>("cargoBuild") {
    // Target arm64 and armeabi-v7a
    val projectDir = file("src/main/rust")

    workingDir(projectDir)
    environment("ANDROID_NDK_HOME", "/Users/oakamil/Library/Android/sdk/ndk/28.2.13676358")
    environment("CMAKE_TOOLCHAIN_FILE_aarch64_linux_android", "/Users/oakamil/Library/Android/sdk/ndk/28.2.13676358/build/cmake/android.toolchain.cmake")
    environment("CMAKE_TOOLCHAIN_FILE_armv7_linux_androideabi", "/Users/oakamil/Library/Android/sdk/ndk/28.2.13676358/build/cmake/android.toolchain.cmake")
    environment("CMAKE_MAKE_PROGRAM", "/opt/homebrew/bin/ninja")
    commandLine("/Users/oakamil/.cargo/bin/cargo", "ndk", "-P", "24", "-t", "armeabi-v7a", "-t", "arm64-v8a", "-o", "../jniLibs", "build", "--release")
}

tasks.matching { it.name == "mergeDebugJniLibFolders" || it.name == "mergeReleaseJniLibFolders" }.configureEach {
    dependsOn("cargoBuild")
}