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
        // LiveKit's audio switch (com.github.davidliu:audioswitch) is published only here; nothing else may come from it.
        maven("https://jitpack.io") { content { includeGroup("com.github.davidliu") } }
    }
}

rootProject.name = "MenkerudHome"
include(":app")
