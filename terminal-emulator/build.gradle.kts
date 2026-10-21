plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.emulator"

    compileSdk { version = release(37) { minorApiLevel = 1 } }
    ndkVersion = System.getenv("JITPACK_NDK_VERSION") ?: providers.gradleProperty("ndkVersion").orNull ?: ""

    defaultConfig {
        minSdk = providers.gradleProperty("minSdkVersion").orNull?.toInt() ?: 21
        ndk {
            abiFilters.addAll(listOf("arm64-v8a"))
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    externalNativeBuild {
        cmake {
            version = "3.31.6"
            path = file("src/main/cpp/CMakeLists.txt")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isReturnDefaultValues = true
    }

    publishing {
        multipleVariants {
            withSourcesJar()
            withJavadocJar()
            allVariants()
        }
    }
}

tasks.withType<Test> {
    testLogging {
        events("started", "passed", "skipped", "failed")
    }
}

dependencies {
    implementation(libs.androidx.annotation)
    testImplementation(libs.junit)
}

tasks.register<Jar>("sourceJar") {
    from(project.android.sourceSets.getByName("main").java)
    archiveClassifier.set("sources")
}
