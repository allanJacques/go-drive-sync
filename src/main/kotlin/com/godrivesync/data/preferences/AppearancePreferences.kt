package com.godrivesync.data.preferences

import com.godrivesync.ui.theme.AppStyle
import java.util.prefs.Preferences

/** Uses the OS-backed Java preferences store; no database or extra library is needed. */
class AppearancePreferences {
    private val node by lazy { Preferences.userRoot().node("com/godrivesync/appearance") }

    fun loadStyle(): AppStyle = runCatching {
        AppStyle.fromId(node.get("theme", null))
    }.getOrDefault(AppStyle.AURORA)

    /** Caller performs persistence off the UI thread. Failure is reported by the appearance screen. */
    fun saveStyle(style: AppStyle) {
        node.put("theme", style.id)
        node.flush()
    }
}
