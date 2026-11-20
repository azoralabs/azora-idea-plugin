plugins {
    id("java")
    kotlin("jvm") version "2.3.10"
    id("org.jetbrains.intellij.platform") version "2.13.1"
}

group = "org.azora.lang"
version = "0.1.1"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

val useLocalIde = file("/Applications/Android Studio.app").exists() && System.getenv("CI") == null

dependencies {
    intellijPlatform {
        if (useLocalIde) {
            local("/Applications/Android Studio.app")
        } else {
            intellijIdea("2025.3")
        }
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Bundled)
    }
    // Own the transport dependency on both IntelliJ IDEA and Android Studio.
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.21.2") {
        // The IntelliJ platform supplies Gson.
        exclude(group = "com.google.code.gson")
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testImplementation("junit:junit:4.13.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("org.junit.vintage:junit-vintage-engine:5.11.4")
}

kotlin {
    jvmToolchain(21)
}

intellijPlatform {
    buildSearchableOptions = false
    pluginConfiguration {
        name = "Azora Language"
        version = project.version.toString()
        // `description` is deliberately not set here: setting it makes
        // patchPluginXml replace the rich <description> in plugin.xml, and that
        // block is what the Marketplace renders as the plugin's page.
        ideaVersion {
            sinceBuild = "253"
        }
        changeNotes = """
            <ul>
                <li>Fix block indentation when moving code with Cut and Paste, including selections that begin at the first token.</li>
                <li>Indent multiline modifier chains inside named arguments and <code>when</code> branches.</li>
            </ul>
        """.trimIndent()
    }

    publishing {
        token = providers.environmentVariable("PUBLISH_TOKEN")
    }
}

tasks {
    test {
        useJUnitPlatform()
    }
}

// Release CI builds this artifact from the pinned compiler checkout before Gradle runs.
val compilerDir = file(providers.environmentVariable("AZORA_COMPILER_DIR").orElse("../azora-lang").get())
val azlsJar = compilerDir.resolve("azls/build/libs/azls-all.jar")

tasks.withType<org.jetbrains.intellij.platform.gradle.tasks.PrepareSandboxTask>().configureEach {
    from(listOf(azlsJar, compilerDir.resolve("LICENSE"))) { into("${project.name}/azls") }
    from(compilerDir.resolve("std")) {
        include("**/*.az")
        into("${project.name}/azls/std/src")
    }
    from("src/main/azls/package.azon") { into("${project.name}/azls/std") }
}

tasks.buildPlugin {
    doFirst {
        check(azlsJar.isFile) { "Build :azls:fatJar in $compilerDir before packaging Azora." }
    }
}

tasks.test {
    inputs.file(azlsJar)
    systemProperty("azora.test.azlsJar", azlsJar.absolutePath)
}
