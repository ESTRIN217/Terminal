pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://jitpack.io") }
        // Spec 022: distribuye el motor GeckoView del release de Mozilla
        // (maven-metadata desincronizado; POM/AAR verificado directo)
        maven { url = uri("https://maven.mozilla.org/maven2/") }
    }
}

include(":app", ":termux-shared", ":terminal-emulator", ":terminal-view", ":filemanager")

// Spec 014: termux-x11 (fork ESTRIN217) como librería. Includes planos a
// propósito: cada nodo anidado exigiría configurar/evaluar el build.gradle raíz
// del submódulo, que declara repositories allprojects incompatible con
// FAIL_ON_PROJECT_REPOS de este repo. El alias del stub es nuevo porque
// lorie/build.gradle renombró su compileOnly (línea única, en el submódulo).
include(":termux-x11-lorie")
project(":termux-x11-lorie").projectDir = file("termux-x11/lorie")
include(":termux-x11-stub")
project(":termux-x11-stub").projectDir = file("termux-x11/shell-loader/stub")
