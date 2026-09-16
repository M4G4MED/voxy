@file:Suppress("UnstableApiUsage")

import me.cortex.voxy.gradle.prop
import org.gradle.api.tasks.Copy
import org.gradle.api.tasks.bundling.Jar
import net.neoforged.moddevgradle.legacyforge.dsl.MixinExtension

extra["loaderName"] = "legacyforge"
extra["loaderDisplayName"] = "LegacyForge"
extra["archiveTaskName"] = "jar"
extra["sourceJavaDir"] = "src/forge/java"

plugins {
    id("java")
    id("net.neoforged.moddev.legacyforge") version "2.0.141"
    id("me.modmuss50.mod-publish-plugin") version "2.0.0-beta.1"
    // Provide fletching-table conversion (accesswidener -> accesstransformer)
    id("dev.kikugie.fletching-table.lexforge") version "0.1.0-alpha.23"
}

apply(from = rootProject.file("build.common.gradle.kts"))

fletchingTable {
    accessConverter.register(sourceSets.main) {
        add(sc.process(rootProject.file("src/main/resources/voxy.accesswidener"), "build/processed.accesswidener").path)
    }
}

extensions.configure<MixinExtension> {
    add(sourceSets.main.get(), "voxy.refmap.json")
    sc.process(rootProject.file("src/main/resources/client.voxy.mixins.json"), "build/processed.client.voxy.mixins.json")
    sc.process(rootProject.file("src/main/resources/common.voxy.mixins.json"), "build/processed.common.voxy.mixins.json")
    config("client.voxy.mixins.json")
    config("common.voxy.mixins.json")
}

val modId = prop("mod.id", "archives_base_name")
val mcVersion = prop("deps.minecraft", "minecraft_version")
val forgeVersion = prop("deps.forge", "forge_version")

if (project.name.startsWith("1.20.1")) {
    configurations.configureEach {
        resolutionStrategy {
            force("org.antlr:antlr4-runtime:4.9.1", "org.antlr:antlr4:4.9.1")
        }
    }
}

// Additional dependency versions (shared with fabric build)
val jedisVersion: String by extra
val rocksdbVersion: String by extra
val commonsPoolVersion: String by extra
val lz4Version: String by extra
val xzVersion: String by extra
val sqliteJdbcVersion: String by extra

val lwjglVersion = "3.3.1"

val shadedDependencies = configurations.create("shadedDependencies") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    // MixinExtras required by generated mixins (used across variants)
    annotationProcessor("io.github.llamalad7:mixinextras-common:0.5.4")
    compileOnly("org.spongepowered:mixin:0.8.7")
    annotationProcessor("org.spongepowered:mixin:0.8.7:processor")
    compileOnly("io.github.llamalad7:mixinextras-common:0.5.4")
    compileOnly("io.github.llamalad7:mixinextras-forge:0.5.4")
    val embeddiumVer = prop("deps.embeddium")
    modCompileOnly("maven.modrinth:embeddium:$embeddiumVer")
    modRuntimeOnly("maven.modrinth:embeddium:$embeddiumVer")
    val oculusVer = prop("deps.oculus")
    modCompileOnly("maven.modrinth:oculus:$oculusVer")
    modRuntimeOnly("maven.modrinth:oculus:$oculusVer")

    val lithiumForgeVer = prop("deps.radium")
    modCompileOnly("maven.modrinth:radium:$lithiumForgeVer")
    val nvidiumMaven = prop("deps.nvidium_maven")
    modCompileOnly("${nvidiumMaven}:nvidium:${prop("deps.nvidium")}")


    // modCompileOnly("org.sinytra:Connector:1.0.0-beta.48+1.20.1")
    // modRuntimeOnly("maven.modrinth:connector-extras:1.11.2+1.20.1")
    modCompileOnly("maven.local:modmenu-bridge:1.11.2+1.20.1")
    // val modmenuVer = prop("deps.modmenu")
    // modCompileOnly("maven.modrinth:modmenu:$modmenuVer")

    runtimeOnly("io.github.douira:glsl-transformer:2.0.1")
    runtimeOnly("org.anarres:jcpp:1.4.14")

    val sodiumExtraFabric = prop("deps.sodium.extra")
    modCompileOnly("maven.modrinth:sodium-extra:$sodiumExtraFabric")

    val chunkyFabric = prop("deps.chunky")
    modCompileOnly("maven.modrinth:Chunky:$chunkyFabric")
    modRuntimeOnly("maven.modrinth:Chunky:$chunkyFabric")

    val sparkFabric = prop("deps.spark")
    modRuntimeOnly("maven.modrinth:spark:$sparkFabric")

    val fabricPerms = prop("deps.fabric.permissions")
    modRuntimeOnly("maven.modrinth:fabric-permissions-api:$fabricPerms")

    val viveFabric = prop("deps.vivecraft")
    modCompileOnly("maven.modrinth:vivecraft:$viveFabric")
    modCompileOnly("maven.modrinth:flashback:${prop("deps.flashback")}")

    implementation(platform("org.lwjgl:lwjgl-bom:$lwjglVersion"))
    implementation("org.lwjgl:lwjgl")
    implementation("org.lwjgl:lwjgl-lmdb:$lwjglVersion")
    implementation("org.lwjgl:lwjgl-zstd:$lwjglVersion")
    runtimeOnly("org.lwjgl:lwjgl:$lwjglVersion:natives-windows")
    runtimeOnly("org.lwjgl:lwjgl:$lwjglVersion:natives-linux")
    runtimeOnly("org.lwjgl:lwjgl-zstd:$lwjglVersion:natives-windows")
    runtimeOnly("org.lwjgl:lwjgl-zstd:$lwjglVersion:natives-linux")

    implementation("redis.clients:jedis:$jedisVersion")
    implementation("org.rocksdb:rocksdbjni:$rocksdbVersion")
    implementation("org.apache.commons:commons-pool2:$commonsPoolVersion")
    implementation("org.lz4:lz4-java:$lz4Version")
    implementation("org.tukaani:xz:$xzVersion")
    runtimeOnly("org.xerial:sqlite-jdbc:$sqliteJdbcVersion")

    implementation(jarJar("redis.clients:jedis:$jedisVersion")!!)
    implementation(jarJar("org.rocksdb:rocksdbjni:$rocksdbVersion")!!)
    implementation(jarJar("org.apache.commons:commons-pool2:$commonsPoolVersion")!!)
    implementation(jarJar("org.lz4:lz4-java:$lz4Version")!!)
    implementation(jarJar("org.tukaani:xz:$xzVersion")!!)
    implementation(jarJar("org.xerial:sqlite-jdbc:$sqliteJdbcVersion")!!)
    shadedDependencies("org.lwjgl:lwjgl-lmdb:$lwjglVersion")
    shadedDependencies("org.lwjgl:lwjgl-zstd:$lwjglVersion")
    shadedDependencies("org.lwjgl:lwjgl-lmdb:$lwjglVersion:natives-windows")
    shadedDependencies("org.lwjgl:lwjgl-zstd:$lwjglVersion:natives-windows")
    shadedDependencies("org.lwjgl:lwjgl-lmdb:$lwjglVersion:natives-linux")
    shadedDependencies("org.lwjgl:lwjgl-zstd:$lwjglVersion:natives-linux")
}

