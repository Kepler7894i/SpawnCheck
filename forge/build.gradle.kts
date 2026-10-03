plugins {
    id("net.minecraftforge.gradle")
}

val minecraftVersion = project.property("minecraftVersion") as String
val forgeVersion = project.property("forgeVersion") as String

sourceSets.main {
    java.srcDir("../common/src/main/java")
    resources.srcDir("../common/src/main/resources")
}

minecraft {
    runs {
        configureEach {
            workingDir.set(layout.projectDirectory.dir("run"))
        }
        register("client")
        register("server") {
            args("--nogui")
        }
    }
}

repositories {
    minecraft.mavenizer(this)
    maven(fg.forgeMaven)
    maven(fg.minecraftLibsMaven)
    mavenCentral()
}

dependencies {
    implementation(minecraft.dependency("net.minecraftforge:forge:$minecraftVersion-$forgeVersion"))
}

tasks.processResources {
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    // Plain values captured here (not script properties) so the configuration cache can serialize the task.
    val expansions = mapOf(
        "version" to project.version.toString(),
        "minecraft_version" to minecraftVersion,
    )
    inputs.properties(expansions)
    filesMatching("META-INF/mods.toml") { expand(expansions) }
}
