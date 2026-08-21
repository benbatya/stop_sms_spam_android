package com.batya.stopsmsspam.role

import android.app.role.RoleManager
import android.content.Context
import android.content.Intent
import android.provider.Settings
import android.provider.Telephony

/**
 * Wraps acquiring and giving back the default-SMS-app role.
 *
 * The role is the whole reason this app can mark spam read and file its own replies into the
 * real threads - but it also makes the app responsible for every incoming message, so the
 * intended pattern is to take the role, clean up, and hand it straight back. There is no API to
 * hand it back programmatically, so [handBackIntent] sends the user to the settings screen where
 * they can reselect their normal messaging app.
 */
class SmsRoleManager(private val context: Context) {

    private val roleManager: RoleManager? = context.getSystemService(RoleManager::class.java)

    /**
     * Whether we currently hold the SMS role.
     *
     * [RoleManager.isRoleHeld] is the authority and is checked first: on Android 16 an app can
     * hold ROLE_SMS while `Telephony.Sms.getDefaultSmsPackage` still reports something else,
     * which left the app stuck on its setup screen even though its receivers were already
     * handling incoming messages. The legacy lookup stays as a fallback for the reverse case.
     */
    fun isDefaultSmsApp(): Boolean {
        val heldByRole = runCatching {
            roleManager?.isRoleHeld(RoleManager.ROLE_SMS) == true
        }.getOrDefault(false)
        return heldByRole || context.packageName == Telephony.Sms.getDefaultSmsPackage(context)
    }

    fun isRoleAvailable(): Boolean =
        roleManager?.isRoleAvailable(RoleManager.ROLE_SMS) == true

    /** Intent that shows the system's "make this your SMS app?" dialog. */
    fun requestRoleIntent(): Intent? =
        roleManager?.takeIf { it.isRoleAvailable(RoleManager.ROLE_SMS) }
            ?.createRequestRoleIntent(RoleManager.ROLE_SMS)

    /** Package name of whatever currently holds the role, for showing the user. */
    fun currentDefaultPackage(): String? = Telephony.Sms.getDefaultSmsPackage(context)

    fun currentDefaultLabel(): String? {
        val pkg = currentDefaultPackage() ?: return null
        return runCatching {
            val pm = context.packageManager
            pm.getApplicationLabel(pm.getApplicationInfo(pkg, 0)).toString()
        }.getOrNull()
    }

    /**
     * Opens the system default-apps screen. Android offers no way for an app to give the SMS
     * role to a specific other app, so the handback is necessarily a manual step.
     */
    fun handBackIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
