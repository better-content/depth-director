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

val worldGameTest = sourceSets.create("worldGameTest") {
    compileClasspath += sourceSets.main.get().output + configurations.compileClasspath.get()
    runtimeClasspath += output + compileClasspath
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
        val gameTestServer = create("gameTestServer") {
            workingDirectory(project.file("run-gametest"))
            property("forge.enableGameTest", "true")
            property("forge.gameTestServer", "true")
            property("forge.enabledGameTestNamespaces", property("mod_id") as String)
            arg("--nogui")
        }
        create("worldGameTestServer") {
            parent(gameTestServer)
            workingDirectory(project.file("run-world-gametest"))
            property("forge.enableGameTest", "true")
            property("forge.gameTestServer", "true")
            property("forge.enabledGameTestNamespaces", "depth_director_world_tests")
            property("mixin.env.remapRefMap", "true")
            property("mixin.env.refMapRemappingFile", file("build/createSrgToMcp/output.srg").absolutePath)
            arg("--nogui")
            mods {
                create("depth_director_world_tests") { source(worldGameTest) }
            }
        }
    }
}

repositories {
    maven("https://maven.minecraftforge.net")
    maven("https://www.cursemaven.com") { content { includeGroup("curse.maven") } }
    maven("https://maven.teamresourceful.com/repository/maven-public") {
        content {
            includeGroup("com.teamresourceful")
            includeGroup("com.teamresourceful.resourcefullib")
            includeGroup("com.teamresourceful.resourcefulconfig")
        }
    }
    ivy("https://cdn.modrinth.com/data/Lq6ojcWv/versions") {
        name = "endermanOverhaulWorldTest"
        patternLayout { artifact("[revision]/[artifact].[ext]") }
        metadataSources { artifact() }
        content { includeGroup("worldtest.enderman") }
    }
    mavenCentral()
}

dependencies {
    minecraft("net.minecraftforge:forge:${property("minecraft_version")}-${property("forge_version")}")
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")

    add("worldGameTestRuntimeOnly", fg.deobf("curse.maven:born-in-chaos-686437:7917933"))
    add("worldGameTestRuntimeOnly", fg.deobf("curse.maven:goety-586095:8087429"))
    add("worldGameTestRuntimeOnly", fg.deobf("curse.maven:deeper-and-darker-659011:5906086"))
    add("worldGameTestRuntimeOnly", fg.deobf("curse.maven:quark-243121:6427817"))
    add("worldGameTestRuntimeOnly", fg.deobf("curse.maven:zeta-968868:7335229"))
    add("worldGameTestRuntimeOnly", fg.deobf("curse.maven:tinkers-construct-74072:7449219"))
    add("worldGameTestRuntimeOnly", fg.deobf("curse.maven:mantle-74924:7563777"))
    add("worldGameTestRuntimeOnly", fg.deobf("curse.maven:geckolib-388172:7553267"))
    add("worldGameTestRuntimeOnly", fg.deobf("curse.maven:curios-api-309927:6418456"))
    add("worldGameTestRuntimeOnly", fg.deobf(
            "com.teamresourceful.resourcefullib:resourcefullib-forge-1.20.1:2.1.29"))
    add("worldGameTestRuntimeOnly", fg.deobf(
            "com.teamresourceful.resourcefulconfig:resourcefulconfig-forge-1.20.1:2.1.3"))
    add("worldGameTestRuntimeOnly", fg.deobf(
            "worldtest.enderman:endermanoverhaul-forge-1.20.1-1.0.4:yjxych8u@jar"))
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

val resetWorldGameTestRun = tasks.register<Delete>("resetWorldGameTestRun") {
    delete(layout.projectDirectory.dir("run-world-gametest/world"))
    delete(layout.projectDirectory.dir("run-world-gametest/logs"))
    delete(layout.projectDirectory.dir("run-world-gametest/config"))
    delete(layout.projectDirectory.dir("run-world-gametest/mods"))
}
val syncWorldGameTestStructures = tasks.register<Sync>("syncWorldGameTestStructures") {
    from(layout.projectDirectory.dir("src/main/resources/gameteststructures"))
    into(layout.projectDirectory.dir("run-world-gametest/gameteststructures"))
}
tasks.matching { it.name.startsWith("prepareRunWorldGameTestServer") }.configureEach {
    dependsOn(resetWorldGameTestRun, syncWorldGameTestStructures)
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
tasks.register("verifyWorld") {
    group = "verification"
    description = "Runs the explicit full-cadence real-catalogue Director qualification suite."
    dependsOn(tasks.named("verifyFull"), tasks.named("runWorldGameTestServer"))
}
tasks.matching { it.name == "runWorldGameTestServer" }.configureEach {
    mustRunAfter(tasks.named("verifyFull"))
}
