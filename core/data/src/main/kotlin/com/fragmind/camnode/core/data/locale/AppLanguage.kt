package com.fragmind.camnode.core.data.locale

import android.app.LocaleManager
import android.content.Context
import android.content.res.Configuration
import android.os.Build
import android.os.LocaleList
import androidx.core.content.edit
import java.util.Locale

/**
 * The language CamNode shows, chosen inside the app. "" follows the phone.
 *
 * Android 13+ keeps a per-app language itself (also editable in system settings) and
 * applies it to every component. Older versions have no such setting, so the choice is
 * stored here and every entry point wraps its context with [wrap]; a running service
 * picks up a change when it next starts.
 */
object AppLanguage {
    /** Language tags with translations, in the order the picker shows them. */
    val SUPPORTED = listOf("es", "en", "pt", "fr", "de", "hi", "id", "ar", "zh", "ru")

    /** Where the platform's own name is not what speakers call the language. */
    private val NATIVE_NAMES = mapOf("id" to "Bahasa Indonesia", "zh" to "中文（简体）")

    private const val PREFS = "camnode_language"
    private const val KEY = "tag"

    fun current(context: Context): String =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales.toLanguageTags().substringBefore(',').substringBefore('-')
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(KEY, "").orEmpty()
        }

    /**
     * Saves [tag] ("" for the phone's language). Android 13+ recreates visible activities
     * itself; on older versions the caller recreates its activity.
     */
    fun set(context: Context, tag: String) {
        require(tag.isEmpty() || tag in SUPPORTED) { "Unsupported language $tag" }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            context.getSystemService(LocaleManager::class.java).applicationLocales = LocaleList.forLanguageTags(tag)
        } else {
            context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putString(KEY, tag) }
        }
    }

    /** For attachBaseContext before Android 13: a context whose resources use the chosen language. */
    fun wrap(base: Context): Context {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) return base
        val tag = current(base).ifEmpty { return base }
        val configuration = Configuration(base.resources.configuration)
        configuration.setLocales(LocaleList(Locale.forLanguageTag(tag)))
        return base.createConfigurationContext(configuration)
    }

    /** The language's own name, so anyone can find theirs in the list. */
    fun nativeName(tag: String): String {
        NATIVE_NAMES[tag]?.let { return it }
        val locale = Locale.forLanguageTag(tag)
        return locale.getDisplayName(locale).replaceFirstChar { it.titlecase(locale) }
    }
}
