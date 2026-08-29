plugins {
    idea
    jacoco
    `maven-publish`
    id("net.minecraftforge.gradle") version "[6.0.24,6.2)"
}

group = property("mod_group_id") as String
version = property("mod_version") as String
base { archivesName.set(property("artifact_name") as String) }

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(17))
    withSourcesJar()
}

minecraft {
    mappings("official", property("minecraft_version") as String)
    copyIdeResources = true
    runs {
        configureEach {
            workingDirectory(project.file("run"))
            property("forge.logging.console.level", "info")
            mods {
                create(property("mod_id") as String) { source(sourceSets.main.get()) }
            }
        }
        create("client")
        create("server") { arg("--nogui") }
        create("gameTestServer") {
            workingDirectory(project.file("run-gametest"))
            property("forge.enableGameTest", "true")
            property("forge.gameTestServer", "true")
            property("forge.enabledGameTestNamespaces", property("mod_id") as String)
            arg("--nogui")
        }
    }
}

repositories {
    maven("https://maven.minecraftforge.net")
    mavenCentral()
}

dependencies {
    minecraft("net.minecraftforge:forge:${property("minecraft_version")}-${property("forge_version")}")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
}

tasks.processResources {
    val props = mapOf(
        "minecraft_version" to project.property("minecraft_version"),
        "forge_version" to project.property("forge_version"),
        "mod_id" to project.property("mod_id"),
        "mod_name" to project.property("mod_name"),
        "mod_version" to project.property("mod_version")
    )
    inputs.properties(props)
    filesMatching(listOf("META-INF/mods.toml", "pack.mcmeta")) { expand(props) }
}

tasks.named<Jar>("jar") { finalizedBy("reobfJar") }

val stageRuntimeJar by tasks.registering(Copy::class) {
    group = "build"
    dependsOn(tasks.named("reobfJar"))
    from(layout.buildDirectory.file("reobfJar/output.jar"))
    into(layout.buildDirectory.dir("libs"))
    rename { "${base.archivesName.get()}-$version.jar" }
}

tasks.named("assemble") { dependsOn(stageRuntimeJar) }
tasks.withType<JavaCompile>().configureEach { options.release.set(17) }
tasks.test { useJUnitPlatform() }

val resetGameTestMods = tasks.register<Delete>("resetGameTestMods") {
    delete(layout.projectDirectory.dir("run-gametest/mods"))
}
val syncGameTestStructures = tasks.register<Sync>("syncGameTestStructures") {
    from(layout.projectDirectory.dir("src/main/resources/gameteststructures"))
    into(layout.projectDirectory.dir("run-gametest/gameteststructures"))
}
tasks.matching { it.name.startsWith("prepareRunGameTestServer") }.configureEach {
    dependsOn(resetGameTestMods, syncGameTestStructures)
}

tasks.register("headlessGameTest") {
    group = "verification"
    dependsOn(tasks.named("runGameTestServer"))
}
tasks.register("verifyFast") {
    group = "verification"
    dependsOn(tasks.named("check"))
}
tasks.register("verifyFull") {
    group = "verification"
    dependsOn(tasks.named("verifyFast"), tasks.named("headlessGameTest"))
}
