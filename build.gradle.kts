plugins {
    id("java")
    kotlin("jvm") version "2.1.10"
    id("org.jetbrains.intellij.platform") version "2.13.1"
}

group = "org.azora.lang"
version = "0.0.9"

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
            intellijIdeaCommunity("2025.1")
        }
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Bundled)
    }
    // Android Studio 2025.3 bundles LSP4J 0.21.2. Compile against that exact
    // protocol API without packaging a duplicate runtime into the plugin.
    compileOnly("org.eclipse.lsp4j:org.eclipse.lsp4j:0.21.2")
    compileOnly("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:0.21.2")
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
            <p><b>AZLS process integration</b></p>
            <ul>
                <li>The plugin now launches AZLS as an isolated external process and communicates over standard JSON-RPC/LSP.</li>
                <li>Compiler-owned, versioned diagnostics and exact ranges replace the plugin's duplicate semantic analyzer.</li>
                <li>Completion, hover, definition, semantic tokens, and version-checked quick fixes are served through the same AZLS snapshot.</li>
                <li>Associated types such as <code>Iterator::Item</code> use their dedicated teal semantic role at declarations and use sites.</li>
                <li>The server is restarted a bounded number of times after a crash and is shut down with the project.</li>
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
