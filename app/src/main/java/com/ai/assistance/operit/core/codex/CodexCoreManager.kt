package com.ai.assistance.operit.core.codex

import android.content.Context
import android.os.Environment
import com.ai.assistance.operit.util.AppLogger
import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.File
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.nio.charset.StandardCharsets
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicLong
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import org.json.JSONArray
import org.json.JSONObject

/**
 * Operit 内置的 Codex Core 进程管理器与客户端协议层。
 * 负责通过标准输入输出 (stdio) 与 Codex App-Server 进行 JSON-RPC 2.0 异步双向通信。
 */
class CodexCoreManager private constructor(private val context: Context) {

    companion object {
        private const val TAG = "CodexCoreManager"

        @Volatile
        private var instance: CodexCoreManager? = null

        fun getInstance(context: Context): CodexCoreManager {
            return instance ?: synchronized(this) {
                instance ?: CodexCoreManager(context.applicationContext).also { instance = it }
            }
        }
    }

    private val scope = CoroutineScope(Dispatchers.IO + Job())
    private val requestCounter = AtomicLong(1)
    private val pendingRequests = ConcurrentHashMap<Long, CompletableDeferred<JSONObject>>()

    private val _events = MutableSharedFlow<CodexEvent>(extraBufferCapacity = 64)
    val events: SharedFlow<CodexEvent> = _events.asSharedFlow()

    private var process: Process? = null
    private var stdinWriter: BufferedWriter? = null
    private var isRunning = false
    private var binaryPath: String = "codex"
    private var workspaceDir: File = File(Environment.getExternalStorageDirectory(), "Operit/workspace")

    data class CodexEvent(
        val method: String,
        val threadId: String,
        val turnId: String? = null,
        val delta: String? = null,
        val command: String? = null,
        val output: String? = null,
        val status: String? = null,
        val rawParams: JSONObject? = null
    )

    init {
        resolveEnvironment()
    }

    private fun resolveEnvironment() {
        val sdcard = Environment.getExternalStorageDirectory()
        workspaceDir = if (sdcard != null && sdcard.exists()) {
            File(sdcard, "Operit/workspace")
        } else {
            File(context.filesDir, "workspace")
        }
        if (!workspaceDir.exists()) {
            workspaceDir.mkdirs()
        }

        // 探测 Codex 核心二进制位置
        val appNativeLib = File(context.applicationInfo.nativeLibraryDir, "libcodex.so")
        val systemBin = File("/usr/local/bin/codex")
        val prootBin = File("/data/user/0/com.ai.assistance.operit/files/usr/var/lib/proot-distro/installed-rootfs/ubuntu/usr/local/bin/codex")

        binaryPath = when {
            appNativeLib.exists() && appNativeLib.canExecute() -> appNativeLib.absolutePath
            systemBin.exists() && systemBin.canExecute() -> systemBin.absolutePath
            prootBin.exists() && prootBin.canExecute() -> prootBin.absolutePath
            else -> "codex"
        }
    }

    @Synchronized
    suspend fun ensureStarted() {
        if (isRunning && process != null) return

        try {
            AppLogger.i(TAG, "Starting Codex Core daemon from: $binaryPath, workspace: ${workspaceDir.absolutePath}")
            val pb = ProcessBuilder(binaryPath, "app-server", "--transport", "stdio")
            pb.directory(workspaceDir)
            pb.redirectErrorStream(false)

            val proc = pb.start()
            process = proc
            stdinWriter = BufferedWriter(OutputStreamWriter(proc.outputStream, StandardCharsets.UTF_8))
            isRunning = true

            // 启动标准输出监听协程
            scope.launch { readStdoutLoop(proc) }
            scope.launch { readStderrLoop(proc) }

            // 发起协议握手
            initializeHandshake()
        } catch (e: Exception) {
            AppLogger.w(TAG, "Native Codex Core process spawn failed (${e.message}), fallback to embedded agent simulator.")
            isRunning = false
        }
    }

    private suspend fun initializeHandshake() {
        val clientInfo = JSONObject().apply {
            put("name", "operit-native-codex")
            put("title", "Operit Android Builtin Codex")
            put("version", "1.0.0")
        }
        val capabilities = JSONObject().apply {
            put("experimentalApi", true)
        }
        val params = JSONObject().apply {
            put("clientInfo", clientInfo)
            put("capabilities", capabilities)
        }
        sendRequest("initialize", params)
    }

    suspend fun sendRequest(method: String, params: JSONObject?): JSONObject {
        val writer = stdinWriter
        if (!isRunning || writer == null) {
            throw IllegalStateException("Codex App-Server is not running")
        }

        val id = requestCounter.getAndIncrement()
        val deferred = CompletableDeferred<JSONObject>()
        pendingRequests[id] = deferred

        val req = JSONObject().apply {
            put("id", id)
            put("method", method)
            if (params != null) {
                put("params", params)
            }
        }

        val wire = req.toString() + "\n"
        synchronized(writer) {
            writer.write(wire)
            writer.flush()
        }

        return deferred.await()
    }

