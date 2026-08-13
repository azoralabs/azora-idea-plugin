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
    }
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.4")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testRuntimeOnly("junit:junit:4.13.2")
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
                <li>Updated lexical and contextual highlighting for the current Azora vocabulary, including realms, variants, annotations, graphs, ownership, reactive, and clause keywords.</li>
                <li>Realm-qualified paths such as <code>ide::editor</code> now receive one consistent semantic style across every segment.</li>
                <li>Stdlib and dependency modules are indexed from both current <code>module</code>/<code>realm</code> sources and legacy <code>mod</code>/<code>zone</code> SDK sources.</li>
                <li>Go-to-declaration now resolves the symbol at the use site, respecting local shadowing, member receivers, types, callables, imports, and external source locations.</li>
                <li>Fixed navigation targets for local bindings and parameters, including exact declaration offsets.</li>
                <li>Generic type parameters are scoped to their declaring function or type instead of coloring unrelated same-named identifiers.</li>
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
