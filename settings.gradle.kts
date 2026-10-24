pluginManagement {
    repositories {
        mavenCentral()
        gradlePluginPortal()
        maven("https://maven.fabricmc.net/") { name = "Fabric" }
        maven("https://maven.kikugie.dev/releases") { name = "KikuGie Releases" }
    }
}

plugins {
    // One source tree, one jar per Minecraft version (https://stonecutter.kikugie.dev)
    id("dev.kikugie.stonecutter") version "0.9.8"
    // Applies the right Loom for each version: remapping Loom with Mojang mappings for the
    // obfuscated 1.21.11, plain Loom for 26.1+ (https://codeberg.org/KikuGie/loom-back-compat)
    id("dev.kikugie.loom-back-compat") version "0.4.2"
}

stonecutter {
    create(rootProject) {
        // Versions and their dependencies are in stonecutter.properties.toml.
        versions("1.21.11", "26.1", "26.2", "26.3")
        vcsVersion = "26.3"
    }
}

rootProject.name = "MintyRadar"
