plugins {
    id("java")
    kotlin("jvm") version "2.1.10"
    id("org.jetbrains.intellij.platform") version "2.13.1"
}

group = "org.azora.lang"
version = "0.0.6"

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
                <li>IntelliSense and semantic highlighting now follow the Azora 0.0.4 language rules used by AZLS and the web playground.</li>
                <li>Known project, dependency and SDK types, specs and functions are resolved before they are colored.</li>
                <li>Spec types and members, implementations, properties, decorators, type parameters, zone usage and import paths now have distinct semantic styles.</li>
                <li>Unused declarations, parameters, properties and spec members are dimmed automatically.</li>
                <li><code>where</code> is recognized as a contextual keyword only in valid declaration constraints.</li>
                <li><code>self</code> and <code>it</code> remain identifiers and are styled as receiver parameters in their scopes.</li>
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
