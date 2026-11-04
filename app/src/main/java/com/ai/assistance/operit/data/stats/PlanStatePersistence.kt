package com.ai.assistance.operit.data.stats

import android.content.Context
import android.util.AtomicFile
import com.ai.assistance.operit.data.model.PlanModelStage
import com.ai.assistance.operit.data.model.PlanStep
import com.ai.assistance.operit.data.model.PlanStepPriority
import com.ai.assistance.operit.data.model.PlanStepStatus
import java.io.File
import java.io.IOException
import java.util.Collections
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import org.json.JSONArray
import org.json.JSONObject

/** One chat's authoritative snapshot. Empty steps do not imply an absent phase. */
data class PlanStateSnapshot(val steps: List<PlanStep>, val stage: PlanModelStage?)

/** Failed commits never publish a new live plan. */
class PlanPersistenceException(message: String, cause: Throwable? = null) :
    IllegalStateException(message, cause)

internal interface PlanStateBackend {
    fun load(): String?
    fun save(document: String)
}

/** Fixed private filename: chat ids are JSON keys, never filesystem paths. */
internal class AtomicPlanStateBackend(context: Context) : PlanStateBackend {
    private val file = AtomicFile(File(context.filesDir, "plan-state-v1.json"))

    override fun load(): String? {
        // AtomicFile recovers its own interrupted transaction; there is no alternate source.
        if (!file.baseFile.exists() && !File(file.baseFile.path + ".bak").exists()) return null
        return file.openRead().use { it.readBytes().toString(Charsets.UTF_8) }
    }

    override fun save(document: String) {
        val bytes = document.toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            stream.flush()
            stream.fd.sync()
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
        file.finishWrite(stream)
        // AtomicFile may log rather than throw on rename failure. Confirm before publishing.
        if (!file.baseFile.readBytes().contentEquals(bytes)) {
            throw IOException("Atomic plan commit could not be confirmed")
        }
    }
}

internal object PlanStateCodec {
    fun encode(states: Map<String, PlanStateSnapshot>): String {
        val chats = JSONObject()
        states.forEach { (chatId, snapshot) ->
            require(chatId.isNotBlank()) { "A chat id is required" }
            chats.put(chatId, snapshotJson(snapshot))
        }
        return JSONObject().put("version", 1).put("chats", chats).toString()
    }

    fun snapshotJson(snapshot: PlanStateSnapshot): JSONObject {
        val steps = JSONArray()
        snapshot.steps.forEach { step ->
            require(step.content.isNotBlank()) { "Plan content must not be blank" }
            steps.put(JSONObject()
                .put("content", step.content)
                .put("status", PlanStepStatus.toWireValue(step.status))
                .put("priority", PlanStepPriority.toWireValue(step.priority)))
        }
        val phase = when (snapshot.stage) {
            null -> "chat"
            PlanModelStage.GENERATION -> "generation"
            PlanModelStage.IMPLEMENTATION -> "implementation"
            PlanModelStage.REVIEW -> "review"
        }
        return JSONObject().put("phase", phase)
            .put("steps", steps)
    }

    fun decode(document: String): Map<String, PlanStateSnapshot> {
        val root = JSONObject(document)
        require(root.get("version") == 1) { "Unsupported plan storage version" }
        val chats = root.getJSONObject("chats")
        val states = linkedMapOf<String, PlanStateSnapshot>()
        val keys = chats.keys()
        while (keys.hasNext()) {
            val chatId = keys.next()
            require(chatId.isNotBlank()) { "A chat id is required" }
            val value = chats.getJSONObject(chatId)
            val stage = when (val phase = value.get("phase")) {
                "chat" -> null
                "generation" -> PlanModelStage.GENERATION
                "implementation" -> PlanModelStage.IMPLEMENTATION
                "review" -> PlanModelStage.REVIEW
                else -> throw IllegalArgumentException("Invalid stored phase: $phase")
            }
            val entries = value.getJSONArray("steps")
            val steps = (0 until entries.length()).map { index ->
                val entry = entries.getJSONObject(index)
                val content = entry.get("content")
                require(content is String && content.isNotBlank()) { "Invalid stored content" }
                val status = when (entry.get("status")) {
                    "pending" -> PlanStepStatus.PENDING
                    "in_progress" -> PlanStepStatus.IN_PROGRESS
                    "completed" -> PlanStepStatus.COMPLETED
                    else -> throw IllegalArgumentException("Invalid stored status")
                }
                val priority = when (entry.get("priority")) {
                    "high" -> PlanStepPriority.HIGH
                    "medium" -> PlanStepPriority.MEDIUM
                    "low" -> PlanStepPriority.LOW
                    else -> throw IllegalArgumentException("Invalid stored priority")
                }
                PlanStep(content, status, priority)
            }
            states[chatId] = PlanStateSnapshot(immutableSteps(steps), stage)
        }
        return states
    }
}

