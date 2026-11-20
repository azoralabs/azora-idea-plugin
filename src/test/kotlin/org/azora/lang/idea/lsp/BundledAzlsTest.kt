/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.lang.idea.lsp

import org.azora.lang.idea.AzoraLanguageFacts
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.net.URLClassLoader
import java.nio.file.Path
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit

class BundledAzlsTest {
    private val jar = File(requireNotNull(System.getProperty("azora.test.azlsJar")))

    @Test
    fun `plugin reserved words match the bundled compiler`() {
        URLClassLoader(arrayOf(jar.toURI().toURL()), ClassLoader.getPlatformClassLoader()).use { loader ->
            val vocabulary = loader.loadClass("org.azora.lang.frontend.AzoraSyntaxVocabulary")
            val words = vocabulary.getMethod("getReservedKeywords").invoke(vocabulary.getField("INSTANCE").get(null))
                as Map<*, *>
            assertEquals(words.keys, AzoraLanguageFacts.hardKeywords)
        }
    }

    @Test
    fun `completion and singleton definition round trip over stdio`(@TempDir root: Path) {
        val java = File(System.getProperty("java.home"), "bin/java")
        val process = ProcessBuilder(java.path, "-jar", jar.absolutePath, "--stdio")
            .redirectError(ProcessBuilder.Redirect.INHERIT)
            .start()
        try {
            val client = object : LanguageClient {
                override fun telemetryEvent(value: Any?) = Unit
                override fun publishDiagnostics(params: PublishDiagnosticsParams) = Unit
                override fun showMessage(params: MessageParams) = Unit
                override fun logMessage(params: MessageParams) = Unit
                override fun showMessageRequest(params: ShowMessageRequestParams): CompletableFuture<MessageActionItem> =
                    CompletableFuture.completedFuture(null)
            }
            val launcher = LSPLauncher.Builder<LanguageServer>()
                .setLocalService(client)
                .setRemoteInterface(LanguageServer::class.java)
                .setInput(process.inputStream)
                .setOutput(process.outputStream)
                .configureGson { AzoraLspCodec.configure(it) }
                .create()
            launcher.startListening()
            val server = launcher.remoteProxy
            server.initialize(InitializeParams()).get(10, TimeUnit.SECONDS)
            server.initialized(InitializedParams())
            val text = """
                func greeting(name: String): String = name
                func main() {
                    greeting("Azora")
                }
            """.trimIndent()
            val uri = root.resolve("main.az").toUri().toString()
            val document = TextDocumentIdentifier(uri)
            server.textDocumentService.didOpen(DidOpenTextDocumentParams(TextDocumentItem(uri, "azora", 1, text)))
            val completion = server.textDocumentService.completion(CompletionParams(document, Position(2, 8)))
                .get(10, TimeUnit.SECONDS)
            val items = if (completion.isLeft) completion.left else completion.right.items
            assertTrue(items.any { it.label == "greeting" })
            val location = server.textDocumentService.definition(DefinitionParams(document, Position(2, 6)))
                .get(10, TimeUnit.SECONDS).left.single()
            assertEquals(uri, location.uri)
            assertEquals(0, location.range.start.line)
            server.shutdown().get(10, TimeUnit.SECONDS)
            server.exit()
        } finally {
            if (!process.waitFor(1, TimeUnit.SECONDS)) process.destroyForcibly()
        }
    }
}
