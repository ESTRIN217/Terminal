plugins {
    id("com.android.library")
}

android {
    namespace = "com.termux.view"

    compileSdk { version = release(37) { minorApiLevel = 1 } }

    defaultConfig {
        minSdk = providers.gradleProperty("minSdkVersion").orNull?.toInt() ?: 21
        testInstrumentationRunner = "android.support.test.runner.AndroidJUnitRunner"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
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
    implementation(libs.androidx.annotation)
    api(project(":terminal-emulator"))
    testImplementation(libs.junit)
}

tasks.register<Jar>("sourceJar") {
    from(project.android.sourceSets.getByName("main").java)
    archiveClassifier.set("sources")
}
