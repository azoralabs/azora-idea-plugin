/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.lang.idea.lsp

import com.google.gson.GsonBuilder
import com.google.gson.JsonDeserializer
import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
import com.intellij.ide.plugins.PluginManagerCore
import com.intellij.openapi.extensions.PluginId
import com.intellij.openapi.progress.ProgressManager
import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.command.WriteCommandAction
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.editor.Document
import com.intellij.openapi.editor.EditorFactory
import com.intellij.openapi.editor.event.DocumentEvent
import com.intellij.openapi.editor.event.DocumentListener
import com.intellij.openapi.fileEditor.FileDocumentManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.openapi.vfs.VirtualFileManager
import com.intellij.psi.PsiManager
import com.intellij.util.concurrency.AppExecutorUtil
import org.eclipse.lsp4j.ApplyWorkspaceEditParams
import org.eclipse.lsp4j.ApplyWorkspaceEditResponse
import org.eclipse.lsp4j.ClientCapabilities
import org.eclipse.lsp4j.ClientInfo
import org.eclipse.lsp4j.CodeAction
import org.eclipse.lsp4j.CodeActionContext
import org.eclipse.lsp4j.CodeActionParams
import org.eclipse.lsp4j.CompletionItem
import org.eclipse.lsp4j.CompletionParams
import org.eclipse.lsp4j.DefinitionParams
import org.eclipse.lsp4j.Diagnostic
import org.eclipse.lsp4j.DidChangeTextDocumentParams
import org.eclipse.lsp4j.DidCloseTextDocumentParams
import org.eclipse.lsp4j.DidOpenTextDocumentParams
import org.eclipse.lsp4j.GeneralClientCapabilities
import org.eclipse.lsp4j.Hover
import org.eclipse.lsp4j.HoverParams
import org.eclipse.lsp4j.InitializeParams
import org.eclipse.lsp4j.InitializedParams
import org.eclipse.lsp4j.Location
import org.eclipse.lsp4j.LocationLink
import org.eclipse.lsp4j.MessageActionItem
import org.eclipse.lsp4j.MessageParams
import org.eclipse.lsp4j.Position
import org.eclipse.lsp4j.PublishDiagnosticsParams
import org.eclipse.lsp4j.Range
import org.eclipse.lsp4j.SemanticTokensParams
import org.eclipse.lsp4j.ShowMessageRequestParams
import org.eclipse.lsp4j.TextDocumentContentChangeEvent
import org.eclipse.lsp4j.TextDocumentIdentifier
import org.eclipse.lsp4j.TextDocumentItem
import org.eclipse.lsp4j.VersionedTextDocumentIdentifier
import org.eclipse.lsp4j.WorkspaceEdit
import org.eclipse.lsp4j.WorkspaceFolder
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import org.eclipse.lsp4j.services.TextDocumentService
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.lang.reflect.ParameterizedType
import java.util.concurrent.TimeoutException
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Project-scoped AZLS process supervisor and LSP client.
 *
 * The plugin owns editor adaptation only. Parsing, diagnostics, fixes and
 * semantic roles come from the external AZLS process through standard LSP.
 */