private fun immutableSteps(steps: List<PlanStep>): List<PlanStep> =
    Collections.unmodifiableList(ArrayList(steps))

/** Both Stores share this monitor for all reads and read-modify-write transactions. */
internal class PlanStateRepository(private val backend: PlanStateBackend) {
    private var failedWrite = false
    private var states: Map<String, PlanStateSnapshot> = try {
        val document = backend.load()
        if (document == null) emptyMap() else PlanStateCodec.decode(document)
    } catch (error: Exception) {
        throw PlanPersistenceException("Cannot restore authoritative plan state", error)
    }
    private val state = MutableStateFlow(visiblePlans(states))
    val plans: StateFlow<Map<String, List<PlanStep>>> = state.asStateFlow()

    @Synchronized
    fun read(chatId: String): PlanStateSnapshot {
        checkHealthy(chatId)
        return states[chatId] ?: PlanStateSnapshot(emptyList(), null)
    }

    @Synchronized
    fun update(chatId: String, steps: List<PlanStep>, stage: PlanModelStage?): List<PlanStep> {
        val previous = read(chatId).steps
        val snapshot = PlanStateSnapshot(immutableSteps(steps), stage)
        val next = if (steps.isEmpty() && stage == null) states - chatId
            else states + (chatId to snapshot)
        // Validate before the commit boundary: invalid input is not a disk failure.
        val document = PlanStateCodec.encode(next)
        try {
            backend.save(document)
        } catch (error: Exception) {
            // Commit outcome can be unknown: stop serving authority until process restart.
            // Never silently switch to in-memory-only storage or retry another source.
            failedWrite = true
            throw PlanPersistenceException("Plan commit failed; restart required before further plan access", error)
        }
        states = next
        state.value = visiblePlans(states)
        return previous
    }

    @Synchronized
    fun updateSteps(chatId: String, steps: List<PlanStep>): List<PlanStep> =
        update(chatId, steps, read(chatId).stage)

    @Synchronized
    fun updateStage(chatId: String, stage: PlanModelStage?) {
        update(chatId, read(chatId).steps, stage)
    }

    @Synchronized
    fun clear(chatId: String) { update(chatId, emptyList(), null) }

    @Synchronized
    fun formatPlanContext(chatId: String): String {
        val snapshot = read(chatId)
        if (snapshot.steps.isEmpty() && snapshot.stage == null) return ""
        return """<authoritative_plan_state>
${PlanStateCodec.snapshotJson(snapshot)}
This is the authoritative persisted plan and phase for this chat, not a new task request.
Continue from pending/in_progress steps. Do not repeat completed operations or reset their status merely because context was summarized or the process restarted.
Completed records are not proof of success beyond their recorded status; review existing results before explicitly revising the plan.
A delegated subagent must not overwrite or restart the parent plan. Only the parent may update it with update_plan.
</authoritative_plan_state>"""
    }

    private fun checkHealthy(chatId: String) {
        require(chatId.isNotBlank()) { "A chat id is required" }
        check(!failedWrite) { "Plan persistence is unavailable after a failed commit; restart required" }
    }

    private fun visiblePlans(states: Map<String, PlanStateSnapshot>): Map<String, List<PlanStep>> =
        Collections.unmodifiableMap(states.filterValues { it.steps.isNotEmpty() }.mapValues { it.value.steps })
}
