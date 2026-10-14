package com.ai.assistance.operit.core.codex

import android.content.Context
import android.os.Build
import java.io.File
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import org.json.JSONArray
import org.json.JSONObject

/** One private official app-server process per AIService; never shares credentials or threads. */
class CodexCoreManager(private val context: Context) {
    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private val lifecycle = Mutex()
    private val ids = AtomicLong(1)
    private val pending = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()
    private val notifications = ConcurrentHashMap<String, Channel<JSONObject>>()
    @Volatile private var process: Process? = null
    private var writer: java.io.BufferedWriter? = null
    private var currentKey: String? = null
    private var currentEndpoint: String? = null
    private val engineDir = File(context.filesDir, "codex-engine")

    suspend fun ensureStarted(endpoint: String, key: String, model: String, headers: Map<String, String>) =
        lifecycle.withLock {
            val baseUrl = normalizeEndpoint(endpoint)
            require(model.isNotBlank()) { "Codex requires a configured model" }
            if (process?.isAlive == true && currentKey == key && currentEndpoint == baseUrl) return@withLock
            stop()
            withContext(Dispatchers.IO) {
                require(Build.SUPPORTED_ABIS.any { it == "arm64-v8a" }) { "Bundled Codex requires ARM64" }
                val binDir = File(context.filesDir, "usr/bin")
                val proot = File(binDir, "proot")
                val rootfs = File(context.filesDir, "usr/var/lib/proot-distro/installed-rootfs/ubuntu")
                require(proot.canExecute() && File(rootfs, "usr/bin/env").isFile) {
                    "Initialize the Operit Ubuntu terminal before using Codex API"
                }
                engineDir.mkdirs()
                val binary = File(engineDir, "codex-app-server")
                // CI supplies the pinned upstream executable. Never downloads code at chat time.
                context.assets.open("codex/codex-app-server").use { input ->
                    val temp = File(engineDir, "codex-app-server.tmp")
                    temp.outputStream().use { input.copyTo(it) }
                    check(temp.renameTo(binary)) { "Cannot install bundled Codex" }
                }
                check(binary.setExecutable(true, true)) { "Cannot make Codex executable" }
                val home = File(engineDir, "home").apply { mkdirs() }
                File(engineDir, "workspace").mkdirs()
                // API keys are ONLY in child environment, not argv, config.toml or logs.
                home.resolve("config.toml").writeText("""
                    model = ${JSONObject.quote(model)}
                    model_provider = "operit"
                    approval_policy = "on-request"
                    sandbox_mode = "read-only"
                    [model_providers.operit]
                    name = "Operit API"
                    base_url = ${JSONObject.quote(baseUrl)}
                    wire_api = "responses"
                    env_key = "OPERIT_CODEX_API_KEY"
                    requires_openai_auth = false
                """.trimIndent() + "\n")
                val args = mutableListOf(
                    proot.absolutePath, "-0", "-r", rootfs.absolutePath,
                    "-b", "/dev", "-b", "/proc", "-b", "/sys",
                    "-b", "${engineDir.absolutePath}:/operit-codex",
                    "-b", "/storage/emulated/0:/sdcard",
                    "-w", "/operit-codex/workspace", "/usr/bin/env",
                    "PATH=/usr/local/sbin:/usr/local/bin:/usr/sbin:/usr/bin:/sbin:/bin",
                    "HOME=/root", "CODEX_HOME=/operit-codex/home",
                    "/operit-codex/codex-app-server", "--listen", "stdio://"
                )
                val pb = ProcessBuilder(args).directory(context.filesDir).redirectErrorStream(false)
                val env = pb.environment()
                env.remove("LD_PRELOAD")
                env["PATH"] = "${binDir.absolutePath}:/system/bin"
                env["PROOT_LOADER"] = File(binDir, "loader").absolutePath
                env["PROOT_TMP_DIR"] = File(context.filesDir, "tmp").apply { mkdirs() }.absolutePath
                env["TMPDIR"] = env.getValue("PROOT_TMP_DIR")
                env["OPERIT_CODEX_API_KEY"] = key
                // Custom headers also use env indirection; never persist their secret values.
                if (headers.isNotEmpty()) {
                    home.resolve("config.toml").appendText("[model_providers.operit.env_http_headers]\n")
                    headers.entries.forEachIndexed { index, entry ->
                        val name = "OPERIT_CODEX_HEADER_$index"
                        env[name] = entry.value
                        home.resolve("config.toml").appendText("${JSONObject.quote(entry.key)} = ${JSONObject.quote(name)}\n")
                    }
                }
                val proc = pb.start()
                process = proc
                writer = proc.outputStream.bufferedWriter(Charsets.UTF_8)
                currentKey = key
                currentEndpoint = baseUrl
                scope.launch { readLoop(proc) }
                // Drain stderr without logging arbitrary provider output or credentials.
                scope.launch { proc.errorStream.use { stream -> val buffer = ByteArray(4096); while (stream.read(buffer) >= 0) { } } }
            }
            try {
                request("initialize", JSONObject().put("clientInfo", JSONObject()
                    .put("name", "operit-codex-api").put("version", "1.0"))
                    .put("capabilities", JSONObject().put("experimentalApi", false)))
                write(JSONObject().put("method", "initialized"))
            } catch (e: Exception) { stop(); throw e }
        }

