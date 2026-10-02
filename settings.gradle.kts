rootProject.name = "spawncheck"

pluginManagement {
    repositories {
        maven(url = "https://maven.fabricmc.net/")
        maven(url = "https://maven.neoforged.net/releases/")
        gradlePluginPortal()
    }
}

plugins {
    // Downloads the JDK the build asks for (Java 25) when it isn't installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

include("common")
include("fabric")
include("neoforge")
