// build.gradle.kts (root) - NO aplicar com.android.application aquí
// Declara las versiones de plugin en settings.gradle.kts y evita aplicar el plugin en el root.
// Esto previene que Gradle trate el proyecto raíz como un módulo Android.
plugins {
    // Declarar las versiones aquí con apply false es opcional si ya las declaraste en settings.gradle.kts.
    // Mantener apply false asegura que no se apliquen en el proyecto raíz.
    id("com.android.application") version "8.1.2" apply false
    id("org.jetbrains.kotlin.android") version "1.9.10" apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

// Opcional: tareas o configuraciones globales del build pueden colocarse aquí.
// Pero NUNCA apliques com.android.application en el root si quieres que solo :app sea el módulo Android.
