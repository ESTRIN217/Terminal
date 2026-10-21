plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.shared"

    compileSdk { version = release(37) { minorApiLevel = 1 } }
    ndkVersion = System.getenv("JITPACK_NDK_VERSION") ?: providers.gradleProperty("ndkVersion").orNull ?: ""

    defaultConfig {
        minSdk = providers.gradleProperty("minSdkVersion").orNull?.toInt() ?: 21
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

    externalNativeBuild {
        cmake {
            version = "3.31.6"
            path = file("src/main/cpp/CMakeLists.txt")
        }
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
    implementation(libs.androidx.appcompat)
    implementation(libs.androidx.annotation)
    implementation(libs.androidx.core)
    implementation(libs.google.material)
    implementation(libs.google.guava)
    implementation(libs.markwon.core)
    implementation(libs.markwon.ext.strikethrough)
    implementation(libs.markwon.linkify)
    implementation(libs.markwon.recycler)
    implementation(libs.hiddenapibypass)
    implementation(libs.androidx.window)
    implementation(libs.commons.io)
    implementation(libs.termux.am.library)
    implementation(project(":terminal-view"))

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit.ext)
    add("coreLibraryDesugaring", libs.desugar.jdk.libs)
}

tasks.register<Jar>("sourceJar") {
    from(project.android.sourceSets.getByName("main").java)
    archiveClassifier.set("sources")
}
