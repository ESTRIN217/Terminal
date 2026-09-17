plugins {
    id("com.android.library")
    alias(libs.plugins.jetbrains.kotlin.compose)
}

android {
    namespace = "com.estrin217.filemanager"

    compileSdk { version = release(37) { minorApiLevel = 1 } }
    ndkVersion = System.getenv("JITPACK_NDK_VERSION") ?: project.properties["ndkVersion"]?.toString() ?: ""

    defaultConfig {
        minSdk = project.properties["minSdkVersion"]?.toString()?.toInt() ?: 24
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        isCoreLibraryDesugaringEnabled = true
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    publishing {
        multipleVariants {
            withSourcesJar()
            withJavadocJar()
            allVariants()
        }
    }
}

dependencies {
    implementation(project(":termux-shared"))
    implementation(libs.androidx.core)

    // Compose
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.foundation)
    implementation(libs.compose.runtime)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.kotlinx.coroutines.android)

    testImplementation(libs.junit)
    add("coreLibraryDesugaring", libs.desugar.jdk.libs)
}