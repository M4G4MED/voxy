import me.cortex.voxy.gradle.prop
import dev.kikugie.stonecutter.build.StonecutterBuildExtension
import org.gradle.api.plugins.BasePluginExtension
import org.gradle.api.plugins.JavaPluginExtension
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.bundling.AbstractArchiveTask
import org.gradle.api.tasks.bundling.Jar
import org.gradle.api.tasks.compile.JavaCompile

val modVersion = prop("mod.version", "mod_version")
val minecraftVersion = prop("deps.minecraft", "minecraft_version")
val modId = prop("mod.id", "archives_base_name")
val modName = prop("mod.name", "mod_name")

extra["modVersion"] = modVersion
extra["minecraftVersion"] = minecraftVersion
extra["modId"] = modId
extra["modName"] = modName
extra["jedisVersion"] = prop("jedisVersion")
extra["rocksdbVersion"] = prop("rocksdbVersion")
extra["commonsPoolVersion"] = prop("commonsPoolVersion")
extra["lz4Version"] = prop("lz4Version")
extra["xzVersion"] = prop("xzVersion")
extra["sqliteJdbcVersion"] = prop("sqliteJdbcVersion")
extra["additionalVersions"] = providers.gradleProperty("publish.additionalVersions").orNull
    ?.split(",")
    ?.map { it.trim() }
    ?.filter { it.isNotEmpty() }
    ?: emptyList<String>()
extra["publishModrinth"] = providers.gradleProperty("publish.modrinth").orNull
extra["publishCurseforge"] = providers.gradleProperty("publish.curseforge").orNull

val loaderName = (extra.properties["loaderName"] as? String)?.takeIf { it.isNotBlank() } ?: "shared"
val loaderDisplayName = (extra.properties["loaderDisplayName"] as? String)?.takeIf { it.isNotBlank() } ?: loaderName
val archiveTaskName = (extra.properties["archiveTaskName"] as? String)?.takeIf { it.isNotBlank() } ?: "jar"
val sourceJavaDir = extra.properties["sourceJavaDir"] as? String
val additionalSourceJavaDirs = (extra.properties["additionalSourceJavaDirs"] as? List<*>)
    ?.filterIsInstance<String>()
    ?: emptyList()
val publishModrinth = extra.properties["publishModrinth"] as? String
val publishCurseforge = extra.properties["publishCurseforge"] as? String
val additionalVersions = emptyList<String>()
val stonecutterLoaderName = if (loaderName == "legacyforge") "forge" else loaderName

apply(plugin = "dev.kikugie.stonecutter")

repositories {
    flatDir {
        dirs("libs")
    }

    maven {
        url = uri("https://artifex.soh.gg/maven/m3t4f1v3/bp-voxy-packages/")
    }

    // Sable + sable-companion compile-only artifacts (1.21.1 compat)
    maven {
        name = "RyanHCode"
        url = uri("https://maven.ryanhcode.dev/releases")
    }

    if (prop("useRubyOnly").toBoolean()) {
        exclusiveContent {
            forRepository {
                maven {
                    name = "Modrinth"
                    url = uri("https://api.modrinth.com/maven")
                }
            }
            filter {
                includeGroup("maven.modrinth")
            }
        }

        // exclusiveContent {
        //     forRepository {
        //         maven {
        //             name = "CurseForge"
        //             url = uri("https://cursemaven.com")
        //         }
        //     }
        //     filter {
        //         includeGroup("curse.maven")
        //     }
        // }

        maven {
            url = uri("https://maven.shedaniel.me/")
        }

        maven {
            url = uri("https://maven.terraformersmc.com/releases/")
        }

        maven {
            name = "CaffeineMC"
            url = uri("https://maven.caffeinemc.net/releases") // or /snapshots
        }

        exclusiveContent {
            forRepository {
                ivy {
                    name = "github"
                    url = uri("https://github.com/")

                    patternLayout {
                        artifact("/[organisation]/[module]/releases/download/[revision]/[module]-[revision]-[classifier].[ext]")
                    }

                    metadataSources {
                        artifact()
                    }
                }
            }

            filter {
                includeModuleByRegex("[^\\.]+", "nvidium")
            }
        }
        maven {
            name = "Sinytra"
            url = uri("https://maven.su5ed.dev/releases")
        }
    }
}

extensions.configure<StonecutterBuildExtension> {
    constants {
        match(
            stonecutterLoaderName,
            "fabric",   // != "loader" -> false
            "neoforge", // == "loader" -> true
            "forge",    // != "loader" -> false
        )
    }
}

// Source sets are wired per-loader: the shared core (src/main/java) is processed by
// Stonecutter into build/generated/stonecutter/main/java; per-version and per-loader
// sources are added via `sourceJavaDir` / `additionalSourceJavaDirs` (see below) or the
// loader buildscripts. Textual API differences between versions are handled by
// Stonecutter's `replacements.string(...)` DSL configured in settings.gradle.kts.

tasks.withType<Copy>().matching { it.name == "processResources" }.configureEach {
    val commit = try {
        val p = Runtime.getRuntime().exec(arrayOf("git", "rev-parse", "--short", "HEAD"))
        p.inputStream.bufferedReader().readText().trim().ifEmpty { "<UnknownCommit>" }
    } catch (e: Exception) {
        "<UnknownCommit>"
    }
    val buildtime = (System.currentTimeMillis() / 1000L).toString()

    val props = mapOf(
        "version" to modVersion,
        "mod_version" to modVersion,
        "minecraft" to minecraftVersion,
        "commit" to commit,
        "buildtime" to buildtime,
        "mod_name" to modName,
        "mod_id" to modId,
        "mod_license" to "All Rights Reserved",
        "mod_authors" to "MCRcortex",
        "mod_description" to "Voxy is an LoD rendering mod for minecraft",
        "loader_version_range" to "[47,)",
        "forge_version_range" to "[47,)",
        "minecraft_version_range" to "[$minecraftVersion,)",
    )

    filesMatching(listOf("fabric.mod.json", "META-INF/neoforge.mods.toml", "META-INF/mods.toml")) {
        expand(props)
    }
}

version = "$modVersion+$minecraftVersion-$loaderName"

extensions.configure<BasePluginExtension> {
    archivesName.set(modId)
}

extensions.configure<SourceSetContainer> {
    named("main") {
        sourceJavaDir?.let { java.srcDir(rootProject.file(it)) }
        additionalSourceJavaDirs.forEach { dir ->
            val f = rootProject.file(dir)
            if (f.exists()) {
                val already = java.srcDirs.any { it.canonicalPath == f.canonicalPath }
                if (!already) java.srcDir(f)
            }
        }
        resources.srcDir("src/main/generated")
    }
}

extensions.configure<JavaPluginExtension> {
    withSourcesJar()
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
}

tasks.matching { it.name == "createMinecraftArtifacts" }.configureEach {
    dependsOn("stonecutterGenerate")
}

tasks.register<Copy>("buildAndCollect") {
    group = "build"
    from(tasks.named<AbstractArchiveTask>(archiveTaskName).flatMap { it.archiveFile })
    into(rootProject.layout.buildDirectory.file("libs/$modVersion"))
    dependsOn("build")
}

