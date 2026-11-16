package com.fragmind.camnode.feature.applock

import android.content.ComponentName
import android.content.Context
import android.content.pm.PackageManager
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.fragmind.camnode.core.model.AppDisguise
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Switches the launcher entry: each disguise is an activity-alias with its own icon and
 * label (declared in the app manifest); exactly one is enabled at a time.
 *
 * Disabling the alias a task was launched from finishes that task, so the switch waits
 * until the app is in the background. Must be called on the main thread.
 */
@Singleton
class LauncherDisguiser @Inject constructor(@ApplicationContext private val context: Context) : DefaultLifecycleObserver {
    private var pending: LauncherEntry? = null
    private var observing = false

    fun apply(disguise: AppDisguise, iconStyle: Int = 0) {
        val entry = LauncherEntry(disguise, iconStyle.coerceIn(0, disguise.iconStyles - 1))
        val lifecycle = ProcessLifecycleOwner.get().lifecycle
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED)) {
            switchTo(entry)
            return
        }
        pending = entry
        if (!observing) {
            observing = true
            lifecycle.addObserver(this)
        }
    }

    override fun onStop(owner: LifecycleOwner) {
        pending?.let(::switchTo)
        pending = null
    }

    private fun switchTo(wantedEntry: LauncherEntry) {
        val packageManager = context.packageManager
        LauncherEntry.all().forEach { entry ->
            val component = entry.component(context)
            val wanted = if (entry == wantedEntry) PackageManager.COMPONENT_ENABLED_STATE_ENABLED else PackageManager.COMPONENT_ENABLED_STATE_DISABLED
            if (packageManager.isEnabled(component, defaultEnabled = entry.disguise == AppDisguise.NONE) != (wanted == PackageManager.COMPONENT_ENABLED_STATE_ENABLED)) {
                packageManager.setComponentEnabledSetting(component, wanted, PackageManager.DONT_KILL_APP)
            }
        }
    }

    private fun PackageManager.isEnabled(component: ComponentName, defaultEnabled: Boolean): Boolean =
        when (getComponentEnabledSetting(component)) {
            PackageManager.COMPONENT_ENABLED_STATE_ENABLED -> true
            PackageManager.COMPONENT_ENABLED_STATE_DEFAULT -> defaultEnabled
            else -> false
        }

}

/** One launcher icon: a disguise and which of its icon styles. */
data class LauncherEntry(val disguise: AppDisguise, val iconStyle: Int) {
    /** Must match the activity-alias names in app/src/main/AndroidManifest.xml. */
    val className: String
        get() {
            val base = when (disguise) {
                AppDisguise.NONE -> return "com.fragmind.camnode.launcher.Default"
                AppDisguise.CALCULATOR -> "Calculator"
                AppDisguise.CLOCK -> "Clock"
                AppDisguise.CALENDAR -> "Calendar"
                AppDisguise.NOTES -> "Notes"
            }
            return "com.fragmind.camnode.launcher.$base" + if (iconStyle == 0) "" else "${iconStyle + 1}"
        }

    fun component(context: Context) = ComponentName(context.packageName, className)

    companion object {
        fun all(): List<LauncherEntry> = AppDisguise.entries.flatMap { disguise -> List(disguise.iconStyles) { LauncherEntry(disguise, it) } }
    }
}
