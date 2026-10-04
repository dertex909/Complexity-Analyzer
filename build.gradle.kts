import me.modmuss50.mpp.platforms.discord.LinkType
import org.gradle.jvm.tasks.Jar

plugins {
    `java-library`
    `maven-publish`
    idea
    id("net.neoforged.moddev") version "2.0.144"
    id("me.modmuss50.mod-publish-plugin") version "2.2.0"
}

repositories {
    maven("https://maven.blamejared.com/")
}

val modVersion = prop("mod_version")
val modGroupId = prop("mod_group_id")
val modId = prop("mod_id")
val modName = prop("mod_name")
val modLicense = prop("mod_license")
val modAuthors = prop("mod_authors")
val modDescription = prop("mod_description")
val neoVersion = prop("neo_version")
val minecraftVersion = prop("minecraft_version")
val loaderVersionRange = prop("loader_version_range")
val nettyCodecHttpVersion = prop("netty_codec_http_version")
val zstdJniVersion = prop("zstd_jni_version")

version = modVersion
group = modGroupId
base.archivesName.set(modId)
java.toolchain.languageVersion.set(JavaLanguageVersion.of(21))
java.withSourcesJar()
idea.module.isDownloadSources = true
idea.module.isDownloadJavadoc = true

sourceSets.main {
    resources.srcDir("src/generated/resources")
}

neoForge {
    version = neoVersion
    mods.create(modId).sourceSet(sourceSets.main.get())

    runs {
        create("client") {
            client()
            gameDirectory.set(file("run/client"))
        }

        create("client-second") {
            client()
            gameDirectory.set(file("run/client-second"))
        }

        create("server") {
            server()
            programArgument("--nogui")
            gameDirectory.set(file("run/server"))
        }

        configureEach {
            systemProperty("forge.logging.markers", "REGISTRIES")
            systemProperty("neoforge.enabledGameTestNamespaces", modId)
            logLevel = org.slf4j.event.Level.DEBUG
            additionalRuntimeClasspathConfiguration.extendsFrom(configurations.implementation.get())
        }
    }
}

val http = "io.netty:netty-codec-http:$nettyCodecHttpVersion"
val zstd = "com.github.luben:zstd-jni:$zstdJniVersion"
val jei = "mezz.jei:jei-$minecraftVersion-neoforge:19.25.0.325"

dependencies {
    implementation(http)
    jarJar(http)

    implementation(zstd)
    jarJar(zstd)

    runtimeOnly(jei)
}

tasks.processResources {
    exclude("**/*.py")

    val replaceProperties = mapOf(
        "minecraft_version" to minecraftVersion,
        "minecraft_version_range" to "[$minecraftVersion]",
        "neo_version" to neoVersion,
        "neo_version_range" to "[$neoVersion,)",
        "loader_version_range" to loaderVersionRange,
        "mod_id" to modId,
        "mod_name" to modName,
        "mod_license" to modLicense,
        "mod_version" to modVersion,
        "mod_authors" to modAuthors,
        "mod_description" to modDescription
    )
    inputs.properties(replaceProperties)

    filesMatching("META-INF/neoforge.mods.toml") {
        expand(replaceProperties)
    }
}

publishMods {
    file.set(tasks.named<Jar>("jar").flatMap { it.archiveFile })
    additionalFiles.from(tasks.named<Jar>("sourcesJar").flatMap { it.archiveFile })
    type = ALPHA
    modLoaders.add("neoforge")

    val fullChangelog = providers.fileContents(layout.projectDirectory.file("CHANGELOG.md")).asText

    modrinth {
        accessToken = providers.environmentVariable("MODRINTH_TOKEN")
        projectId = "RQjlpUyI"
        minecraftVersions.add(minecraftVersion)
        changelog = fullChangelog
    }

    curseforge {
        accessToken = providers.environmentVariable("CURSEFORGE_TOKEN")
        projectId = "1377875"
        projectSlug = "complexity-analyzer"
        minecraftVersions.add(minecraftVersion)
        server = true
        client = false
        changelog = fullChangelog
    }

    discord {
        webhookUrl = providers.environmentVariable("DISCORD_WEBHOOK")
        username = "Complexity Analyzer"
        content = "🚀 **Complexity Analyzer v$modVersion** is out!\n"
        style {
            link = LinkType.INLINE.name
        }
    }
}

fun prop(name: String): String = providers.gradleProperty(name).get()