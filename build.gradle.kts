plugins {
    id("java")
    kotlin("jvm") version "2.1.10"
    id("org.jetbrains.intellij.platform") version "2.13.1"
}

group = "org.azora.lang"
version = "0.0.8"

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
            <p><b>Two new views</b></p>
            <ul>
                <li><b>The IR beside the source.</b> An <code>.az</code> file opens as an editor with a preview, the way a Markdown file does, in two tabs: <b>IR</b> and <b>Optimized IR</b>. It refreshes shortly after typing stops, from the buffer as it stands rather than its last save, and shows only <i>this file's</i> declarations. The compiler is asked rather than imitated, so the pane never disagrees with a build.</li>
                <li><b>Doc comments render in place.</b> A <code>/** … */</code> is drawn as formatted text and turns back into source the moment the caret enters it. The licence header at the top of a file folds by default.</li>
            </ul>
            <p><b>Imports</b></p>
            <ul>
                <li>One reader for the whole import grammar, so completion, navigation, Optimize Imports and highlighting agree. A group written across several lines, or with a comment in it, is now read correctly.</li>
                <li>Optimize Imports narrows <code>import path::*</code> to what the file uses, never touching an explicit import and leaving a wildcard alone when the index knows nothing about its module.</li>
                <li>Completion offers names that are not imported yet and writes the import in the same keystroke; Alt-Enter does the same for a name already written.</li>
                <li>Go-to-definition works on the segments of an import path.</li>
            </ul>
            <p><b>Errors for undefined symbols</b></p>
            <ul>
                <li>Every undeclared name is reported, not only capitalised ones and ones applied to <code>(</code>. It stays quiet because the editor learned the forms that bind without being declarations: loop rows, pattern captures, lambda parameters, scope names and <code>catch</code> bindings.</li>
                <li>A name that exists but is not imported here is reported separately, with the import as its fix.</li>
            </ul>
            <p><b>New language rules</b></p>
            <ul>
                <li>A <code>prop</code> only observes: <code>[self&amp;]</code> is the only receiver it takes.</li>
                <li>A width suffix is not part of a literal - <code>4L</code> is reported with both ways out: drop it, or write <code>Long(4)</code>.</li>
            </ul>
            <p><b>Diagnostics and quick fixes</b></p>
            <ul>
                <li>Borrowing: writing through a <code>[self&amp;]</code> is an error with the exclusive borrow as its fix; a <code>[self!]</code> that never writes is a weak warning with the shared one; and inside an <code>impl</code> a written receiver type is reported as saying nothing.</li>
                <li>Style, each with a fix: <code>i += 1</code> to <code>i++</code>; a run of <code>purge</code> statements to one <code>purge [a, b]</code>; a one-line body onto its own line; <code>[a: Int, b: Int]</code> to <code>[a, b]: Int</code>; a repeated initializer to a single value; a one-statement <code>when</code> arm without braces; a redundant constructor; a one-expression property in its short form.</li>
                <li>Unused locals, parameters and members are reported - never dimmed, since a colour says what a name is rather than whether anyone calls it.</li>
            </ul>
            <p><b>Colours</b></p>
            <ul>
                <li><code>prop</code> names read as any other name rather than brighter than the fields beside them; parameters are a light grey-blue; generic type parameters take the macro colour; error cases are redder; a <code>${'$'}{…}</code> macro hole is gold including its braces.</li>
                <li>A <code>@Deprecated</code> declaration is struck through wherever its name appears.</li>
                <li><code>[a, b, c] = [x, y, z]</code> highlights each name together with the value it answers to.</li>
            </ul>
            <p><b>Fixes</b></p>
            <ul>
                <li>Doc comments no longer lose their colour while a file is edited.</li>
                <li>Colours update as soon as a declaration in another open tab changes, rather than waiting for a reparse.</li>
                <li><code>derives</code> keeps its keyword colour after a pack that states which literal it is written as, and when the clause opens its own line.</li>
                <li><code>where</code> keeps its keyword colour after a signature with a callable parameter.</li>
                <li>A quick fix is no longer built from text with its literals blanked out.</li>
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
