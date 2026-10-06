import io.papermc.paperweight.userdev.ReobfArtifactConfiguration

plugins {
    java
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21"
}

group = "com.tacz.guns"
version = "1.1.8-hotfix2-paper-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    paperweight.paperDevBundle("1.21.11-R0.1-SNAPSHOT")
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
        include("data/**", "gunpack.meta.json", "assets/tacz/display/attachments/*.json", "assets/tacz/display/guns/*.json")
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