class AzoraLspService(private val project: Project) : Disposable {
    private val log = Logger.getInstance(AzoraLspService::class.java)
    private val disposed = AtomicBoolean(false)
    private val restarts = AtomicInteger(0)
    private val opened = ConcurrentHashMap<String, OpenDocument>()
    private val diagnostics = ConcurrentHashMap<String, PublishedSnapshot>()
    private val actions = ConcurrentHashMap<String, ActionSnapshot>()
    private val semanticTokens = ConcurrentHashMap<String, TokenSnapshot>()
    @Volatile private var ready = CompletableFuture<LanguageServer>()
    @Volatile private var process: Process? = null
    @Volatile private var remote: LanguageServer? = null

    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                    if (!isAzora(file) || !opened.containsKey(uri(file))) return
                    ensureOpen(file, event.document)
                }
            },
            this,
        )
        ApplicationManager.getApplication().executeOnPooledThread(::start)
    }

    fun snapshot(file: VirtualFile, document: Document): LspSnapshot {
        ensureOpen(file, document)
        val uri = uri(file)
        val version = opened[uri]?.version
        val published = diagnostics[uri]?.takeIf { it.version == null || it.version == version }
        return LspSnapshot(
            diagnostics = published?.diagnostics.orEmpty(),
            actions = actions[uri]?.takeIf { it.version == version }?.actions.orEmpty(),
            version = version,
        )
    }

    fun tokensAtLine(file: VirtualFile, document: Document, line: Int): List<LspSemanticToken> {
        ensureOpen(file, document)
        val version = opened[uri(file)]?.version
        return semanticTokens[uri(file)]?.takeIf { it.version == version }?.byLine?.get(line).orEmpty()
    }

    fun complete(file: VirtualFile, document: Document, offset: Int): List<CompletionItem> {
        val result = request(file, document) { server, uri ->
            server.textDocumentService.completion(
                CompletionParams(TextDocumentIdentifier(uri), position(document, offset)),
            )
        } ?: return emptyList()
        return if (result.isLeft) result.left.orEmpty() else result.right.items.orEmpty()
    }

    fun hover(file: VirtualFile, document: Document, offset: Int): Hover? =
        request(file, document) { server, uri ->
            server.textDocumentService.hover(HoverParams(TextDocumentIdentifier(uri), position(document, offset)))
        }

    fun definition(file: VirtualFile, document: Document, offset: Int): List<LspLocation> {
        val result = request(file, document) { server, uri ->
            server.textDocumentService.definition(
                DefinitionParams(TextDocumentIdentifier(uri), position(document, offset)),
            )
        } ?: return emptyList()
        return if (result.isLeft) {
            result.left.map { LspLocation(it.uri, it.range) }
        } else {
            result.right.map { LspLocation(it.targetUri, it.targetSelectionRange ?: it.targetRange) }
        }
    }

    private fun <T> request(
        file: VirtualFile,
        document: Document,
        send: (LanguageServer, String) -> CompletableFuture<T>,
    ): T? {
        if (!isAzora(file)) return null
        val server = serverWithin(REQUEST_TIMEOUT_MS) ?: return null
        val stamp = document.modificationStamp
        val response = synchronized(opened) {
            if (remote !== server) return null
            val uri = uri(file)
            synchronizeDocument(server, uri, document)
            send(server, uri)
        }
        return await(response, REQUEST_TIMEOUT_MS)?.takeIf { document.modificationStamp == stamp }
    }

    fun apply(action: CodeAction) {
        val server = serverWithin(REQUEST_TIMEOUT_MS) ?: return
        val resolved = if (action.edit == null && action.data != null) {
            runCatching {
                server.textDocumentService.resolveCodeAction(action).get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
            }.getOrDefault(action)
        } else action
        resolved.edit?.let(::applyWorkspaceEdit)
    }

    fun close(file: VirtualFile) = synchronized(opened) {
        val state = opened.remove(uri(file)) ?: return@synchronized
        diagnostics.remove(state.uri)
        actions.remove(state.uri)
        semanticTokens.remove(state.uri)
        remote?.textDocumentService?.didClose(
            DidCloseTextDocumentParams(TextDocumentIdentifier(state.uri)),
        )
        Unit
    }

    private fun start() {
        if (disposed.get() || remote != null) return
        val connectionReady = ready
        val jar = locateServer() ?: run {
            connectionReady.completeExceptionally(IllegalStateException("AZLS is missing from the plugin"))
            log.warn("AZLS is missing from the plugin")
            return
        }
        try {
            val java = File(System.getProperty("java.home"), "bin/java").absolutePath
            val started = ProcessBuilder(java, "-jar", jar.absolutePath, "--stdio")
                .directory(project.basePath?.let(::File))
                .redirectErrorStream(false)
                .start()
            process = started
            drainStderr(started)
            val created = LSPLauncher.Builder<LanguageServer>()
                .setLocalService(Client())
                .setRemoteInterface(LanguageServer::class.java)
                .setInput(started.inputStream)
                .setOutput(started.outputStream)
                .configureGson { AzoraLspCodec.configure(it) }
                .create()
            val server = created.remoteProxy
            val listening = created.startListening()
            val rootUri = project.basePath?.let { File(it).toPath().toUri().toString() }
            val params = InitializeParams().apply {
                processId = ProcessHandle.current().pid().toInt()
                clientInfo = ClientInfo("Azora IntelliJ Plugin", PLUGIN_PROTOCOL_VERSION)
                this.rootUri = rootUri
                capabilities = ClientCapabilities().apply {
                    general = GeneralClientCapabilities().apply {
                        positionEncodings = listOf("utf-16")
                    }
                }
                workspaceFolders = buildList {
                    rootUri?.let { add(WorkspaceFolder(it, project.name)) }
                    jar.parentFile.resolve("std").takeIf(File::isDirectory)?.let {
                        add(WorkspaceFolder(it.toPath().toUri().toString(), "Azora standard library"))
                    }
                }
            }
            server.initialize(params).thenAccept {
                if (disposed.get()) return@thenAccept
                server.initialized(InitializedParams())
                synchronized(opened) {
                    if (disposed.get() || ready !== connectionReady) return@thenAccept
                    remote = server
                    connectionReady.complete(server)
                }
            }.exceptionally { error ->
                failStartup(error, connectionReady)
                started.destroy()
                null
            }
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    listening.get()
                } catch (error: Throwable) {
                    if (!disposed.get()) log.warn("AZLS connection stopped", error)
                } finally {
                    synchronized(opened) {
                        remote = null
                        opened.clear()
                        connectionReady.completeExceptionally(IllegalStateException("AZLS connection closed"))
                        ready = CompletableFuture()
                    }
                    if (!disposed.get() && restarts.incrementAndGet() <= MAX_RESTARTS) {
                        AppExecutorUtil.getAppScheduledExecutorService().schedule(::start, 1, TimeUnit.SECONDS)
                    }
                }
            }
        } catch (error: Throwable) {
            failStartup(error, connectionReady)
        }
    }

    private fun failStartup(error: Throwable, connectionReady: CompletableFuture<LanguageServer>) {
        log.warn("Failed to start AZLS", error)
        if (!connectionReady.isDone) connectionReady.completeExceptionally(error)
    }

    private fun ensureOpen(file: VirtualFile, document: Document) {
        if (!isAzora(file) || disposed.get()) return
        synchronized(opened) {
            remote?.let { synchronizeDocument(it, uri(file), document) }
        }
    }

    /** Called under the same lock as request dispatch so didOpen/didChange arrive first. */
    private fun synchronizeDocument(server: LanguageServer, uri: String, document: Document) {
        val stamp = document.modificationStamp
        val current = opened[uri]
        if (current?.stamp == stamp && current.document === document) return

        val version = (current?.version ?: 0) + 1
        val state = OpenDocument(uri, document, version, stamp)
        opened[uri] = state
        diagnostics.remove(uri)
        actions.remove(uri)
        semanticTokens.remove(uri)
        if (current == null) {
            server.textDocumentService.didOpen(
                DidOpenTextDocumentParams(TextDocumentItem(uri, "azora", version, document.text)),
            )
        } else {
            server.textDocumentService.didChange(
                DidChangeTextDocumentParams(
                    VersionedTextDocumentIdentifier(uri, version),
                    listOf(TextDocumentContentChangeEvent(document.text)),
                ),
            )
        }
        AppExecutorUtil.getAppScheduledExecutorService().schedule({
            if (opened[uri] === state) requestSemanticTokens(state)
        }, 120, TimeUnit.MILLISECONDS)
    }

    private fun requestSemanticTokens(state: OpenDocument) {
        remote?.textDocumentService?.semanticTokensFull(
            SemanticTokensParams(TextDocumentIdentifier(state.uri)),
        )?.thenAccept { response ->
            if (opened[state.uri] !== state) return@thenAccept
            semanticTokens[state.uri] = TokenSnapshot(
                state.version,
                AzoraLspCodec.decodeSemanticTokens(response?.data.orEmpty()).groupBy(LspSemanticToken::line),
            )
            restartDaemon(state.uri)
        }
    }

    private fun requestCodeActions(params: PublishDiagnosticsParams) {
        val server = remote ?: return
        val expectedVersion = opened[params.uri]?.version ?: return
        if (params.version != null && params.version != expectedVersion) return
        val range = params.diagnostics.fold(null as Range?) { combined, diagnostic ->
            if (combined == null) diagnostic.range else Range(
                minPosition(combined.start, diagnostic.range.start),
                maxPosition(combined.end, diagnostic.range.end),
            )
        } ?: Range(Position(0, 0), Position(0, 0))
        server.textDocumentService.codeAction(
            CodeActionParams(
                TextDocumentIdentifier(params.uri),
                range,
                CodeActionContext(params.diagnostics),
            ),
        ).thenAccept { returned ->
            if (opened[params.uri]?.version != expectedVersion) return@thenAccept
            actions[params.uri] = ActionSnapshot(
                expectedVersion,
                returned.orEmpty().mapNotNull { if (it.isRight) it.right else null },
            )
            restartDaemon(params.uri)
        }
    }

    private fun applyWorkspaceEdit(edit: WorkspaceEdit) {
        ApplicationManager.getApplication().invokeLater {
            val changes = mutableMapOf<String, MutableList<org.eclipse.lsp4j.TextEdit>>()
            edit.changes.orEmpty().forEach { (uri, edits) -> changes.getOrPut(uri) { mutableListOf() }.addAll(edits) }
            edit.documentChanges.orEmpty().forEach { change ->
                if (!change.isLeft) return@forEach
                val documentEdit = change.left
                val uri = documentEdit.textDocument.uri
                val expected = documentEdit.textDocument.version
                val current = opened[uri]?.version
                if (expected != null && current != expected) return@invokeLater
                changes.getOrPut(uri) { mutableListOf() }.addAll(documentEdit.edits)
            }
            WriteCommandAction.runWriteCommandAction(project, "Apply Azora fix", null, Runnable {
                changes.forEach { (uri, edits) ->
                    val file = AzoraLspCodec.findFile(uri) ?: return@forEach
                    val document = FileDocumentManager.getInstance().getDocument(file) ?: return@forEach
                    edits.sortedByDescending { offset(document, it.range.start) }.forEach { textEdit ->
                        val start = offset(document, textEdit.range.start)
                        val end = offset(document, textEdit.range.end)
                        if (start in 0..end && end <= document.textLength) {
                            document.replaceString(start, end, textEdit.newText)
                        }
                    }
                }
            })
        }
    }

    private fun serverWithin(timeoutMs: Long): LanguageServer? =
        remote ?: await(ready, timeoutMs, cancelOnTimeout = false)

    private fun <T> await(future: CompletableFuture<T>, timeoutMs: Long, cancelOnTimeout: Boolean = true): T? {
        val deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(timeoutMs)
        try {
            while (true) {
                ProgressManager.checkCanceled()
                val remaining = deadline - System.nanoTime()
                if (remaining <= 0) return null
                try {
                    return future.get(minOf(remaining, TimeUnit.MILLISECONDS.toNanos(25)), TimeUnit.NANOSECONDS)
                } catch (_: TimeoutException) {
                    // Give the IntelliJ progress indicator a chance to cancel stale requests.
                }
            }
        } catch (_: java.util.concurrent.ExecutionException) {
            return null
        } catch (_: InterruptedException) {
            Thread.currentThread().interrupt()
            return null
        } finally {
            if (cancelOnTimeout && !future.isDone) future.cancel(true)
        }
    }

    private fun position(document: Document, offset: Int): Position {
        val safe = offset.coerceIn(0, document.textLength)
        val line = document.getLineNumber(safe)
        return Position(line, safe - document.getLineStartOffset(line))
    }

    private fun offset(document: Document, position: Position): Int {
        if (document.lineCount == 0) return 0
        val line = position.line.coerceIn(0, document.lineCount - 1)
        return (document.getLineStartOffset(line) + position.character)
            .coerceAtMost(document.getLineEndOffset(line))
    }

    private fun restartDaemon(uri: String) {
        ApplicationManager.getApplication().invokeLater {
            if (project.isDisposed) return@invokeLater
            val file = AzoraLspCodec.findFile(uri) ?: return@invokeLater
            val psi = PsiManager.getInstance(project).findFile(file) ?: return@invokeLater
            DaemonCodeAnalyzer.getInstance(project).restart(psi)
        }
    }

    private fun drainStderr(started: Process) {
        ApplicationManager.getApplication().executeOnPooledThread {
            BufferedReader(InputStreamReader(started.errorStream)).useLines { lines ->
                lines.forEach { log.warn("AZLS: $it") }
            }
        }
    }

    private fun locateServer(): File? = sequenceOf(
        System.getProperty("azora.azls.jar"),
        System.getenv("AZORA_AZLS_JAR"),
        PluginManagerCore.getPlugin(PluginId.getId("org.azora.lang.idea"))
            ?.pluginPath?.resolve("azls/azls-all.jar")?.toString(),
        File(System.getProperty("user.home"), ".azora/azls/azls.jar").path,
    ).filterNotNull().map(::File).firstOrNull(File::isFile)

    private fun uri(file: VirtualFile): String =
        if (file.fileSystem.protocol == "file") file.toNioPath().toUri().toString() else file.url

    private fun isAzora(file: VirtualFile): Boolean = file.extension.equals("az", ignoreCase = true)

    override fun dispose() {
        if (!disposed.compareAndSet(false, true)) return
        runCatching { remote?.shutdown()?.get(750, TimeUnit.MILLISECONDS) }
        runCatching { remote?.exit() }
        process?.destroy()
        opened.clear()
        diagnostics.clear()
        actions.clear()
        semanticTokens.clear()
    }

    private inner class Client : LanguageClient {
        override fun telemetryEvent(`object`: Any?) = Unit

        override fun publishDiagnostics(params: PublishDiagnosticsParams) {
            val current = opened[params.uri]?.version ?: return
            if (params.version != null && current != params.version) return
            diagnostics[params.uri] = PublishedSnapshot(params.version, params.diagnostics.orEmpty())
            actions.remove(params.uri)
            requestCodeActions(params)
            restartDaemon(params.uri)
        }

        override fun showMessage(messageParams: MessageParams) {
            log.info("AZLS: ${messageParams.message}")
        }

        override fun showMessageRequest(requestParams: ShowMessageRequestParams): CompletableFuture<MessageActionItem> =
            CompletableFuture.completedFuture(null)

        override fun logMessage(message: MessageParams) {
            log.info("AZLS: ${message.message}")
        }

        override fun applyEdit(params: ApplyWorkspaceEditParams): CompletableFuture<ApplyWorkspaceEditResponse> {
            applyWorkspaceEdit(params.edit)
            return CompletableFuture.completedFuture(ApplyWorkspaceEditResponse(true))
        }
    }

    private data class OpenDocument(val uri: String, val document: Document, val version: Int, val stamp: Long)
    private data class PublishedSnapshot(val version: Int?, val diagnostics: List<Diagnostic>)
    private data class ActionSnapshot(val version: Int, val actions: List<CodeAction>)
    private data class TokenSnapshot(val version: Int, val byLine: Map<Int, List<LspSemanticToken>>)

    companion object {
        private const val REQUEST_TIMEOUT_MS = 900L
        private const val MAX_RESTARTS = 3
        private const val PLUGIN_PROTOCOL_VERSION = "1"
        private fun minPosition(a: Position, b: Position): Position = if (a.line < b.line || a.line == b.line && a.character <= b.character) a else b
        private fun maxPosition(a: Position, b: Position): Position = if (a.line > b.line || a.line == b.line && a.character >= b.character) a else b
    }
}

