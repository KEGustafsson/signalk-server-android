pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
        // usb-serial-for-android is published only through JitPack, which builds it from its
        // GitHub tag. Only that one group may come from there, so no other dependency can be
        // served from a repository that builds on demand; the checksums are pinned like the rest.
        exclusiveContent {
            forRepository { maven("https://jitpack.io") { name = "JitPack" } }
            filter { includeGroup("com.github.mik3y") }
        }
    }
}
rootProject.name = "SignalKServerAndroid"
include(":app", ":node-runtime")
