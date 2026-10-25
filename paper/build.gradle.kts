import io.papermc.paperweight.userdev.ReobfArtifactConfiguration

plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
}

group = "com.tacz.guns"
version = "1.1.8-hotfix2-paper-SNAPSHOT"

repositories {
    mavenCentral()
    mavenLocal { content { includeModule("com.ayou", "ProtocolSupportCustomPacket") } }
}

dependencies {
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
    compileOnly("com.ayou:ProtocolSupportCustomPacket:1.4.0-SNAPSHOT") { isTransitive = false }
    testImplementation("com.ayou:ProtocolSupportCustomPacket:1.4.0-SNAPSHOT") { isTransitive = false }
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testImplementation("com.google.code.gson:gson:2.10.1")
    testImplementation("org.mockito:mockito-core:5.14.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

sourceSets.main { java.srcDir("../shared/src/main/java") }
sourceSets.test { java.srcDir("../shared/src/test/java") }

val defaultGunPack by tasks.registering(Zip::class) {
    from("../src/main/resources/assets/tacz/custom/tacz_default_gun") {
        include("data/**", "gunpack.meta.json", "assets/*/display/attachments/**/*.json", "assets/*/display/guns/**/*.json")
    }
    archiveFileName = "default-pack.zip"
    destinationDirectory = layout.buildDirectory.dir("generated/pack")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.test { useJUnitPlatform() }

java {
    toolchain.languageVersion = JavaLanguageVersion.of(21)
}

// Paper 1.21.11 runs Mojang mappings; publish a directly loadable NMS-enabled JAR.
paperweight.reobfArtifactConfiguration = ReobfArtifactConfiguration.MOJANG_PRODUCTION

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    options.release = 21
}

tasks.processResources {
    from(defaultGunPack)
    filteringCharset = "UTF-8"
    val pluginVersion = project.version.toString()
    inputs.property("version", pluginVersion)
    filesMatching("plugin.yml") {
        expand("version" to pluginVersion)
    }
}

tasks.jar {
    manifest.attributes("paperweight-mappings-namespace" to "mojang")
}

val serverConfigDirectory = layout.buildDirectory.dir("generated/server-config")
val exportDefaultConfig by tasks.registering(JavaExec::class) {
    group = "distribution"
    description = "Exports validated plugin configuration using the real first-run catalog loader."
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass = "com.tacz.guns.paper.pack.PackConfigExporter"
    javaLauncher = javaToolchains.launcherFor { languageVersion = JavaLanguageVersion.of(21) }
    args(serverConfigDirectory.get().asFile.absolutePath)
    inputs.file(defaultGunPack.flatMap { it.archiveFile })
    outputs.dir(serverConfigDirectory)
    doFirst {
        // This is exclusively generated build output. The first-run marker must not retain
        // removed defaults from an earlier build; live plugins/TACZ is never touched.
        delete(serverConfigDirectory)
    }
}

val defaultServerConfig by tasks.registering(Zip::class) {
    group = "distribution"
    description = "Packages the five configuration directories for extraction into plugins/TACZ."
    dependsOn(exportDefaultConfig)
    from(serverConfigDirectory) {
        include("guns/**", "ammos/**", "attachments/**", "recipes/**", "attachment_tags/**", ".pack-files-v1")
    }
    archiveFileName = "tacz-paper-bjd-config.zip"
    destinationDirectory = layout.buildDirectory.dir("distributions")
    isPreserveFileTimestamps = false
    isReproducibleFileOrder = true
}

tasks.assemble { dependsOn(defaultServerConfig) }
