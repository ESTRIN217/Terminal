import java.util.regex.Pattern
import java.util.Properties
import java.io.FileInputStream

plugins {
    id("com.android.application")
    alias(libs.plugins.jetbrains.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
}

val appVersionName = System.getenv("TERMUX_APP_VERSION_NAME") ?: ""
val apkVersionTag = System.getenv("TERMUX_APK_VERSION_TAG") ?: ""
val splitAPKsForDebugBuilds = System.getenv("TERMUX_SPLIT_APKS_FOR_DEBUG_BUILDS") ?: "1"
val splitAPKsForReleaseBuilds = System.getenv("TERMUX_SPLIT_APKS_FOR_RELEASE_BUILDS") ?: "0"
val keystoreProperties = Properties()
val keystorePropertiesFile = rootProject.file("key.properties")
if (keystorePropertiesFile.exists()) {
	keystoreProperties.load(FileInputStream(keystorePropertiesFile))
}

android {
    namespace = "com.termux"

    // Spec 022: GeckoView 159 exige compilar contra android-37.2 (checkAarMetadata).
    // Solo compileSdk; targetSdk sigue en 28 (intencional, ver AGENTS.md).
    compileSdk { version = release(37) { minorApiLevel = 2 } }
    ndkVersion = System.getenv("JITPACK_NDK_VERSION") ?: providers.gradleProperty("ndkVersion").orNull ?: ""

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
        implementation(project(":filemanager"))
        // Spec 014: servidor X11 (verificación + cliente LorieView en pestañas).
        implementation(project(":termux-x11-lorie"))
        // Spec 022: motor GeckoView del navegador interno (solo app; las librerías
        // Java puras NUNCA lo ven. RNF-1 del plan).
        implementation(libs.geckoview)
    }

    defaultConfig {
        applicationId = "com.estrin217.terminal"
        // Spec 022 RNF-0 (enmienda): minSdk 26 — GV release 159 lo exige
        // (antes 24 por proot/getifaddrs, que sigue satisfecho con 26).
        minSdk = providers.gradleProperty("minSdkVersion").orNull?.toInt() ?: 26
        targetSdk = providers.gradleProperty("targetSdkVersion").orNull?.toInt() ?: 28
        versionCode = 122
        val verName = appVersionName.ifEmpty { "3.0.0" }
        versionName = verName
        validateVersionName(verName)

        manifestPlaceholders["TERMUX_PACKAGE_NAME"] = "com.estrin217.terminal"
        manifestPlaceholders["TERMUX_APP_NAME"] = "Terminal"
        manifestPlaceholders["TERMUX_API_APP_NAME"] = "Terminal:API"
        manifestPlaceholders["TERMUX_BOOT_APP_NAME"] = "Terminal:Boot"
        manifestPlaceholders["TERMUX_FLOAT_APP_NAME"] = "Terminal:Float"
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
      create("release") {
      storeFile = keystoreProperties.getProperty("storeFile")?.let{ rootProject.file(it) } 
      storePassword = keystoreProperties.getProperty("storePassword")
      keyAlias = keystoreProperties.getProperty("keyAlias")
      keyPassword = keystoreProperties.getProperty("keyPassword")
      }
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
            signingConfig = signingConfigs.getByName("release")
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
        // Plain JUnit tests exercise code that logs through android.util.Log (Logger); without
        // this the stubs throw instead of returning 0.
        unitTests.isReturnDefaultValues = true
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

// APK file naming: CI workflows locate artifacts as
// "terminal_<versionTag>_<abi>.apk". TERMUX_APK_VERSION_TAG is set by
// the release/debug workflows; when empty, "<buildType>" is used.
androidComponents {
    onVariants { variant ->
        val buildTypeName = variant.buildType
        if (buildTypeName == "debug" || buildTypeName == "release") {
            variant.outputs.forEach { output ->
                val abi = output.filters
                    .find { it.filterType == com.android.build.api.variant.FilterConfiguration.FilterType.ABI }
                    ?.identifier ?: "universal"
                val tag = apkVersionTag.ifEmpty { buildTypeName }
                output.outputFileName.set("terminal_${tag}_${abi}.apk")
            }
        }
    }
}

dependencies {
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    // Real org.json on the unit test classpath: the android.jar stubs only throw, and the
    // extra-keys parser is exercised from plain JUnit tests (Robolectric's native runtime is
    // not available on every host, e.g. Linux aarch64).
    testImplementation(libs.org.json)
    add("coreLibraryDesugaring", libs.desugar.jdk.libs)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    debugImplementation(libs.compose.ui.tooling)

    // Kotlin coroutines
    implementation(libs.kotlinx.coroutines.android)

    // Imágenes (logos, avatar)
    implementation(libs.coil.compose)
    implementation(libs.coil.network.okhttp)

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


