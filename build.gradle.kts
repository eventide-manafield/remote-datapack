plugins {
    java
    id("com.gradleup.shadow") version "9.6.1"
}

group = "studio.manafield"
version = "1.3.1-SNAPSHOT"
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
    implementation("org.eclipse.jetty:jetty-server:12.1.14")
    implementation("org.slf4j:slf4j-nop:2.0.20")
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
    inputs.property("pluginVersion", pluginVersion)
    filesMatching("plugin.yml") { expand("version" to pluginVersion) }
    from("frontend/dist") { into("web") }
    from(layout.buildDirectory.file("generated/web-assets.list"))
    from(files("LICENSE", "NOTICE", "THIRD_PARTY_NOTICES.md")) { into("META-INF") }
    from("licenses") { into("META-INF/licenses") }
}
// The distributable is the single JAR containing the private HTTP libraries.
tasks.jar { enabled = false }

// Keep the HTTP implementation private to this plugin, including its silent logger.
// No server-provided libraries or JVM-global logging configuration are required.
tasks.shadowJar {
    archiveFileName.set("remote-datapack-${pluginVersion}.jar")
    archiveClassifier.set("")
    relocate("org.eclipse.jetty", "studio.manafield.remotedatapack.internal.jetty")
    relocate("org.slf4j", "studio.manafield.remotedatapack.internal.slf4j")
    filesMatching("META-INF/services/**") { duplicatesStrategy = DuplicatesStrategy.INCLUDE }
    mergeServiceFiles()
    exclude("module-info.class", "META-INF/versions/**/module-info.class", "META-INF/*.SF", "META-INF/*.RSA", "META-INF/*.DSA")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}
tasks.assemble { dependsOn(tasks.shadowJar) }
