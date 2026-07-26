// build.gradle.kts (raíz) - MÍNIMO, no declarar repositorios aquí.
// Las versiones de los plugins deben declararse en settings.gradle.kts (apply false).
// No añadir "repositories" en este archivo cuando settings.gradle.kts controla repositorios.

plugins {
    // Declaradas con apply false para evitar que el root se trate como módulo Android.
    id("com.android.application") version "8.1.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.10" apply false
}

// No declarar "repositories" ni "allprojects/subprojects" que añadan repositorios.
// El manejo de repositorios debe hacerse desde settings.gradle.kts (dependencyResolutionManagement).
