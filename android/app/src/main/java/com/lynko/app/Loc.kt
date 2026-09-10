package com.lynko.app

import android.content.Context
import android.content.res.Configuration
import org.json.JSONObject
import java.util.Locale

/**
 * JSON-key locale loader for the phone UI. The same en.json / fa.json files
 * ship to desktop (TypeScript import) and phone (this assets loader), so a
 * string is edited in exactly one place. Keys mirror the desktop's t() API.
 *
 * Language follows the system locale (fa → Farsi/RTL, anything else → English).
 */
object Loc {

    @Volatile
    private var bundle: JSONObject? = null

    @Volatile
    private var fallback: JSONObject? = null

    @Volatile
    var lang: String = "en"
        private set

    fun isRtl(): Boolean = lang == "fa"

    /** Load from assets/locales/<lang>.json. Safe to call every onCreate. */
    fun init(ctx: Context) {
        lang = if (ctx.resources.configuration.locales[0].language == "fa") "fa" else "en"
        fun load(l: String): JSONObject? = try {
            ctx.assets.open("locales/$l.json").bufferedReader().use { JSONObject(it.readText()) }
        } catch (e: Exception) {
            android.util.Log.w("lynko", "locale '$l' not loadable: ${e.message}")
            null
        }
        fallback = fallback ?: load("en")
        bundle = (if (lang == "en") fallback else load("fa")) ?: fallback
    }

    /** t("phone", "start") → "Start Lynko" (or Farsi). Falls back to key. */
    fun t(section: String, key: String): String {
        val s = (bundle ?: fallback)?.optJSONObject(section)
        val v = s?.optString(key)?.takeIf { it.isNotEmpty() }
        if (v != null) return v
        val f = fallback?.optJSONObject(section)?.optString(key)?.takeIf { it.isNotEmpty() }
        return f ?: "$section.$key"
    }
}
