plugins {
    id("com.android.application") version "9.5.0" apply false
    id("com.android.library") version "9.5.0" apply false
    // Kotlin is AGP's built-in one from 9.0 on: the Kotlin Android plugin is not applied anywhere,
    // this line only puts a newer Kotlin Gradle plugin on the build classpath than AGP's default.
    id("org.jetbrains.kotlin.android") version "2.4.21" apply false
    // The Compose compiler is a Kotlin compiler plugin and moves with Kotlin, same version.
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.21" apply false
}
