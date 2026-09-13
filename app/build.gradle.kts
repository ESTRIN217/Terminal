import java.util.regex.Pattern

plugins {
    id("com.android.application")
    alias(libs.plugins.jetbrains.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val packageVariant: String = System.getenv("TERMUX_PACKAGE_VARIANT") ?: "apt-android-7"
val appVersionName = System.getenv("TERMUX_APP_VERSION_NAME") ?: ""
val apkVersionTag = System.getenv("TERMUX_APK_VERSION_TAG") ?: ""
val splitAPKsForDebugBuilds = System.getenv("TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS") ?: "1"
val splitAPKsForReleaseBuilds = System.getenv("TERMUX_SPLIT_APKS_FOR_RELEASE_BUILDS") ?: "0"

android {
    namespace = "com.termux"

    compileSdk { version = release(37) { minorApiLevel = 1 } }
    ndkVersion = System.getenv("JITPACK_NDK_VERSION") ?: project.properties["ndkVersion"]?.toString() ?: ""

    dependencies {
        implementation(libs.androidx.annotation)
        implementation(libs.androidx.core)
        implementation(libs.androidx.drawerlayout)
        implementation(libs.androidx.preference)
        implementation(libs.androidx.viewpager)
        implementation(libs.google.material)
        implementation(libs.google.guava)
        implementation(libs.markwon.core)
        implementation(libs.markwon.ext.strikethrough)
        implementation(libs.markwon.linkify)
        implementation(libs.markwon.recycler)
        implementation(libs.google.listenablefuture)

        implementation(project(":terminal-view"))
        implementation(project(":termux-shared"))
    }

    defaultConfig {
        applicationId = "com.estrin217.terminal"
        minSdk = project.properties["minSdkVersion"]?.toString()?.toInt() ?: 21
        targetSdk = project.properties["targetSdkVersion"]?.toString()?.toInt() ?: 28
        versionCode = 118
        val verName = appVersionName.ifEmpty { "0.118.0" }
        versionName = verName
        validateVersionName(verName)

        buildConfigField("String", "TERMUX_PACKAGE_VARIANT", "\"$packageVariant\"")

        manifestPlaceholders["TERMUX_PACKAGE_NAME"] = "com.estrin217.terminal"
        manifestPlaceholders["TERMUX_APP_NAME"] = "Terminal"
        manifestPlaceholders["TERMUX_API_APP_NAME"] = "Terminal:API"
        manifestPlaceholders["TERMUX_BOOT_APP_NAME"] = "Terminal:Boot"
        manifestPlaceholders["TERMUX_FLOAT_APP_NAME"] = "Terminal:Float"
        manifestPlaceholders["TERMUX_STYLING_APP_NAME"] = "Terminal:Styling"
        manifestPlaceholders["TERMUX_TASKER_APP_NAME"] = "Terminal:Tasker"
        manifestPlaceholders["TERMUX_WIDGET_APP_NAME"] = "Terminal:Widget"

        splits {
            abi {
                isEnable = (gradle.startParameter.taskNames.any { it.contains("Debug") } && splitAPKsForDebugBuilds == "1") ||
                    (gradle.startParameter.taskNames.any { it.contains("Release") } && splitAPKsForReleaseBuilds == "1")
                reset()
                include("arm64-v8a")
                isUniversalApk = true
            }
        }

        // Fase 2B: proot bundled solo arm64-v8a (el loader embebido lleva
        // LOADER_ADDRESS y formato objcopy fijos de AArch64).
        ndk {
            abiFilters += "arm64-v8a"
        }

        // Fase 2B: proot usa getifaddrs (Bionic API 24+). Compilar el nativo
        // contra android-28, igual que la verificacion de Fase 2A
        // (aarch64-linux-android28-clang). El minSdk Java no cambia.
        externalNativeBuild {
            cmake {
                arguments += "-DANDROID_PLATFORM=android-28"
            }
        }
    }

    signingConfigs {
        getByName("debug") {
            storeFile = file("testkey_untrusted.jks")
            keyAlias = "alias"
            storePassword = "xrj45yWGLbsO7W0v"
            keyPassword = "xrj45yWGLbsO7W0v"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }

        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    externalNativeBuild {
        cmake {
            version = "3.31.6"
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    lint {
        disable += "ProtectedPermissions"
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    packaging {
        jniLibs {
            useLegacyPackaging = true
        }
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
}

// APK file naming (ported from the pre-Kotlin build.gradle): CI workflows locate
// artifacts as "terminal_<versionTag>_<abi>.apk". TERMUX_APK_VERSION_TAG is set by
// the release/debug workflows; when empty, "<packageVariant>-<buildType>" is used.
androidComponents {
    onVariants { variant ->
        val buildTypeName = variant.buildType
        if (buildTypeName == "debug" || buildTypeName == "release") {
            variant.outputs.forEach { output ->
                val abi = output.filters
                    .find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }
                    ?.identifier ?: "universal"
                val tag = apkVersionTag.ifEmpty { "$packageVariant-$buildTypeName" }
                output.outputFileName.set("terminal_${tag}_${abi}.apk")
            }
        }
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    add("coreLibraryDesugaring", libs.desugar.jdk.libs)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)

    // Kotlin coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Fase 3: extraccion del rootfs Debian (.tar.gz OCI; gzip va en commons-compress)
    implementation(libs.commons.compress)
}

tasks.register("versionName") {
    // Stored as a task input so the doLast action does not capture the script
    // object (which breaks configuration cache serialization).
    inputs.property("appVersionName", android.defaultConfig.versionName)
    doLast {
        println(inputs.properties["appVersionName"])
    }
}

fun validateVersionName(versionName: String) {
    if (!Pattern.matches(
            "^(0|[1-9]\\d*)\\.(0|[1-9]\\d*)\\.(0|[1-9]\\d*)(?:-((?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*)(?:\\.(?:0|[1-9]\\d*|\\d*[a-zA-Z-][0-9a-zA-Z-]*))*))?(?:\\+([0-9a-zA-Z-]+(?:\\.[0-9a-zA-Z-]+)*))?\$",
            versionName
        )
    ) {
        throw GradleException(
            "The versionName '$versionName' is not a valid version as per semantic version '2.0.0' spec in the format 'major.minor.patch(-prerelease)(+buildmetadata)'. https://semver.org/spec/v2.0.0.html."
        )
    }
}

// Fase 2B: el binario proot se genera via CMake POST_BUILD en
// src/main/assets/. Asegurar que CMake corre antes de fusionar assets
// para que el binario entre en el APK ya en la primera compilacion.
tasks.matching { it.name.matches(Regex("merge.*Assets")) }.configureEach {
    dependsOn(tasks.matching { it.name.startsWith("buildCMake") })
}


