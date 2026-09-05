package com.droiddock.app

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import java.util.Locale

/**
 * Translation, with **English as the key** — the same scheme the Mac client
 * uses, so both halves of DroidDock have one story about how strings work.
 *
 * # Why not `strings.xml`
 *
 * `strings.xml` is the idiomatic Android answer and it was the first choice.
 * It does not survive contact with this codebase: `stringResource` is a
 * `@Composable` function, and a large share of DroidDock's user-facing text is
 * produced outside composition — `Toast` calls inside click lambdas,
 * notification titles built in [BridgeService] and [PermissionHealth], Quick
 * Settings tile subtitles, the crash notifier. Those need `getString` and a
 * `Context`, so extraction would mean two different transformations decided per
 * call site by whether the enclosing function happens to be composable. That is
 * a lot of ways to be subtly wrong for no gain while only one language ships.
 *
 * [t] is an ordinary function and works identically in both places.
 *
 * **What that forgoes, stated plainly:** Android's resource machinery — locale
 * qualifiers, `values-de/`, plural rules, RTL layout mirroring, and translators
 * working in XML with familiar tooling. If DroidDock ever ships several
 * languages seriously, generating `strings.xml` *from* these catalogs is the
 * migration, and the English keys make that mechanical.
 *
 * # Recomposition
 *
 * [locale] is a Compose `mutableStateOf`, and [t] reads it. A composable that
 * calls `t(...)` therefore registers as a reader of that state, and changing
 * the language recomposes exactly the parts of the UI showing text — no
 * activity recreation, no manual invalidation.
 */
/** A language: English keys mapped to their translations. Top-level because
 *  Kotlin has no nested type aliases. */
typealias Catalog = Map<String, String>

object I18n {

    /**
     * Every language this build can display.
     *
     * English is absent on purpose: it *is* the keys. **Adding a language is one
     * entry here** — no code changes anywhere else. The picker in Settings is
     * always shown and fills itself from this map; while it is empty the row
     * says so rather than pretending there is a choice.
     *
     * ```kotlin
     * private val CATALOGS = mapOf("de" to mapOf("Connected" to "Verbunden"))
     * ```
     */
    private val CATALOGS: Map<String, Catalog> = mapOf(
        // <locale-list>
        "ar" to catalog_ar,
        "bn" to catalog_bn,
        "de" to catalog_de,
        "es" to catalog_es,
        "fr" to catalog_fr,
        "hi" to catalog_hi,
        "id" to catalog_id,
        "it" to catalog_it,
        "ja" to catalog_ja,
        "ko" to catalog_ko,
        "pt" to catalog_pt,
        "ru" to catalog_ru,
        "tr" to catalog_tr,
        "vi" to catalog_vi,
        "zh" to catalog_zh,
        "zh-Hant" to catalog_zh_Hant,
        // </locale-list>
    )

    /** Tag → label, for the picker. English first, then whatever exists. */
    fun available(): List<Pair<String, String>> =
        listOf("en" to "English") + CATALOGS.keys.map { it to displayName(it) }

    private fun displayName(tag: String): String =
        Locale.forLanguageTag(tag).getDisplayName(Locale.forLanguageTag(tag))
            .replaceFirstChar { it.uppercase(Locale.forLanguageTag(tag)) }

    /** True while only English exists — the picker says so instead of implying
     *  there is something to switch to. */
    fun onlyEnglish(): Boolean = CATALOGS.isEmpty()

    /**
     * The stored choice: a tag, or `""` for "follow the system".
     *
     * Backed by Compose state so a change repaints; persisted through [Prefs] so
     * it survives a restart.
     */
    var locale by mutableStateOf("")
        private set

    fun load(ctx: android.content.Context) {
        locale = Prefs.locale(ctx)
    }

    fun set(ctx: android.content.Context, tag: String) {
        Prefs.setLocale(ctx, tag)
        locale = tag
    }

    /** Languages written right-to-left. Direction is the app's business, not the
     *  translator's — a catalog is just strings. */
    private val RTL = setOf("ar", "fa", "ur", "he")

    /** Whether the active language is right-to-left. Drives `LocalLayoutDirection`,
     *  which is what actually mirrors Compose's rows, padding and alignment. */
    fun isRtl(): Boolean = RTL.contains(activeTag())

    /** The language actually in use, resolving `""` against the system. */
    fun activeTag(): String {
        val wanted = locale.ifEmpty { Locale.getDefault().language }
        val primary = wanted.substringBefore('-').lowercase(Locale.ROOT)
        return if (CATALOGS.containsKey(primary)) primary else "en"
    }

    /**
     * Translate, substituting `{name}` placeholders.
     *
     * An untranslated string comes back unchanged — which is correct English,
     * not a resource id. That is the whole reason the key is the English text.
     */
    fun t(en: String, vararg args: Pair<String, Any?>): String {
        // Read through `locale` rather than a cached catalog so composables that
        // call this are registered as readers and recompose on a change.
        val tag = locale.ifEmpty { Locale.getDefault().language }.substringBefore('-').lowercase(Locale.ROOT)
        var out = CATALOGS[tag]?.get(en) ?: en
        for ((name, value) in args) out = out.replace("{$name}", value.toString())
        return out
    }

    /**
     * Singular/plural, as two independent English strings.
     *
     * Not a "+s" rule: English itself breaks it ("1 entry" / "2 entries"), and a
     * language whose plural rules differ needs both forms translated separately
     * anyway. `{n}` in either is replaced with [count].
     */
    fun tn(one: String, other: String, count: Int, vararg args: Pair<String, Any?>): String =
        t(if (count == 1) one else other, "n" to count, *args)
}

/** Shorthand, so a call site is `t("Connected")` and not `I18n.t("Connected")`. */
fun t(en: String, vararg args: Pair<String, Any?>): String = I18n.t(en, *args)
