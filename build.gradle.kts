plugins { java }

group = "studio.manafield"
version = "1.2.0-java21-SNAPSHOT"
val pluginVersion = version.toString()

java { toolchain.languageVersion.set(JavaLanguageVersion.of(21)) }

repositories {
    mavenCentral()
    exclusiveContent {
        forRepository {
            maven {
                name = "paperApiArtifact"
                url = uri("https://repo.papermc.io/repository/maven-public/")
                metadataSources { artifact() }
            }
        }
        filter { includeModule("io.papermc.paper", "paper-api") }
    }
    maven("https://repo.papermc.io/repository/maven-public/") {
        content { includeGroupByRegex("io\\.papermc.*"); includeGroup("com.mojang"); includeGroup("net.md-5") }
    }
}

dependencies {
    // Artifact-only: the historic POM imports a mutable Adventure SNAPSHOT BOM.
    // Checksum-pin the API snapshot and declare the compile-time types we use explicitly.
    compileOnly("io.papermc.paper:paper-api:1.21.9-R0.1-SNAPSHOT@jar") { isTransitive = false }
    compileOnly("net.kyori:adventure-api:4.25.0")
    compileOnly("org.yaml:snakeyaml:2.2")
    compileOnly("net.md-5:bungeecord-chat:1.21-R0.2-deprecated+build.21") { isTransitive = false }
}

dependencyLocking { lockAllConfigurations() }

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release.set(21)
}

val npmCommand = if (System.getProperty("os.name").startsWith("Windows")) "npm.cmd" else "npm"
val frontendInstall = tasks.register<Exec>("frontendInstall") {
    workingDir("frontend")
    commandLine(npmCommand, "ci", "--no-audit", "--no-fund")
    inputs.files("frontend/package.json", "frontend/package-lock.json", "frontend/.npmrc")
    outputs.dir("frontend/node_modules")
}
val frontendBuild = tasks.register<Exec>("frontendBuild") {
    dependsOn(frontendInstall)
    workingDir("frontend")
    commandLine(npmCommand, "run", "build")
    inputs.dir("frontend/src")
    inputs.files("frontend/index.html", "frontend/tsconfig.json", "frontend/package.json", "frontend/package-lock.json")
    outputs.dir("frontend/dist")
}
val webManifest = tasks.register("webManifest") {
    dependsOn(frontendBuild)
    inputs.dir("frontend/dist")
    val root = layout.projectDirectory.dir("frontend/dist").asFile
    val output = layout.buildDirectory.file("generated/web-assets.list")
    outputs.file(output)
    doLast {
        val names = root.walkTopDown().filter { it.isFile }.map { it.relativeTo(root).invariantSeparatorsPath }.sorted().toList()
        require("index.html" in names) { "Missing frontend index.html" }
        output.get().asFile.apply { parentFile.mkdirs(); writeText(names.joinToString("\n", postfix = "\n")) }
    }
}
tasks.processResources {
    dependsOn(webManifest)
    filesMatching("plugin.yml") { expand("version" to pluginVersion) }
    from("frontend/dist") { into("web") }
    from(layout.buildDirectory.file("generated/web-assets.list"))
    from(files("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md")) { into("META-INF") }
    from("licenses") { into("META-INF/licenses") }
}
tasks.jar {
    archiveFileName.set("remote-datapack-${pluginVersion}.jar")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
