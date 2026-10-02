plugins {
    id("net.neoforged.moddev")
}

val minecraftVersion = project.property("minecraftVersion") as String
val neoforgeVersion = project.property("neoforgeVersion") as String

sourceSets.main {
    java.srcDir("../common/src/main/java")
    resources.srcDir("../common/src/main/resources")
}

neoForge {
    version = neoforgeVersion

    runs {
        create("client") { client() }
        create("server") { server() }
    }

    mods {
        create("spawncheck") { sourceSet(sourceSets.main.get()) }
    }
}

tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // Plain values captured here (not script properties) so the configuration cache can serialize the task.
    val expansions = mapOf(
        "version" to project.version.toString(),
        "minecraft_version" to minecraftVersion,
    )
    inputs.properties(expansions)
    filesMatching("META-INF/neoforge.mods.toml") { expand(expansions) }
}