    suspend fun startThread(cwd: String? = null, model: String? = null): String {
        ensureStarted()
        val params = JSONObject().apply {
            put("cwd", cwd ?: workspaceDir.absolutePath)
            put("ephemeral", false)
            if (!model.isNullOrBlank()) {
                put("model", model)
            }
        }
        val res = sendRequest("thread/start", params)
        val threadObj = res.optJSONObject("thread")
        return threadObj?.optString("id") ?: res.optString("id", "thr_default")
    }

    suspend fun startTurn(threadId: String, userText: String, model: String? = null): String {
        ensureStarted()
        val inputItem = JSONObject().apply {
            put("type", "text")
            put("text", userText)
            put("textElements", JSONArray())
        }
        val inputArray = JSONArray().apply { put(inputItem) }
        val params = JSONObject().apply {
            put("threadId", threadId)
            put("input", inputArray)
            if (!model.isNullOrBlank()) {
                put("model", model)
            }
        }
        val res = sendRequest("turn/start", params)
        val turnObj = res.optJSONObject("turn")
        return turnObj?.optString("id") ?: res.optString("id", "turn_default")
    }

    suspend fun interruptTurn(threadId: String, turnId: String) {
        if (!isRunning) return
        val params = JSONObject().apply {
            put("threadId", threadId)
            put("turnId", turnId)
        }
        sendRequest("turn/interrupt", params)
    }

    suspend fun listModels(): List<String> {
        val defaultModels = listOf("o3-mini", "gpt-4o", "gpt-4o-mini", "claude-3-5-sonnet", "deepseek-reasoner")
        if (!isRunning) return defaultModels

        return try {
            val res = sendRequest("model/list", JSONObject().apply { put("includeHidden", false) })
            val list = mutableListOf<String>()
            val dataArray = res.optJSONArray("data") ?: res.optJSONArray("models")
            if (dataArray != null) {
                for (i in 0 until dataArray.length()) {
                    val m = dataArray.getJSONObject(i)
                    val id = m.optString("id").ifEmpty { m.optString("name") }
                    if (id.isNotEmpty()) list.add(id)
                }
            }
            if (list.isNotEmpty()) list else defaultModels
        } catch (e: Exception) {
            defaultModels
        }
    }

    private suspend fun readStdoutLoop(proc: Process) {
        val reader = BufferedReader(InputStreamReader(proc.inputStream, StandardCharsets.UTF_8))
        while (scope.isActive && isRunning) {
            val line = reader.readLine() ?: break
            val trimmed = line.trim()
            if (trimmed.isEmpty()) continue

            try {
                val json = JSONObject(trimmed)
                if (json.has("id")) {
                    val id = json.getLong("id")
                    val deferred = pendingRequests.remove(id)
                    if (deferred != null) {
                        if (json.has("error")) {
                            val err = json.getJSONObject("error")
                            deferred.completeExceptionally(RuntimeException(err.optString("message", "Codex error")))
                        } else {
                            deferred.complete(json.optJSONObject("result") ?: JSONObject())
                        }
                    }
                } else if (json.has("method")) {
                    dispatchNotification(json.getString("method"), json.optJSONObject("params") ?: JSONObject())
                }
            } catch (e: Exception) {
                AppLogger.w(TAG, "Error parsing stdout JSON: $trimmed")
            }
        }
    }

    private suspend fun readStderrLoop(proc: Process) {
        val reader = BufferedReader(InputStreamReader(proc.errorStream, StandardCharsets.UTF_8))
        while (scope.isActive && isRunning) {
            val line = reader.readLine() ?: break
            AppLogger.d(TAG, "[Codex Core Stderr] $line")
        }
    }

    private suspend fun dispatchNotification(method: String, params: JSONObject) {
        val threadId = params.optString("threadId", "")
        val turnId = params.optString("turnId", "")

        val event = when (method) {
            "item/agentMessage/delta" -> {
                CodexEvent(
                    method = method,
                    threadId = threadId,
                    turnId = turnId,
                    delta = params.optString("delta", "")
                )
            }
            "item/reasoning/textDelta" -> {
                CodexEvent(
                    method = method,
                    threadId = threadId,
                    turnId = turnId,
                    delta = params.optString("delta", "")
                )
            }
            "item/started" -> {
                val item = params.optJSONObject("item")
                if (item?.optString("type") == "commandExecution") {
                    CodexEvent(
                        method = method,
                        threadId = threadId,
                        turnId = turnId,
                        command = item.optString("command"),
                        status = "started",
                        rawParams = item
                    )
                } else null
            }
            "item/commandExecution/outputDelta" -> {
                CodexEvent(
                    method = method,
                    threadId = threadId,
                    turnId = turnId,
                    output = params.optString("delta", "")
                )
            }
            "item/completed" -> {
                val item = params.optJSONObject("item")
                if (item?.optString("type") == "commandExecution") {
                    CodexEvent(
                        method = method,
                        threadId = threadId,
                        turnId = turnId,
                        command = item.optString("command"),
                        status = "completed",
                        rawParams = item
                    )
                } else null
            }
            "turn/completed", "turn/aborted" -> {
                CodexEvent(
                    method = method,
                    threadId = threadId,
                    turnId = turnId,
                    status = method.substringAfter('/')
                )
            }
            else -> null
        }

        if (event != null) {
            _events.emit(event)
        }
    }

    fun isCoreRunning(): Boolean = isRunning
}