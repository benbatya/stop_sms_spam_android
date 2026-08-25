package com.batya.stopsmsspam.bulk

import android.content.Context
import android.util.Log
import com.batya.stopsmsspam.data.model.ClearReason
import com.batya.stopsmsspam.data.model.MessageRef
import com.batya.stopsmsspam.data.model.MessageSource
import com.batya.stopsmsspam.data.model.ReplyPlan
import com.batya.stopsmsspam.data.model.SendOutcome
import com.batya.stopsmsspam.data.model.SendStatus
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

/** Everything needed to run - or resume - one bulk opt-out batch. */
data class BatchSnapshot(
    val plans: List<ReplyPlan>,
    val outcomes: List<SendOutcome> = emptyList(),
    val delaySeconds: Int = SendPacing.DEFAULT_DELAY_SECONDS,
    val jitterPercent: Int = SendPacing.DEFAULT_JITTER_PERCENT,
    val dryRun: Boolean = true,
) {
    /** Plans are worked through in order, so the tail past the recorded outcomes is what is left. */
    val remaining: List<ReplyPlan> get() = plans.drop(outcomes.size)
}

/**
 * Persists the in-flight batch to disk.
 *
 * A paced run can last an hour, which is long enough for the process to be killed underneath it.
 * Without a snapshot the user would have no way to tell which numbers had already been replied
 * to, and re-running the whole batch would double-text everyone.
 */
class BatchStore(context: Context) {

    private val file = File(context.filesDir, "batch.json")

    fun save(snapshot: BatchSnapshot) {
        runCatching { file.writeText(encode(snapshot).toString()) }
            .onFailure { Log.e(TAG, "Could not persist batch", it) }
    }

    fun load(): BatchSnapshot? = runCatching {
        if (!file.exists()) return null
        decode(JSONObject(file.readText()))
    }.onFailure { Log.e(TAG, "Could not read batch", it) }.getOrNull()

    fun clear() {
        runCatching { file.delete() }
    }

    private fun encode(snapshot: BatchSnapshot) = JSONObject().apply {
        put("delaySeconds", snapshot.delaySeconds)
        put("jitterPercent", snapshot.jitterPercent)
        put("dryRun", snapshot.dryRun)
        put(
            "plans",
            JSONArray().apply {
                snapshot.plans.forEach { plan ->
                    put(
                        JSONObject().apply {
                            put("address", plan.address)
                            put("keyword", plan.keyword)
                            put("subscriptionId", plan.subscriptionId)
                            put("sendReply", plan.sendReply)
                            put("clearReason", plan.clearReason?.name ?: JSONObject.NULL)
                            put("delete", plan.delete)
                            put("block", plan.block)
                            // Source travels with each id: a resumed batch must not delete an
                            // SMS row that happens to share an id with the MMS it meant.
                            put(
                                "messages",
                                JSONArray().apply {
                                    plan.messages.forEach {
                                        put(JSONObject().put("id", it.id).put("src", it.source.name))
                                    }
                                },
                            )
                        },
                    )
                }
            },
        )
        put(
            "outcomes",
            JSONArray().apply {
                snapshot.outcomes.forEach { outcome ->
                    put(
                        JSONObject().apply {
                            put("address", outcome.address)
                            put("keyword", outcome.keyword)
                            put("status", outcome.status.name)
                            put("detail", outcome.detail ?: JSONObject.NULL)
                            put("timestamp", outcome.timestamp)
                        },
                    )
                }
            },
        )
    }

    private fun decode(json: JSONObject): BatchSnapshot {
        val plansJson = json.optJSONArray("plans") ?: JSONArray()
        val plans = (0 until plansJson.length()).map { i ->
            val obj = plansJson.getJSONObject(i)
            val refsJson = obj.optJSONArray("messages") ?: JSONArray()
            ReplyPlan(
                address = obj.getString("address"),
                keyword = obj.getString("keyword"),
                subscriptionId = obj.optInt("subscriptionId", -1),
                sendReply = obj.optBoolean("sendReply", true),
                // A batch written before this field existed only ever skipped a reply because
                // the sender was already opted out, so that is what its absence means.
                clearReason = obj.optString("clearReason")
                    .takeIf { it.isNotEmpty() && it != "null" }
                    ?.let { name -> ClearReason.entries.firstOrNull { it.name == name } }
                    ?: ClearReason.ALREADY_OPTED_OUT.takeIf { !obj.optBoolean("sendReply", true) },
                delete = obj.optBoolean("delete", true),
                block = obj.optBoolean("block", false),
                messages = (0 until refsJson.length()).map {
                    val ref = refsJson.getJSONObject(it)
                    MessageRef(
                        id = ref.getLong("id"),
                        source = runCatching { MessageSource.valueOf(ref.getString("src")) }
                            .getOrDefault(MessageSource.SMS),
                    )
                },
            )
        }

        val outcomesJson = json.optJSONArray("outcomes") ?: JSONArray()
        val outcomes = (0 until outcomesJson.length()).map { i ->
            val obj = outcomesJson.getJSONObject(i)
            SendOutcome(
                address = obj.getString("address"),
                keyword = obj.getString("keyword"),
                status = runCatching { SendStatus.valueOf(obj.getString("status")) }
                    .getOrDefault(SendStatus.FAILED),
                detail = obj.optString("detail").takeIf { it.isNotEmpty() && it != "null" },
                timestamp = obj.optLong("timestamp"),
            )
        }

        return BatchSnapshot(
            plans = plans,
            outcomes = outcomes,
            delaySeconds = json.optInt("delaySeconds", SendPacing.DEFAULT_DELAY_SECONDS),
            jitterPercent = json.optInt("jitterPercent", SendPacing.DEFAULT_JITTER_PERCENT),
            dryRun = json.optBoolean("dryRun", true),
        )
    }

    private companion object {
        const val TAG = "BatchStore"
    }
}
