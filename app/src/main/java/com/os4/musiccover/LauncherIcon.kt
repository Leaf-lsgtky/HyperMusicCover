package com.os4.musiccover

import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager

/**
 * The desktop icon, which is the `.LauncherAlias` entry in the manifest.
 *
 * The component's enabled state is the only record of whether it is hidden - there is no pref
 * beside it to drift out of step. Hiding it leaves MainActivity reachable two ways: the
 * MODULE_SETTINGS filter LSPosed opens a module through, and [SecretCodeReceiver].
 */
object LauncherIcon {
    /** Dialled as `*#*#95993926#*#*`. Must match the receiver's host in the manifest. */
    const val SECRET_CODE = "95993926"

    // Class names come from the namespace (com.os4.musiccover), not the application id, so the
    // alias is looked up next to MainActivity rather than under context.packageName.
    private fun component(context: Context) = ComponentName(
        context.packageName,
        MainActivity::class.java.name.substringBeforeLast('.') + ".LauncherAlias",
    )

    fun isHidden(context: Context): Boolean =
        context.packageManager.getComponentEnabledSetting(component(context)) ==
            PackageManager.COMPONENT_ENABLED_STATE_DISABLED

    fun setHidden(context: Context, hidden: Boolean) {
        context.packageManager.setComponentEnabledSetting(
            component(context),
            // ENABLED, not DEFAULT, as InstallerX Revived does: going back to DEFAULT left the alias
            // resolvable but the HyperOS launcher never put the icon back.
            if (hidden) PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            else PackageManager.COMPONENT_ENABLED_STATE_ENABLED,
            // Without this the package is killed on the change, closing the page the switch is on.
            PackageManager.DONT_KILL_APP,
        )
    }
}

/**
 * Opens the app when [LauncherIcon.SECRET_CODE] is dialled, where the dialler delivers to us.
 *
 * On this phone it never does, and the module in SystemUI answers the same broadcast instead -
 * see `Main.registerSecretCode`, which also records what was measured. This is kept because the
 * two do not conflict: where the dialler does deliver to an app, this is the shorter path and
 * works without the module being enabled; where it does not, this simply never fires.
 *
 * The manifest filter already restricts the action and the code, so anything that arrives here
 * is ours.
 */
class SecretCodeReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        context.startActivity(
            Intent(context, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }
}
