pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories { google(); mavenCentral() }
}
rootProject.name = "pboard"
include(":app", ":engine", ":translate", ":translatepack", ":scanpack", ":voicepack", ":voicepackaccurate", ":bench", ":baselineprofile", ":testapp")
