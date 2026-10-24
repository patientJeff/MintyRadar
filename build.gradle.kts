plugins {
    // Applies the right Loom for this project's Minecraft version (see settings.gradle.kts).
    id("dev.kikugie.loom-back-compat")
    id("me.modmuss50.mod-publish-plugin")
}

val mcVersion = sc.current.version
val modVersion = property("mod.version") as String
val mcCompat = property("mod.mc_compat") as String
val loaderVersion = property("deps.fabric_loader") as String
// e.g. MintyRadar-1.3.0+26.3.jar
version = "$modVersion+$mcVersion"
base.archivesName = property("mod.name") as String

// Minecraft 26.1+ runs on Java 25, 1.21.x on Java 21.
val requiredJava: JavaVersion = if (sc.current.parsed >= "26.1") JavaVersion.VERSION_25 else JavaVersion.VERSION_21

repositories {
    maven("https://maven.terraformersmc.com/releases/") { name = "TerraformersMC" }
}

dependencies {
    minecraft("com.mojang:minecraft:$mcVersion")
    // Mojang's official names on the obfuscated 1.21.11, so the same sources work everywhere.
    loomx.applyMojangMappings()

    // `mod...` configurations work on 26.1+ too: loom-back-compat converts them.
    modImplementation("net.fabricmc:fabric-loader:${property("deps.fabric_loader")}")
    modImplementation("net.fabricmc.fabric-api:fabric-api:${property("deps.fabric_api")}")

    // Mod Menu is optional: compiled against for the config-screen hook, and loaded in
    // the dev client, but not required by (or bundled into) the released jar.
    modCompileOnly("com.terraformersmc:modmenu:${property("deps.modmenu")}")
    modLocalRuntime("com.terraformersmc:modmenu:${property("deps.modmenu")}")
}

loom {
    runConfigs.all {
        // 26.3 uses run/ as before; other versions get their own folder, so worlds
        // are never opened by an older game than the one that saved them.
        runDirectory = rootProject.file(if (mcVersion == "26.3") "run" else "run/versions/$mcVersion")
    }
}

// gradlew :26.3:runClientGameTest (or any version): a test client that turns on every radar
// feature in a test world and takes screenshots (src/gametest). Not part of the released jar.
fabricApi {
    configureTests {
        createSourceSet = true
        modId = "minty_radar_gametest"
        enableGameTests = false
        enableClientGameTests = true
        eula = true
    }
}

val gameTestRun = file("build/run/clientGameTest")
tasks.named<JavaExec>("runClientGameTest") {
    // A small heap, so the test client still starts while your own game is open.
    jvmArgs("-Xmx2G")
    doFirst {
        // Tests run silently: master volume 0 in the test client's options.
        val options = File(gameTestRun, "options.txt")
        val lines = (if (options.exists()) options.readLines().filter { !it.startsWith("soundCategory_master:") } else listOf())
            .plus("soundCategory_master:0.0")
        options.parentFile.mkdirs()
        options.writeText(lines.joinToString("\n") + "\n")

        // Start from a 1.2.0 settings file, to check it's moved to MintyRadar.json and
        // its range (64 blocks, index 2 of the old steps) carries over.
        val config = File(gameTestRun, "config")
        File(config, "MintyRadar.json").delete()
        config.mkdirs()
        File(config, "minty_radar.json").writeText("{\"enabled\": true, \"rangeIndex\": 2, \"friends\": [\"Notch\"]}\n")
    }
}

// gradlew publishMods: uploads every version's jar to Modrinth, marked with the game versions
// from mod.mc_releases. Needs modrinth.id in stonecutter.properties.toml and a Modrinth token
// in the MODRINTH_TOKEN environment variable; without them it's a dry run that uploads nothing.
val modrinthId = property("modrinth.id") as String
val modrinthToken = providers.environmentVariable("MODRINTH_TOKEN")
val mcReleases = sc.properties.rawOrNull("mod", "mc_releases")?.asList().orEmpty().map { it.toString() }

publishMods {
    file = loomx.modJar.flatMap { it.archiveFile }
    displayName = "MintyRadar $modVersion for $mcVersion"
    version = project.version.toString()
    changelog = changelogFor(modVersion)
    type = STABLE
    modLoaders.add("fabric")
    dryRun = modrinthId.isBlank() || !modrinthToken.isPresent

    modrinth {
        projectId = modrinthId
        accessToken = modrinthToken
        minecraftVersions.addAll(mcReleases)
        requires("fabric-api")
        optional("modmenu")
    }
}

/** This version's section of CHANGELOG.md, from its "## " heading up to the next "---". */
fun changelogFor(version: String): String {
    val text = rootProject.file("CHANGELOG.md").readText().replace("\r\n", "\n")
    val start = text.indexOf("## $version")
    if (start < 0) return "See CHANGELOG.md"
    val body = text.substring(text.indexOf('\n', start) + 1)
    val end = body.indexOf("\n---")
    return (if (end < 0) body else body.substring(0, end)).trim()
}

java {
    withSourcesJar()
    targetCompatibility = requiredJava
    sourceCompatibility = requiredJava
    toolchain {
        languageVersion = JavaLanguageVersion.of(requiredJava.majorVersion)
    }
}

tasks {
    processResources {
        val props = mapOf(
            "version" to project.version.toString(),
            "minecraft" to mcCompat,
            "loader" to loaderVersion,
            "java" to requiredJava.majorVersion,
        )
        inputs.properties(props)
        filesMatching("fabric.mod.json") { expand(props) }
        filesMatching("*.mixins.json") { expand("java" to "JAVA_${requiredJava.majorVersion}") }
    }

    withType<Jar> {
        from(rootProject.file("LICENSE")) {
            rename { "${it}_MintyRadar" }
        }
    }

    // gradlew buildAndCollect: builds every version and copies the jars to build/libs/<mod version>/.
    register<Copy>("buildAndCollect") {
        group = "build"
        description = "Builds the mod jars and copies them to build/libs/<mod version>/"
        from(loomx.modJar.flatMap { it.archiveFile })
        into(rootProject.layout.buildDirectory.dir("libs/$modVersion"))
    }
}
