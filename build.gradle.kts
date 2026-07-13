plugins {
    id("java")
    kotlin("jvm") version "2.1.10"
    id("org.jetbrains.intellij.platform") version "2.13.1"
}

group = "com.azora.lang"
version = "0.0.3"

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
    jvmToolchain(17)
}

intellijPlatform {
    pluginConfiguration {
        name = "Azora Language"
        version = project.version.toString()
        description = "Full language support for the Azora programming language."
        ideaVersion {
            sinceBuild = "253"
        }
        changeNotes = """
            <ul>
                <li>Updated syntax support for Azora 0.0.3.</li>
                <li>Context-aware keyword highlighting for soft keywords.</li>
                <li>Completion for std modules, grouped imports, annotations, fields, local bindings, and snippets.</li>
                <li>Project-aware go-to-definition and hover documentation.</li>
                <li>Zone, task, flow, deref, contracts, and reactive keyword support.</li>
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
    buildSearchableOptions {
        enabled = false
    }
    prepareJarSearchableOptions {
        enabled = false
    }
}
