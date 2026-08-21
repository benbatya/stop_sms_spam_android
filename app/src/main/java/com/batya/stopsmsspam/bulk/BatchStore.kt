package com.batya.stopsmsspam.bulk

import android.content.Context
import android.util.Log
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
                            put("delete", plan.delete)
                            put("block", plan.block)
                            put("messageIds", JSONArray().apply { plan.messageIds.forEach { put(it) } })
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
            val idsJson = obj.optJSONArray("messageIds") ?: JSONArray()
            ReplyPlan(
                address = obj.getString("address"),
                keyword = obj.getString("keyword"),
                subscriptionId = obj.optInt("subscriptionId", -1),
                sendReply = obj.optBoolean("sendReply", true),
                delete = obj.optBoolean("delete", true),
                block = obj.optBoolean("block", false),
                messageIds = (0 until idsJson.length()).map { idsJson.getLong(it) },
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
