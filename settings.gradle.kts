pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
    plugins {
        // Versiones recomendadas: ajusta si necesitas otras
        id("com.android.application") version "8.1.2" apply false
        id("org.jetbrains.kotlin.android") version "1.9.22" apply false
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        maven { url = uri("https://repo1.maven.org/maven2") }
    }
}

rootProject.name = "DIME_App"
include(":app")
