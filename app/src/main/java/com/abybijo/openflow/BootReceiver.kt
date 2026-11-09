package com.abybijo.openflow

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * Brings the bubble back after a reboot or an app update — but only if
 * the user left it enabled. The preference is read from the same
 * DataStore the app uses; there is no second storage.
 */
class BootReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        val action = intent?.action ?: return
        if (action != Intent.ACTION_BOOT_COMPLETED &&
            action != Intent.ACTION_MY_PACKAGE_REPLACED
        ) return
        val pending = goAsync()
        CoroutineScope(SupervisorJob() + Dispatchers.IO).launch {
            try {
                val enabled = Store.prefsFlow(context).first().bubbleEnabled
                if (enabled) {
                    ContextCompat.startForegroundService(
                        context, Intent(context, BubbleService::class.java)
                    )
                }
            } catch (_: Exception) {
                // Starting the service can fail (permissions revoked, battery
                // restrictions). The bubble stays off until the app is opened.
            } finally {
                pending.finish()
            }
        }
    }
}
