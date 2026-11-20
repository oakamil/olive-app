import java.io.File
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android {
    namespace = "com.oakamil.olive"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.oakamil.olive"
        minSdk = 29
        targetSdk = 36
        versionCode = 42
        versionName = "0.9.4"
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

androidComponents {
    beforeVariants(selector().withBuildType("debug")) { variantBuilder ->
        variantBuilder.enable = false
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
    implementation("androidx.compose.material3:material3")
    implementation("com.github.requery:sqlite-android:3.49.0")
    implementation("com.github.ernestp:AndroidUSBCamera:3.6.0")
    testImplementation("junit:junit:4.13.2")
}

// Custom task to compile rust and copy to jniLibs
tasks.register<Exec>("cargoBuild") {
    // Target arm64 and armeabi-v7a
    val rustDir = file("src/main/rust")
    val userHome = System.getProperty("user.home")
    val localProperties = Properties().apply {
        val file = rootProject.file("local.properties")
        if (file.isFile) {
            file.inputStream().use { load(it) }
        }
    }

    fun resolveSdkDir(): File {
        val candidates = listOfNotNull(
            System.getenv("ANDROID_HOME")?.takeIf { it.isNotBlank() }?.let(::File),
            System.getenv("ANDROID_SDK_ROOT")?.takeIf { it.isNotBlank() }?.let(::File),
            localProperties.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }?.let(::File),
            File(userHome, "Library/Android/sdk"),
            File(userHome, "Android/Sdk")
        )
        return candidates.firstOrNull { it.isDirectory } ?: File(userHome, "Library/Android/sdk")
    }

    fun resolveNdkDir(): String {
        val explicit = listOfNotNull(
            System.getenv("ANDROID_NDK_HOME")?.takeIf { it.isNotBlank() },
            System.getenv("ANDROID_NDK_ROOT")?.takeIf { it.isNotBlank() },
            localProperties.getProperty("ndk.dir")?.takeIf { it.isNotBlank() }
        ).firstOrNull { File(it).isDirectory }
        if (explicit != null) return explicit

        val ndkParent = File(resolveSdkDir(), "ndk")
        val preferred = File(ndkParent, "28.2.13676358")
        if (preferred.isDirectory) return preferred.absolutePath

        val latest = ndkParent.listFiles()
            ?.filter { it.isDirectory }
            ?.maxWithOrNull(Comparator { a, b ->
                val aParts = a.name.split('.').mapNotNull { it.toLongOrNull() }
                val bParts = b.name.split('.').mapNotNull { it.toLongOrNull() }
                val maxLen = maxOf(aParts.size, bParts.size)
                for (i in 0 until maxLen) {
                    val cmp = aParts.getOrElse(i) { 0L }.compareTo(bParts.getOrElse(i) { 0L })
                    if (cmp != 0) return@Comparator cmp
                }
                a.name.compareTo(b.name)
            })
        return latest?.absolutePath ?: preferred.absolutePath
    }

    fun resolveExecutable(envVar: String, vararg candidates: String): String {
        val fromEnv = System.getenv(envVar)?.takeIf { it.isNotBlank() }
        if (fromEnv != null) return fromEnv

        candidates.firstOrNull { File(it).canExecute() }?.let { return it }

        val binaryName = File(candidates.last()).name
        val pathDirs = System.getenv("PATH")?.split(File.pathSeparator).orEmpty()
        pathDirs.map { File(it, binaryName) }.firstOrNull { it.canExecute() }?.let {
            return it.absolutePath
        }
        return binaryName
    }

    val ndkDir = resolveNdkDir()
    val toolchainFile = File(ndkDir, "build/cmake/android.toolchain.cmake").absolutePath
    val ninjaProgram = resolveExecutable(
        "CMAKE_MAKE_PROGRAM",
        "/opt/homebrew/bin/ninja",
        "/usr/local/bin/ninja",
        "/usr/bin/ninja"
    )
    val cargoProgram = resolveExecutable("CARGO", "$userHome/.cargo/bin/cargo")

    workingDir(rustDir)
    environment("ANDROID_NDK_HOME", ndkDir)
    environment("CMAKE_TOOLCHAIN_FILE_aarch64_linux_android", toolchainFile)
    environment("CMAKE_TOOLCHAIN_FILE_armv7_linux_androideabi", toolchainFile)
    environment("CMAKE_MAKE_PROGRAM", ninjaProgram)
    commandLine(cargoProgram, "ndk", "-P", "24", "-t", "armeabi-v7a", "-t", "arm64-v8a", "-o", "../jniLibs", "build", "--release")
}

tasks.matching { it.name == "mergeReleaseJniLibFolders" }.configureEach {
    dependsOn("cargoBuild")
}