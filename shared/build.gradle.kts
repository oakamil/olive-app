plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.multiplatform")
    id("org.jetbrains.kotlin.plugin.compose")
    id("org.jetbrains.compose")
}

kotlin {
    androidTarget {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }

    listOf(
        iosArm64(),
        iosSimulatorArm64()
    ).forEach { iosTarget ->
        iosTarget.compilations.getByName("main").cinterops {
            create("olivebridge") {
                defFile(project.file("src/nativeInterop/cinterop/olivebridge.def"))
                includeDirs(project.file("src/nativeInterop/cinterop/include"))
            }
        }
        iosTarget.binaries.framework(listOf(org.jetbrains.kotlin.gradle.plugin.mpp.NativeBuildType.RELEASE)) {
            baseName = "Shared"
            isStatic = true
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(compose.runtime)
            implementation(compose.foundation)
            implementation(compose.material3)
            implementation(compose.materialIconsExtended)
            implementation(compose.ui)
            api(compose.components.resources)
            implementation("org.jetbrains.androidx.lifecycle:lifecycle-viewmodel-compose:2.8.4")
            implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.9.0")
            implementation("org.jetbrains.kotlinx:kotlinx-datetime:0.6.1")
        }

        androidMain.dependencies {
            implementation("androidx.core:core-ktx:1.12.0")
            implementation("androidx.activity:activity-compose:1.8.0")
        }


        iosMain.dependencies {
        }
    }
}

android {
    namespace = "com.oakamil.olive.shared"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    variantFilter {
        if (buildType.name == "debug") {
            ignore = true
        }
    }
}

compose.resources {
    publicResClass = true
    packageOfResClass = "com.oakamil.olive"
}

tasks.matching { it.name.startsWith("cinterop") }.configureEach {
    mustRunAfter(tasks.matching { it.name.startsWith("syncComposeResources") })
}

