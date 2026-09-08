// Printers builds on its own so it can be opened, tested and released without a host
// application. Applications consume it by pointing a project directory at printers/ — see README.md.
pluginManagement {
    repositories { google(); mavenCentral(); gradlePluginPortal() }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.PREFER_SETTINGS)
    repositories { google(); mavenCentral() }
}

rootProject.name = "Printers"
include(":printers")