    suspend fun request(method: String, params: JSONObject): JSONObject {
        val id = ids.getAndIncrement()
        val reply = CompletableDeferred<JSONObject>()
        pending[id] = reply
        try {
            write(JSONObject().put("id", id).put("method", method).put("params", params))
            return withTimeout(60_000) { reply.await() }
        } finally { pending.remove(id) }
    }

    private fun write(message: JSONObject) {
        val out = writer ?: error("Codex app-server is not running")
        synchronized(out) { out.write(message.toString()); out.newLine(); out.flush() }
    }

    suspend fun startThread(instructions: String, cwd: String? = null): String {
        // No globally hard-coded active chat ID. Caller creates an isolated thread per request.
        val result = request("thread/start", JSONObject()
            .put("modelProvider", "operit").put("cwd", cwd ?: "/operit-codex/workspace")
            .put("developerInstructions", instructions).put("ephemeral", true)
            .put("sandbox", "read-only").put("approvalPolicy", "on-request"))
        return result.getJSONObject("thread").getString("id")
    }

    fun subscribe(threadId: String): Channel<JSONObject> = Channel<JSONObject>(Channel.UNLIMITED).also {
        check(notifications.putIfAbsent(threadId, it) == null)
    }
    fun unsubscribe(threadId: String) { notifications.remove(threadId)?.close() }

    suspend fun startTurn(threadId: String, text: String): String = request("turn/start", JSONObject()
        .put("threadId", threadId).put("input", JSONArray().put(JSONObject()
            .put("type", "text").put("text", text).put("textElements", JSONArray()))))
        .getJSONObject("turn").getString("id")

    suspend fun interrupt(threadId: String, turnId: String) {
        request("turn/interrupt", JSONObject().put("threadId", threadId).put("turnId", turnId))
    }

    private suspend fun readLoop(proc: Process) {
        try {
            proc.inputStream.bufferedReader(Charsets.UTF_8).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val message = JSONObject(line)
                    if (message.has("method") && message.has("id")) {
                        // Explicit denial until Android native approval UI is implemented.
                        val method = message.getString("method")
                        val response = JSONObject().put("id", message.get("id"))
                        if (method.endsWith("requestApproval")) {
                            response.put("result", JSONObject().put("decision", "decline"))
                        } else response.put("error", JSONObject().put("code", -32601)
                            .put("message", "Operit has not enabled this server request"))
                        write(response)
                    } else if (message.has("id")) {
                        val reply = pending.remove(message.getLong("id")) ?: continue
                        if (message.has("error")) reply.completeExceptionally(IllegalStateException("Codex RPC failed: ${message.getJSONObject("error").optInt("code")}"))
                        else reply.complete(message.optJSONObject("result") ?: JSONObject())
                    } else {
                        val params = message.optJSONObject("params") ?: continue
                        notifications[params.optString("threadId")]?.send(message)
                    }
                }
            }
        } catch (e: Exception) {
            if (process === proc) failPending(IllegalStateException("Codex protocol connection closed", e))
        } finally {
            if (process === proc) { process = null; writer = null; failPending(IllegalStateException("Codex app-server exited")) }
        }
    }

    private fun failPending(error: Exception) {
        pending.values.forEach { it.completeExceptionally(error) }; pending.clear()
        notifications.values.forEach { it.close(error) }; notifications.clear()
    }
    fun stop() {
        val proc = process; process = null; writer = null; currentKey = null; currentEndpoint = null
        runCatching { proc?.outputStream?.close() }; proc?.destroy()
        failPending(IllegalStateException("Codex stopped"))
    }
    fun release() { stop(); scope.cancel() }

    companion object {
        fun normalizeEndpoint(endpoint: String): String {
            val raw = endpoint.trim().trimEnd('/')
            val uri = java.net.URI(raw)
            require(uri.scheme in setOf("http", "https") && !uri.host.isNullOrBlank() && uri.userInfo == null && uri.query == null && uri.fragment == null) { "Invalid Codex API endpoint" }
            require(!uri.path.endsWith("/chat/completions")) { "Official Codex requires Responses API, not chat/completions" }
            return raw.removeSuffix("/responses")
        }
    }
}
