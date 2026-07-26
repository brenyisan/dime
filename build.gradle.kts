// build.gradle.kts (raíz) - MÍNIMO, no declarar repositorios aquí.
// Las versiones de los plugins se controlan en settings.gradle.kts.

plugins {
    // Declaradas con apply false para evitar que el root se trate como módulo Android.
    id("com.android.application") version "8.1.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.22" apply false
}

// No declarar "repositories" ni "allprojects/subprojects" que añadan repositorios.
// El manejo de repositorios lo hace settings.gradle.kts.
