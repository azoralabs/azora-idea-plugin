/*
 * Copyright 2026 AzoraLabs
 * Licensed under the Apache License, Version 2.0.
 */

package org.azora.lang.idea.lsp

import com.intellij.codeInsight.daemon.DaemonCodeAnalyzer
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
import com.intellij.openapi.util.Disposer
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
import org.eclipse.lsp4j.jsonrpc.Launcher
import org.eclipse.lsp4j.jsonrpc.messages.Either
import org.eclipse.lsp4j.launch.LSPLauncher
import org.eclipse.lsp4j.services.LanguageClient
import org.eclipse.lsp4j.services.LanguageServer
import java.io.BufferedReader
import java.io.File
import java.io.InputStreamReader
import java.net.URI
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
    @Volatile private var launcher: Launcher<LanguageServer>? = null
    @Volatile private var remote: LanguageServer? = null

    init {
        EditorFactory.getInstance().eventMulticaster.addDocumentListener(
            object : DocumentListener {
                override fun documentChanged(event: DocumentEvent) {
                    val file = FileDocumentManager.getInstance().getFile(event.document) ?: return
                    if (!isAzora(file) || !belongsToProject(file)) return
                    changed(file, event.document)
                }
            },
            this,
        )
        ApplicationManager.getApplication().executeOnPooledThread(::start)
    }

    fun snapshot(file: VirtualFile, document: Document): LspSnapshot {
        ensureOpen(file, document)
        val uri = file.url
        val version = opened[uri]?.version?.get()
        val published = diagnostics[uri]?.takeIf { it.version == null || it.version == version }
        return LspSnapshot(
            diagnostics = published?.diagnostics.orEmpty(),
            actions = actions[uri]?.takeIf { it.version == version }?.actions.orEmpty(),
            version = version,
        )
    }

    fun tokens(file: VirtualFile, document: Document): List<LspSemanticToken> {
        ensureOpen(file, document)
        val version = opened[file.url]?.version?.get()
        return semanticTokens[file.url]?.takeIf { it.version == version }?.tokens.orEmpty()
    }

    fun complete(file: VirtualFile, document: Document, offset: Int): List<CompletionItem> {
        val server = serverWithin(REQUEST_TIMEOUT_MS) ?: return emptyList()
        ensureOpen(file, document)
        val result = runCatching {
            server.textDocumentService.completion(
                CompletionParams(TextDocumentIdentifier(file.url), position(document, offset)),
            ).get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrNull() ?: return emptyList()
        return if (result.isLeft) result.left else result.right.items
    }

    fun hover(file: VirtualFile, document: Document, offset: Int): Hover? {
        val server = serverWithin(REQUEST_TIMEOUT_MS) ?: return null
        ensureOpen(file, document)
        return runCatching {
            server.textDocumentService.hover(
                HoverParams(TextDocumentIdentifier(file.url), position(document, offset)),
            ).get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrNull()
    }

    fun definition(file: VirtualFile, document: Document, offset: Int): List<LspLocation> {
        val server = serverWithin(REQUEST_TIMEOUT_MS) ?: return emptyList()
        ensureOpen(file, document)
        val result = runCatching {
            server.textDocumentService.definition(
                DefinitionParams(TextDocumentIdentifier(file.url), position(document, offset)),
            ).get(REQUEST_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        }.getOrNull() ?: return emptyList()
        return if (result.isLeft) {
            result.left.map { LspLocation(it.uri, it.range) }
        } else {
            result.right.map { LspLocation(it.targetUri, it.targetSelectionRange ?: it.targetRange) }
        }
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

    fun close(file: VirtualFile) {
        val state = opened.remove(file.url) ?: return
        diagnostics.remove(file.url)
        actions.remove(file.url)
        semanticTokens.remove(file.url)
        remote?.textDocumentService?.didClose(
            DidCloseTextDocumentParams(TextDocumentIdentifier(state.uri)),
        )
    }

    private fun start() {
        if (disposed.get() || remote != null) return
        val connectionReady = CompletableFuture<LanguageServer>()
        ready = connectionReady
        val jar = locateServer() ?: run {
            log.warn("AZLS was not found. Build/install it at ~/.azora/azls/azls.jar")
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
            val client = Client()
            val created = LSPLauncher.createClientLauncher(client, started.inputStream, started.outputStream)
            launcher = created
            val server = created.remoteProxy
            remote = server
            val listening = created.startListening()
            val rootUri = project.basePath?.let { File(it).toURI().toString() }
            val params = InitializeParams().apply {
                processId = ProcessHandle.current().pid().toInt()
                clientInfo = ClientInfo("Azora IntelliJ Plugin", PLUGIN_PROTOCOL_VERSION)
                this.rootUri = rootUri
                capabilities = ClientCapabilities().apply {
                    general = GeneralClientCapabilities().apply {
                        positionEncodings = listOf("utf-16")
                    }
                }
                workspaceFolders = rootUri?.let { listOf(WorkspaceFolder(it, project.name)) }
            }
            server.initialize(params).thenAccept {
                if (disposed.get()) return@thenAccept
                server.initialized(InitializedParams())
                connectionReady.complete(server)
            }.exceptionally { error ->
                failStartup(error, connectionReady)
                null
            }
            ApplicationManager.getApplication().executeOnPooledThread {
                try {
                    listening.get()
                } catch (error: Throwable) {
                    if (!disposed.get()) log.warn("AZLS connection stopped", error)
                } finally {
                    remote = null
                    opened.clear()
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
        if (!isAzora(file)) return
        val uri = file.url
        val connection = ready
        connection.thenAccept { server ->
            if (disposed.get()) return@thenAccept
            val created = OpenDocument(uri, document, AtomicInteger(1))
            if (opened.putIfAbsent(uri, created) == null) {
                diagnostics.remove(uri)
                actions.remove(uri)
                semanticTokens.remove(uri)
                server.textDocumentService.didOpen(
                    DidOpenTextDocumentParams(TextDocumentItem(uri, "azora", 1, document.text)),
                )
                requestSemanticTokens(created)
            }
        }
    }

    private fun changed(file: VirtualFile, document: Document) {
        ensureOpen(file, document)
        val connection = ready
        connection.thenAccept { server ->
            val state = opened[file.url] ?: return@thenAccept
            val version = state.version.incrementAndGet()
            diagnostics.remove(file.url)
            actions.remove(file.url)
            semanticTokens.remove(file.url)
            server.textDocumentService.didChange(
                DidChangeTextDocumentParams(
                    VersionedTextDocumentIdentifier(file.url, version),
                    listOf(TextDocumentContentChangeEvent(document.text)),
                ),
            )
            requestSemanticTokens(state)
        }
    }

    private fun requestSemanticTokens(state: OpenDocument) {
        val version = state.version.get()
        remote?.textDocumentService?.semanticTokensFull(
            SemanticTokensParams(TextDocumentIdentifier(state.uri)),
        )?.thenAccept { response ->
            if (opened[state.uri]?.version?.get() != version) return@thenAccept
            semanticTokens[state.uri] = TokenSnapshot(version, AzoraLspCodec.decodeSemanticTokens(response?.data.orEmpty()))
            restartDaemon(state.uri)
        }
    }

    private fun requestCodeActions(params: PublishDiagnosticsParams) {
        val server = remote ?: return
        val expectedVersion = opened[params.uri]?.version?.get() ?: return
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
            if (opened[params.uri]?.version?.get() != expectedVersion) return@thenAccept
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
                val current = opened[uri]?.version?.get()
                if (expected != null && current != expected) return@invokeLater
                changes.getOrPut(uri) { mutableListOf() }.addAll(documentEdit.edits)
            }
            WriteCommandAction.runWriteCommandAction(project, "Apply Azora fix", null, Runnable {
                changes.forEach { (uri, edits) ->
                    val file = VirtualFileManager.getInstance().findFileByUrl(uri) ?: return@forEach
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

    private fun serverWithin(timeoutMs: Long): LanguageServer? = remote ?: runCatching {
        ready.get(timeoutMs, TimeUnit.MILLISECONDS)
    }.getOrNull()

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
            val file = VirtualFileManager.getInstance().findFileByUrl(uri) ?: return@invokeLater
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
        File(System.getProperty("user.home"), ".azora/azls/azls.jar").path,
        project.basePath?.let { File(it).resolve("azls.jar").path },
    ).filterNotNull().map(::File).firstOrNull(File::isFile)

    private fun belongsToProject(file: VirtualFile): Boolean {
        val base = project.basePath ?: return true
        return file.path == base || file.path.startsWith("$base/")
    }

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
            val current = opened[params.uri]?.version?.get()
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

    private data class OpenDocument(val uri: String, val document: Document, val version: AtomicInteger)
    private data class PublishedSnapshot(val version: Int?, val diagnostics: List<Diagnostic>)
    private data class ActionSnapshot(val version: Int, val actions: List<CodeAction>)
    private data class TokenSnapshot(val version: Int, val tokens: List<LspSemanticToken>)

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
