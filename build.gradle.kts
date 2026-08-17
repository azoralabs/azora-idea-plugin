plugins {
    id("java")
    kotlin("jvm") version "2.1.10"
    id("org.jetbrains.intellij.platform") version "2.13.1"
}

group = "org.azora.lang"
version = "0.0.7"

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
                <li>Synchronized reserved and contextual words with the compiler, including contextual <code>assoc</code>/<code>derives</code>, reserved <code>without</code>, and treating <code>reflect</code> as a function.</li>
                <li>Corrected semantic colors: annotations are yellow, generic parameters orange, loop labels blue, functions use the normal foreground, and complete realm paths are italic.</li>
                <li>Rebuilt go-to-declaration and hover around symbol identity and use-site role, with lexical shadowing, receiver members, exact declaration offsets, documentation, stdlib, and path dependencies.</li>
                <li>Fixed exact-prefix completion so <code>Anchor</code> no longer completes as an unrelated suffix type such as <code>TilemapAnchor</code>.</li>
                <li>Added unused-local warnings and precise diagnostics with quick fixes for Azora naming, constructor/enum shorthand, floating-point literals, imports, annotations, strings, and brackets.</li>
                <li>Added automatic indentation, code-style settings, and Reformat Code support for nested comma-free <code>.azon</code> objects and arrays; updated Azora indentation, structure view, run markers, project scaffolds, snippets, and live templates to current syntax.</li>
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