// Dev runs (runClient/runServer) don't receive the jarJar'd / shaded libraries on their
// classpath. The moddev `additionalRuntimeClasspath` configuration is created when runs are
// registered, so populate it after evaluation with voxy's runtime libraries.
afterEvaluate {
    listOf(
        "redis.clients:jedis:$jedisVersion",
        "org.rocksdb:rocksdbjni:$rocksdbVersion",
        "org.apache.commons:commons-pool2:$commonsPoolVersion",
        "org.lz4:lz4-java:$lz4Version",
        "org.tukaani:xz:$xzVersion",
        "org.xerial:sqlite-jdbc:$sqliteJdbcVersion",
        "org.lwjgl:lwjgl-lmdb:$lwjglVersion",
        "org.lwjgl:lwjgl-zstd:$lwjglVersion",
        "org.lwjgl:lwjgl-lmdb:$lwjglVersion:natives-linux",
        "org.lwjgl:lwjgl-zstd:$lwjglVersion:natives-linux",
    ).forEach { dependencies.add("additionalRuntimeClasspath", it) }
}

legacyForge {
    version = "$mcVersion-$forgeVersion"
    accessTransformers.from("src/main/resources/META-INF/accesstransformer.cfg")
    validateAccessTransformers = true
    runs {
        register("client") {
            gameDirectory = file("run/")
            client()
        }
        register("server") {
            gameDirectory = file("run/")
            server()
        }
        register("data") {
            gameDirectory = file("run/")
            data()
        }
    }

    mods {
        register(modId) {
            sourceSet(sourceSets["main"])
        }
    }
}

afterEvaluate {
    extensions.findByType(SourceSetContainer::class.java)?.let { ssc ->
        ssc.named("main") {
            java.setSrcDirs(
                listOf(
                    // Stonecutter-processed shared core (src/main/java)
                    file("build/generated/stonecutter/main/java"),
                    // loader-shared sources
                    rootProject.file("src/forge/java"),
                    // per-Minecraft-version sources shared across loaders
                    rootProject.file("versions/${mcVersion}/src/java")
                )
            )
        }
    }
}

tasks.named<Jar>("jar") {
    manifest.attributes(
        mapOf(
            "MixinConfigs" to "client.voxy.mixins.json,common.voxy.mixins.json"
        )
    )
    // Merge the LWJGL lmdb/zstd bindings + natives into the mod jar. Drop each shaded jar's
    // module-info and manifest/signatures so the classes and native binaries fold into the mod's
    // own module rather than declaring separate (colliding) modules.
    from({ shadedDependencies.map { zipTree(it) } }) {
        exclude(
            "module-info.class",
            "META-INF/MANIFEST.MF",
            "META-INF/*.SF",
            "META-INF/*.DSA",
            "META-INF/*.RSA",
            "META-INF/versions/**/module-info.class",
        )
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}
