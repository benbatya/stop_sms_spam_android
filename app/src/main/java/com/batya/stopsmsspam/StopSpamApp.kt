package com.batya.stopsmsspam

import android.app.Application
import com.batya.stopsmsspam.sms.Notifications

class StopSpamApp : Application() {
    override fun onCreate() {
        super.onCreate()
        // Channels must exist before the first incoming message arrives, not when the UI opens:
        // while this app holds the SMS role, a notification is the only way the user sees a text.
        Notifications.createChannels(this)
    }
}
