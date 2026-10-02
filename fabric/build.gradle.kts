plugins {
    id("net.fabricmc.fabric-loom")
}

val minecraftVersion = project.property("minecraftVersion") as String
val fabricLoaderVersion = project.property("fabricLoaderVersion") as String
val fabricApiVersion = project.property("fabricApiVersion") as String

sourceSets.main {
    java.srcDir("../common/src/main/java")
    resources.srcDir("../common/src/main/resources")
}

dependencies {
    minecraft("com.mojang:minecraft:$minecraftVersion")

    implementation("net.fabricmc:fabric-loader:$fabricLoaderVersion")
    implementation("net.fabricmc.fabric-api:fabric-api:$fabricApiVersion+$minecraftVersion")
}

tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // Plain values captured here (not script properties) so the configuration cache can serialize the task.
    val expansions = mapOf(
        "version" to project.version.toString(),
        "minecraft_version" to minecraftVersion,
        "fabric_loader_version" to fabricLoaderVersion,
    )
    inputs.properties(expansions)
    filesMatching("fabric.mod.json") { expand(expansions) }
}
