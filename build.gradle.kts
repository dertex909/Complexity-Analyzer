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

val mod_version = project.property("mod_version") as String
val mod_group_id = project.property("mod_group_id") as String
val mod_id = project.property("mod_id") as String
val mod_name = project.property("mod_name") as String
val mod_license = project.property("mod_license") as String
val mod_authors = project.property("mod_authors") as String
val mod_description = project.property("mod_description") as String
val neo_version = project.property("neo_version") as String
val minecraft_version = project.property("minecraft_version") as String
val loader_version_range = project.property("loader_version_range") as String
val netty_codec_http_version = project.property("netty_codec_http_version") as String
val zstd_jni_version = project.property("zstd_jni_version") as String

version = mod_version
group = mod_group_id
base.archivesName.set(mod_id)
java.toolchain.languageVersion.set(JavaLanguageVersion.of(21))
java.withSourcesJar()
idea.module.isDownloadSources = true
idea.module.isDownloadJavadoc = true

sourceSets.main {
    resources.srcDir("src/generated/resources")
}

neoForge {
    version = neo_version
    mods.create(mod_id).sourceSet(sourceSets.main.get())

    runs {
        create("client").client()

        create("server") {
            server()
            programArgument("--nogui")
        }

        create("gameTestServer").type = "gameTestServer"

        create("data") {
            data()
            programArguments.addAll(
                "--mod", mod_id, "--all",
                "--output", file("src/generated/resources/").path,
                "--existing", file("src/main/resources/").path
            )
        }

        configureEach {
            systemProperty("forge.logging.markers", "REGISTRIES")
            systemProperty("neoforge.enabledGameTestNamespaces", mod_id)
            logLevel = org.slf4j.event.Level.DEBUG
            additionalRuntimeClasspathConfiguration.extendsFrom(configurations.implementation.get())
        }
    }
}

val http = "io.netty:netty-codec-http:$netty_codec_http_version"
val zstd = "com.github.luben:zstd-jni:$zstd_jni_version"
val jei = "mezz.jei:jei-$minecraft_version-neoforge:19.25.0.325"

dependencies {
    implementation(http)
    add("jarJar", http)

    implementation(zstd)
    add("jarJar", zstd)

    runtimeOnly(jei)
}

tasks.processResources {
    exclude("**/*.py")

    val replaceProperties = mapOf(
        "minecraft_version" to minecraft_version,
        "minecraft_version_range" to "[$minecraft_version]",
        "neo_version" to neo_version,
        "neo_version_range" to "[$neo_version,)",
        "loader_version_range" to loader_version_range,
        "mod_id" to mod_id,
        "mod_name" to mod_name,
        "mod_license" to mod_license,
        "mod_version" to mod_version,
        "mod_authors" to mod_authors,
        "mod_description" to mod_description
    )
    inputs.properties(replaceProperties)

    filesMatching("META-INF/neoforge.mods.toml") {
        expand(replaceProperties)
    }
}

publishMods {
    file = tasks.named<Jar>("jar").flatMap { it.archiveFile }
    additionalFiles.from(tasks.named<Jar>("sourcesJar").flatMap { it.archiveFile })
    type = ALPHA
    modLoaders.add("neoforge")
    val fullChangelog = providers.fileContents(layout.projectDirectory.file("CHANGELOG.md")).asText

    modrinth {
        accessToken = providers.environmentVariable("MODRINTH_TOKEN")
        projectId = "RQjlpUyI"
        minecraftVersions.add(minecraft_version)
        changelog = fullChangelog
    }

    curseforge {
        accessToken = providers.environmentVariable("CURSEFORGE_TOKEN")
        projectId = "1377875"
        projectSlug = "complexity-analyzer"
        minecraftVersions.add(minecraft_version)
        server = true
        client = false
        changelog = fullChangelog
    }

    discord {
        webhookUrl = providers.environmentVariable("DISCORD_WEBHOOK")
        username = "Complexity Analyzer"
        content = "🚀 **Complexity Analyzer v$mod_version** is out!\n"
        style {
            link = "INLINE"
        }
    }
}