data class LspSnapshot(
    val diagnostics: List<Diagnostic>,
    val actions: List<CodeAction>,
    val version: Int?,
)

data class LspSemanticToken(val line: Int, val character: Int, val length: Int, val type: String)
data class LspLocation(val uri: String, val range: Range)

/** Pure LSP delta decoding kept separate from editor state for regression tests. */
internal object AzoraLspCodec {
    fun configure(gson: GsonBuilder) {
        // LSP allows a singleton Location as well as Location[] or LocationLink[].
        // The LSP4J 0.21 proxy only declares the array forms.
        val resultType = (TextDocumentService::class.java
            .getMethod("definition", DefinitionParams::class.java).genericReturnType as ParameterizedType)
            .actualTypeArguments.single()
        gson.registerTypeAdapter(resultType, JsonDeserializer<Either<List<Location>, List<LocationLink>>> { json, _, context ->
            when {
                json.isJsonObject -> Either.forLeft(listOf(context.deserialize(json, Location::class.java)))
                json.isJsonArray && json.asJsonArray.firstOrNull()?.asJsonObject?.has("targetUri") == true ->
                    Either.forRight(context.deserialize<Array<LocationLink>>(json, Array<LocationLink>::class.java).toList())
                json.isJsonArray ->
                    Either.forLeft(context.deserialize<Array<Location>>(json, Array<Location>::class.java).toList())
                else -> Either.forLeft(emptyList())
            }
        })
    }

