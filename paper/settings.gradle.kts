pluginManagement {
    repositories {
        gradlePluginPortal()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

// Keep Paper's Gradle version independent; the root build invokes this project's wrapper.
rootProject.name = "tacz-paper"

