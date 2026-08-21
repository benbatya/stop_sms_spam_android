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
 * hand it back programmatically - and, as [handBackIntent] documents, no way to open the
 * role-specific picker either - so the handback goes via the system default-apps screen.
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
     * Opens the system default-apps screen, where the user reselects their normal messaging app.
     *
     * This is deliberately **not** the same dialog as [requestRoleIntent], though it looks like
     * it should be. Every route to the role-specific SMS picker is closed to a normal app;
     * all three were tried against Android 16 and each fails differently:
     *
     *  - `createRequestRoleIntent` (the grant dialog) short-circuits when the caller already
     *    holds the role - RequestRoleActivity logs "Application is already a role holder",
     *    returns RESULT_OK and finishes without drawing anything. This button only exists while
     *    we hold the role, so it would never show UI.
     *  - `ACTION_MANAGE_DEFAULT_APP` + `EXTRA_ROLE_NAME` opens exactly the right "Default SMS
     *    app" picker, but requires the privileged `MANAGE_ROLE_HOLDERS` permission and throws
     *    SecurityException from a normal app. It resolves fine through PackageManager, so a
     *    resolveActivity() guard does not protect against it.
     *  - `Telephony.Sms.Intents.ACTION_CHANGE_DEFAULT`, the legacy change-default dialog, is
     *    blocked by PermissionPolicyService ("Action Removed", start result 102). It throws
     *    nothing and shows nothing, so a try/catch fallback never fires - the worst failure of
     *    the three, since the button looks wired up and silently does nothing.
     *
     * The default-apps list costs one extra tap and actually works. Do not "fix" this.
     */
    fun handBackIntent(): Intent =
        Intent(Settings.ACTION_MANAGE_DEFAULT_APPS_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
}
