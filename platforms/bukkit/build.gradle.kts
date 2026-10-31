plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.paperweight)
    alias(libs.plugins.shadow)
    alias(libs.plugins.runpaper)
}

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

kotlin {
    jvmToolchain(25)
}

dependencies {
    implementation(project(":core"))
    paperweight.paperDevBundle(libs.versions.paper.get())
}

tasks.processResources {
    filesMatching("paper-plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveClassifier.set("plain")
}

tasks.shadowJar {
    archiveClassifier.set("")
    archiveBaseName.set("AgentCraft")
    mergeServiceFiles()
    filesMatching(listOf("META-INF/services/**", "META-INF/*.kotlin_module")) {
        duplicatesStrategy = DuplicatesStrategy.INCLUDE
    }
    exclude(
        "META-INF/*.SF",
        "META-INF/*.DSA",
        "META-INF/*.RSA",
        "module-info.class",
        "META-INF/versions/*/module-info.class",
    )
}

tasks.assemble {
    dependsOn(tasks.shadowJar)
}

tasks.runServer {
    minecraftVersion("26.3")
}
