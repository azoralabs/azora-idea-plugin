plugins {
    id("java")
    kotlin("jvm") version "2.1.10"
    id("org.jetbrains.intellij.platform") version "2.13.1"
}

group = "org.azora.lang"
version = "0.0.5"

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
        description = "Full language support for the Azora programming language."
        ideaVersion {
            sinceBuild = "253"
        }
        changeNotes = """
            <ul>
                <li>Official Azora color palette, shared with the Azora code playground, with bold keywords, purple macros and wavy diagnostics — all overridable.</li>
                <li>Macros are now discovered from real <code>meta</code> declarations in your project and SDK: a keyword-named macro such as <code>with</code> is purple where it is a macro and a keyword everywhere else. Nothing is hardcoded.</li>
                <li>String interpolation (<code>${'$'}name</code>, <code>${'$'}{…}</code>) and escape sequences are lexed and colored.</li>
                <li>Member (<code>.</code>) and zone (<code>::</code>) completion resolve real fields, methods and module members.</li>
                <li>Live templates for every Azora declaration form.</li>
                <li>Rebuilt go-to-declaration, hover documentation and indentation.</li>
                <li>Inferred-type inlay hints and smart-cast highlighting.</li>
                <li>Errors are underlined with quick fixes.</li>
                <li>Run gutter icons on <code>func main</code>, <code>task main</code> and every <code>test</code>.</li>
                <li><code>.azon</code> file support plus lib / exe / multi-module project templates.</li>
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