    fun findFile(uri: String): VirtualFile? {
        VirtualFileManager.getInstance().findFileByUrl(uri)?.let { return it }
        val path = runCatching { java.nio.file.Path.of(java.net.URI(uri)) }.getOrNull() ?: return null
        return com.intellij.openapi.vfs.LocalFileSystem.getInstance().findFileByNioFile(path)
    }

    fun offset(document: Document, position: Position): Int? {
        if (position.line !in 0 until document.lineCount || position.character < 0) return null
        val start = document.getLineStartOffset(position.line)
        val length = document.getLineEndOffset(position.line) - start
        return (start + position.character).takeIf { position.character <= length }
    }

    private val tokenTypes = listOf(
        "keyword", "string", "number", "comment", "function", "type", "parameter", "variable",
        "decorator", "macro", "associatedType", "generic", "contextParameter", "property", "enumMember",
        "errorMember", "label", "scope", "modulePath", "doc", "docTag", "docTagValue", "interpolation",
        "field", "typeDeclaration", "specType", "functionDeclaration", "specFunction", "overrideFunction",
        "propertyDeclaration", "specProperty", "overrideProperty", "wildcard", "macroHole", "smartCast", "deprecated",
    )

    fun decodeSemanticTokens(data: List<Int>): List<LspSemanticToken> {
        val result = mutableListOf<LspSemanticToken>()
        var line = 0
        var start = 0
        var index = 0
        while (index + 4 < data.size) {
            val deltaLine = data[index]
            val deltaStart = data[index + 1]
            line += deltaLine
            start = if (deltaLine == 0) start + deltaStart else deltaStart
            val type = tokenTypes.getOrNull(data[index + 3]) ?: "variable"
            result += LspSemanticToken(line, start, data[index + 2], type)
            index += 5
        }
        return result
    }
}
