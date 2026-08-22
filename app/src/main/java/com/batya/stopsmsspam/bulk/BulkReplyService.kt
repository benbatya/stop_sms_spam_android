package com.batya.stopsmsspam.bulk

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.util.Log
import androidx.core.app.ServiceCompat
import com.batya.stopsmsspam.data.BlockedNumbers
import com.batya.stopsmsspam.data.SenderMemory
import com.batya.stopsmsspam.data.SmsRepository
import com.batya.stopsmsspam.data.model.BatchProgress
import com.batya.stopsmsspam.data.model.ReplyPlan
import com.batya.stopsmsspam.data.model.SendOutcome
import com.batya.stopsmsspam.data.model.SendStatus
import com.batya.stopsmsspam.sms.Notifications
import com.batya.stopsmsspam.sms.SendResult
import com.batya.stopsmsspam.sms.SmsSender
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlin.coroutines.coroutineContext

/**
 * Runs the paced batch of opt-out replies.
 *
 * A foreground service rather than a WorkManager job because the run is long, deliberately slow,
 * and something the user is actively watching: they need live progress, a working Cancel, and a
 * guarantee the system will not quietly pause the sending halfway through.
 */
class BulkReplyService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var runJob: Job? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must happen before anything else: the system kills a foreground service that is not
        // in the foreground within a few seconds of being started, cancel requests included.
        goForeground(BulkReplyController.progress.value)

        when (intent?.action) {
            ACTION_CANCEL -> {
                val job = runJob
                if (job?.isActive == true) {
                    // The job's own finally clause tears the service down.
                    job.cancel(CancellationException("Cancelled by user"))
                } else {
                    // Cancel arrived from a stale notification with no run to stop. We were
                    // just pushed into the foreground above, so we have to stand ourselves down.
                    finish()
                }
                return START_NOT_STICKY
            }

            else -> startBatch()
        }
        return START_NOT_STICKY
    }

    private fun startBatch() {
        if (runJob?.isActive == true) return

        val snapshot = BatchStore(this).load()
        if (snapshot == null || snapshot.remaining.isEmpty()) {
            Log.w(TAG, "Nothing to send")
            // The caller already flipped the controller to running; clear it, or the UI sits on
            // a progress screen whose only button cancels a batch that never started.
            BulkReplyController.update { it.copy(running = false, currentAddress = null, nextSendAtMillis = null) }
            finish()
            return
        }

        runJob = scope.launch {
            try {
                runBatch(snapshot)
            } catch (cancellation: CancellationException) {
                recordCancellation(snapshot)
                throw cancellation
            } finally {
                finish()
            }
        }
    }

    private suspend fun runBatch(snapshot: BatchSnapshot) {
        val repository = SmsRepository(this)
        val blockedNumbers = BlockedNumbers(this)
        val store = BatchStore(this)
        val outcomes = snapshot.outcomes.toMutableList()

        BulkReplyController.set(
            BatchProgress(
                running = true,
                dryRun = snapshot.dryRun,
                total = snapshot.plans.size,
                outcomes = outcomes.toList(),
            ),
        )

        val remaining = snapshot.remaining
        remaining.forEachIndexed { index, plan ->
            coroutineContext.ensureActive()

            BulkReplyController.update { it.copy(currentAddress = plan.address, nextSendAtMillis = null) }
            publish(outcomes)

            val outcome = if (!plan.sendReply) {
                // Already opted out of: clean the thread up, send nothing. Not gated on dryRun
                // because marking read is what the user asked for either way - and a dry run
                // that silently skipped it would misrepresent what the real run does.
                val cleared = if (snapshot.dryRun) {
                    false
                } else {
                    applyPostSend(repository, blockedNumbers, plan)
                }
                SendOutcome(
                    address = plan.address,
                    keyword = plan.keyword,
                    status = SendStatus.CLEARED,
                    detail = when {
                        snapshot.dryRun -> "Dry run - would be cleared, not replied to"
                        cleared -> "Blocked and cleared without replying"
                        else -> "Already unsubscribed - cleared without replying"
                    },
                    timestamp = System.currentTimeMillis(),
                    blocked = cleared,
                )
            } else if (snapshot.dryRun) {
                // Exercise every step except the one that actually texts a stranger.
                SendOutcome(
                    address = plan.address,
                    keyword = plan.keyword,
                    status = SendStatus.SENT,
                    detail = "Dry run - nothing was sent",
                    timestamp = System.currentTimeMillis(),
                )
            } else {
                when (
                    val result = SmsSender.sendText(
                        context = this,
                        address = plan.address,
                        body = plan.keyword,
                        subscriptionId = plan.subscriptionId,
                    )
                ) {
                    is SendResult.Success -> {
                        // Nothing to record here: SmsSender files the reply into the Sent box
                        // on confirmation, and that row is what later marks this sender as
                        // already opted out.
                        val blocked = applyPostSend(repository, blockedNumbers, plan)
                        SendOutcome(
                            address = plan.address,
                            keyword = plan.keyword,
                            status = SendStatus.SENT,
                            timestamp = System.currentTimeMillis(),
                            blocked = blocked,
                        )
                    }

                    is SendResult.Failure -> SendOutcome(
                        address = plan.address,
                        keyword = plan.keyword,
                        status = SendStatus.FAILED,
                        detail = result.reason,
                        timestamp = System.currentTimeMillis(),
                    )

                    // Deliberately no mark-read and no Sent-box row: we do not know yet whether
                    // this one went out, and claiming either way would be wrong.
                    is SendResult.Unconfirmed -> SendOutcome(
                        address = plan.address,
                        keyword = plan.keyword,
                        status = SendStatus.UNCONFIRMED,
                        detail = result.reason,
                        timestamp = System.currentTimeMillis(),
                    )
                }
            }

            outcomes += outcome
            store.save(snapshot.copy(outcomes = outcomes.toList()))
            publish(outcomes)

            // Pacing exists to protect the radio and the carrier. A cleared thread touched
            // neither, so waiting after one would just make a long batch longer for no reason.
            val isLast = index == remaining.lastIndex
            if (!isLast && plan.sendReply) {
                val wait = SendPacing.delayMillis(snapshot.delaySeconds, snapshot.jitterPercent)
                BulkReplyController.update {
                    it.copy(
                        currentAddress = null,
                        nextSendAtMillis = System.currentTimeMillis() + wait,
                    )
                }
                publish(outcomes)
                delay(wait)
            }
        }

        BulkReplyController.update {
            it.copy(running = false, currentAddress = null, nextSendAtMillis = null)
        }
        store.clear()
    }

    /**
     * Disposes of a sender's thread once handled, per what the user chose when selecting it.
     *
     * Blocking goes first: if it succeeds the number is dealt with regardless of what becomes of
     * the messages, and if it fails the cleanup should still happen. Only reached after the radio
     * has confirmed a reply went out, or for a thread that was never going to be replied to.
     *
     * @return true if the number was blocked.
     */
    private suspend fun applyPostSend(
        repository: SmsRepository,
        blockedNumbers: BlockedNumbers,
        plan: ReplyPlan,
    ): Boolean {
        val blocked = if (plan.block) {
            runCatching { blockedNumbers.block(plan.address) }
                .onFailure { Log.e(TAG, "Blocking ${plan.address} failed", it) }
                .getOrDefault(false)
        } else {
            false
        }

        runCatching {
            if (plan.delete) {
                repository.delete(plan.messageIds)
                // Deleting the thread implies not wanting its reply either, so the sender's
                // acknowledgement is dropped on arrival instead of landing back in the inbox.
                SenderMemory(this).markAutoDeleteResponses(plan.address)
            } else {
                repository.markRead(plan.messageIds)
            }
        }.onFailure { Log.e(TAG, "Post-send cleanup failed", it) }

        return blocked
    }

    /**
     * A cancelled run leaves the already-sent entries alone and records the untouched tail as
     * cancelled, so the summary shows exactly who was and was not contacted.
     */
    private fun recordCancellation(snapshot: BatchSnapshot) {
        val done = BulkReplyController.progress.value.outcomes
        val untouched = snapshot.plans.drop(done.size).map { plan ->
            SendOutcome(
                address = plan.address,
                keyword = plan.keyword,
                status = SendStatus.CANCELLED,
                timestamp = System.currentTimeMillis(),
            )
        }
        BulkReplyController.update {
            it.copy(
                running = false,
                currentAddress = null,
                nextSendAtMillis = null,
                outcomes = done + untouched,
            )
        }
        BatchStore(this).clear()
    }

    private fun publish(outcomes: List<SendOutcome>) {
        BulkReplyController.update { it.copy(outcomes = outcomes.toList()) }
        Notifications.notifyBatchProgress(this, BulkReplyController.progress.value)
    }

    private fun goForeground(progress: BatchProgress) {
        ServiceCompat.startForeground(
            this,
            Notifications.BATCH_NOTIFICATION_ID,
            Notifications.buildBatchNotification(this, progress),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
        )
    }

    private fun finish() {
        // Leave the final notification up so the user can read the summary after we detach.
        ServiceCompat.stopForeground(this, ServiceCompat.STOP_FOREGROUND_DETACH)
        Notifications.notifyBatchProgress(this, BulkReplyController.progress.value)
        stopSelf()
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    companion object {
        private const val TAG = "BulkReplyService"

        const val ACTION_START = "com.batya.stopsmsspam.action.START_BATCH"
        const val ACTION_CANCEL = "com.batya.stopsmsspam.action.CANCEL_BATCH"

        /**
         * Persists [snapshot] and hands off to the service. The queue goes through disk rather
         * than the Intent so a restarted process can pick the batch back up.
         */
        fun start(context: Context, snapshot: BatchSnapshot) {
            BatchStore(context).save(snapshot)
            BulkReplyController.set(
                BatchProgress(
                    running = true,
                    dryRun = snapshot.dryRun,
                    total = snapshot.plans.size,
                    outcomes = snapshot.outcomes,
                ),
            )
            context.startForegroundService(
                Intent(context, BulkReplyService::class.java).setAction(ACTION_START),
            )
        }

        fun cancel(context: Context) {
            context.startService(
                Intent(context, BulkReplyService::class.java).setAction(ACTION_CANCEL),
            )
        }
    }
}
