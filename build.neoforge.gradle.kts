@file:Suppress("UnstableApiUsage")

import me.cortex.voxy.gradle.prop

extra["loaderName"] = "neoforge"
extra["loaderDisplayName"] = "NeoForge"
extra["archiveTaskName"] = "jar"
extra["sourceJavaDir"] = "src/neoforge/java"
extra["additionalSourceJavaDirs"] = listOf(
    "versions/${prop("deps.minecraft", "minecraft_version")}/src/java"
)

plugins {
    id("net.neoforged.moddev") version "2.0.141"
    id("me.modmuss50.mod-publish-plugin") version "2.0.0-beta.1"
    id("dev.kikugie.fletching-table.neoforge") version "0.1.0-alpha.23"
    id("org.sinytra.adapter.userdev") version "1.2.1-SNAPSHOT"
}

apply(from = rootProject.file("build.common.gradle.kts"))

val modId: String by extra
val minecraftVersion: String by extra
val jedisVersion: String by extra
val rocksdbVersion: String by extra
val commonsPoolVersion: String by extra
val lz4Version: String by extra
val xzVersion: String by extra
val sqliteJdbcVersion: String by extra
val neoForgeVersion = prop("deps.neoforge", "neoforge_version")

val lwjglVersion = "3.3.3"

val shadedDependencies = configurations.create("shadedDependencies") {
    isCanBeConsumed = false
    isCanBeResolved = true
    isTransitive = false
}

dependencies {
    // annotationProcessor("net.fabricmc:sponge-mixin:0.17.2+mixin.0.8.7")

    // Compile against the flat caffeinemc maven jars (api + impl surface); run against the
    // complete Modrinth distribution jar (bundles sodium's core, e.g. Workarounds).
    val sodiumNeoVer = prop("deps.sodium")
    implementation("net.caffeinemc:sodium-neoforge-mod:$sodiumNeoVer")
    implementation("net.caffeinemc:sodium-neoforge-api:$sodiumNeoVer")
    implementation("net.caffeinemc:sodium-neoforge:$sodiumNeoVer")

    val lithiumNeo = prop("deps.lithium")
    implementation("maven.modrinth:lithium:$lithiumNeo")

    compileOnly("drouarb:nvidium:${prop("deps.nvidium")}")

    val irisNeo = prop("deps.iris")
    compileOnly("maven.modrinth:iris:$irisNeo")

    compileOnly("maven.local:modmenu-bridge:1.12.1+1.21.1")

    runtimeOnly("io.github.douira:glsl-transformer:2.0.1")
    runtimeOnly("org.anarres:jcpp:1.4.14")

    val sodiumExtraNeo = prop("deps.sodium.extra")
    compileOnly("maven.modrinth:sodium-extra:$sodiumExtraNeo")

    val chunkyNeo = prop("deps.chunky")
    compileOnly("maven.modrinth:Chunky:$chunkyNeo")
    runtimeOnly("maven.modrinth:Chunky:$chunkyNeo")

    val sparkNeo = prop("deps.spark")
    runtimeOnly("maven.modrinth:spark:$sparkNeo")

    val viveNeo = prop("deps.vivecraft")
    compileOnly("maven.modrinth:vivecraft:$viveNeo")
    compileOnly("maven.modrinth:flashback:${prop("deps.flashback")}")

    // Sable sub-level compat (1.21.1 only — deps.sable is defined only in the
    // 1.21.1 node gradle.properties; classes compile from versions/1.21.1/src/java).
    // Compile-only: runtime is user-provided.
    runCatching { prop("deps.sable") }.getOrNull()?.let { sableVer ->
        compileOnly("dev.ryanhcode.sable:sable-neoforge-1.21.1:$sableVer@jar")
        compileOnly("dev.ryanhcode.sable-companion:sable-companion-common-1.21.1:${prop("deps.sable-companion")}@jar")
    }

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

    jarJar("redis.clients:jedis:$jedisVersion")
    jarJar("org.rocksdb:rocksdbjni:$rocksdbVersion")
    jarJar("org.apache.commons:commons-pool2:$commonsPoolVersion")
    jarJar("org.tukaani:xz:$xzVersion")
    jarJar("org.xerial:sqlite-jdbc:$sqliteJdbcVersion")

    shadedDependencies("org.lwjgl:lwjgl-lmdb:$lwjglVersion")
    shadedDependencies("org.lwjgl:lwjgl-zstd:$lwjglVersion")
    shadedDependencies("org.lwjgl:lwjgl-lmdb:$lwjglVersion:natives-windows")
    shadedDependencies("org.lwjgl:lwjgl-zstd:$lwjglVersion:natives-windows")
    shadedDependencies("org.lwjgl:lwjgl-lmdb:$lwjglVersion:natives-linux")
    shadedDependencies("org.lwjgl:lwjgl-zstd:$lwjglVersion:natives-linux")
}

tasks.named<Jar>("jar") {
    from(shadedDependencies.elements.map { jars -> jars.map { zipTree(it) } }) {
        exclude("**/module-info.class", "META-INF/MANIFEST.MF", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    }
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE
}

// Dev runs (runClient/runServer) don't receive the shaded libraries on their classpath.
// The moddev `additionalRuntimeClasspath` configuration is created when runs are registered,
// so populate it after evaluation with voxy's runtime libraries.
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

fletchingTable {
    accessConverter.register(sourceSets.main) {
        add(sc.process(rootProject.file("src/main/resources/voxy.accesswidener"), "build/processed.accesswidener").path)
    }
}

neoForge {
    version = neoForgeVersion
    accessTransformers.from("src/main/resources/META-INF/accesstransformer.cfg")
    validateAccessTransformers = true

    if (providers.gradleProperty("deps.parchment").isPresent) {
        parchment {
            val parchmentValue = providers.gradleProperty("deps.parchment").get()
            val (mc, ver) = parchmentValue.split(':')
            mappingsVersion = ver
            minecraftVersion = mc
        }
    }

    runs {
        register("client") {
            gameDirectory = file("run/")
            client()
        }
        register("server") {
            gameDirectory = file("run/")
            server()
        }
    }

    mods {
        register(modId) {
            sourceSet(sourceSets["main"])
        }
    }
}
