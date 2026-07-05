import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import net.neoforged.gradle.dsl.common.runs.run.Run

plugins {
    alias(libs.plugins.neogradle.userdev)
    `java-library`
    id("buildlogic.platform")
}

platform {
    kind = buildlogic.WorldEditKind.Mod
}

val minecraftVersion = libs.versions.neoforge.minecraft.get()
// Lowest supported NeoForge build (the declared dependency floor). Decoupled from the
// compiled-against `neoforge` library version so the mod can be built against a newer
// build while still loading on the whole 21.1.x line.
val minNeoforgeVersion = libs.versions.neoforge.min.get()
// Upper bound (exclusive) for the supported NeoForge version range: the next minor line.
// NeoForge is versioned as <mcMinor>.<mcPatch>.<build>, so e.g. 21.1.235 -> 21.2, restricting
// the mod to the 21.1.x line that matches the Minecraft version it was compiled against.
val nextNeoforgeMinorVersion: String = libs.neoforge.get().version!!.split('.').let { parts ->
    "${parts[0]}.${parts[1].toInt() + 1}"
}

val apiClasspath = configurations.create("apiClasspath") {
    isCanBeResolved = true
    extendsFrom(configurations.api.get())
}

repositories {
    maven {
        name = "EngineHub"
        url = uri("https://maven.enginehub.org/repo/")
    }
    mavenCentral()
    killNonEngineHubRepositories()
    afterEvaluate {
        killNonEngineHubRepositories()
    }
}

dependencies {
    "api"(project(":worldedit-core"))

    "implementation"(libs.neoforge)
}

minecraft {
    accessTransformers {
        file("src/main/resources/META-INF/accesstransformer.cfg")
    }
}

runs {
    val runConfig = Action<Run> {
        systemProperties(mapOf(
            "forge.logging.markers" to "SCAN,REGISTRIES,REGISTRYDUMP",
            "forge.logging.console.level" to "debug"
        ))
        workingDirectory(project.file("run").canonicalPath)
        modSources(sourceSets["main"])
        dependencies {
            runtime(apiClasspath)
        }
    }
    named("client").configure(runConfig)
    named("server").configure(runConfig)
}

subsystems {
    parchment {
        minecraftVersion = libs.versions.parchment.minecraft.get()
        mappingsVersion = libs.versions.parchment.mappings.get()
        addRepository = false
    }
    decompiler {
        maxMemory("3G")
    }
}

configure<BasePluginExtension> {
    archivesName.set("${archivesName.get()}-mc$minecraftVersion")
}

configure<PublishingExtension> {
    publications.named<MavenPublication>("maven") {
        artifactId = the<BasePluginExtension>().archivesName.get()
        from(components["java"])
    }
}

tasks.named<Copy>("processResources") {
    // this will ensure that this task is redone when the versions change.
    val properties = mapOf(
        "version" to project.ext["internalVersion"],
        "neoVersion" to libs.neoforge.get().version,
        "minecraftVersion" to minecraftVersion,
        "minNeoforgeVersion" to minNeoforgeVersion,
        "nextNeoforgeMinorVersion" to nextNeoforgeMinorVersion
    )
    properties.forEach { (key, value) ->
        inputs.property(key, value)
    }

    filesMatching("META-INF/neoforge.mods.toml") {
        expand(properties)
    }

    // copy from -core resources as well
    from(project(":worldedit-core").tasks.named("processResources"))
}

tasks.named<ShadowJar>("shadowJar") {
    dependencies {
        relocate("org.antlr.v4", "com.sk89q.worldedit.antlr4")
        relocate("net.royawesome.jlibnoise", "com.sk89q.worldedit.jlibnoise")

        include(dependency("org.antlr:antlr4-runtime"))
        include(dependency("org.mozilla:rhino-runtime"))
        include(dependency("com.sk89q.lib:jlibnoise"))
    }
    minimize {
        exclude(dependency("org.mozilla:rhino-runtime"))
    }
}
