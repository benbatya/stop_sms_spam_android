package com.batya.stopsmsspam.sms

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.io.File

/**
 * Receives incoming MMS while this app holds the SMS role.
 *
 * Storing MMS properly means parsing WAP push PDUs, downloading parts over the carrier's MMSC
 * and writing a multi-table representation into the provider - thousands of lines of work that a
 * spam-cleanup tool has no business reimplementing. This app deliberately does not do it.
 *
 * What it does instead is refuse to destroy anything: the raw PDU is written to app-private
 * storage and the user is told, clearly, that MMS will not arrive until they hand the SMS role
 * back. That is why the intended workflow is to hold the role only while cleaning up.
 */
class MmsDeliverReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val pdu = intent.getByteArrayExtra("data")
        val appContext = context.applicationContext
        val pendingResult = goAsync()
        scope.launch {
            try {
                if (pdu != null) {
                    val dir = File(appContext.filesDir, "unhandled_mms").apply { mkdirs() }
                    File(dir, "mms_${System.currentTimeMillis()}.pdu").writeBytes(pdu)
                }
                Notifications.notifyMmsUnsupported(appContext)
            } catch (t: Throwable) {
                Log.e(TAG, "Failed to park incoming MMS", t)
            } finally {
                pendingResult.finish()
            }
        }
    }

    private companion object {
        const val TAG = "MmsDeliverReceiver"
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    }
}
