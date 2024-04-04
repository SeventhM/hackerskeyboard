/*
 * Copyright (C) 2008-2009 Google Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License"); you may not
 * use this file except in compliance with the License. You may obtain a copy of
 * the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.pocketworkstation.pckeyboard

import android.os.Bundle
import android.text.TextUtils
import android.util.Log
import androidx.fragment.app.FragmentActivity
import androidx.preference.CheckBoxPreference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup
import androidx.preference.PreferenceManager
import java.text.Collator
import java.util.Arrays
import java.util.Locale

class InputLanguageSelection : FragmentActivity() {
    class InputLanguageSelectionFragment : PreferenceFragmentCompat() {
        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.language_prefs, rootKey)
        }
    }

    var fragment = InputLanguageSelectionFragment()
    private var mAvailableLanguages = ArrayList<Loc>()

    class Loc(var label: String, var locale: Locale) : Comparable<Any> {
        override fun toString(): String {
            return label
        }

        override fun compareTo(other: Any): Int {
            return sCollator.compare(
                label, (other as Loc).label
            )
        }

        companion object {
            var sCollator: Collator = Collator.getInstance()
        }
    }

    override fun onCreate(icicle: Bundle?) {
        super.onCreate(icicle)
        isInit = false
        fragment = InputLanguageSelectionFragment()
        supportFragmentManager.beginTransaction().replace(android.R.id.content, fragment).commit()
    }

    private var isInit = false
    fun init() {
        if (isInit) return
        isInit = true
        // Get the settings preferences
        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        val selectedLanguagePref = sp.getString(LatinIME.PREF_SELECTED_LANGUAGES, "")
        Log.i(TAG, "selected languages: $selectedLanguagePref")
        val languageList =
            selectedLanguagePref!!.split(",".toRegex()).dropLastWhile { it.isEmpty() }
                .toTypedArray()

        mAvailableLanguages = uniqueLocales

        // Compatibility hack for v1.22 and older - if a selected language 5-code isn't
        // found in the current list of available languages, try adding the 2-letter
        // language code. For example, "en_US" is no longer listed, so use "en" instead.
        val availableLanguages = HashSet<String>()
        for (i in mAvailableLanguages.indices) {
            val locale = mAvailableLanguages[i].locale
            availableLanguages.add(get5Code(locale))
        }
        val languageSelections = HashSet<String>()
        for (spec in languageList) {
            if (availableLanguages.contains(spec)) {
                languageSelections.add(spec)
            } else if (spec.length > 2) {
                val lang = spec.substring(0, 2)
                if (availableLanguages.contains(lang)) languageSelections.add(lang)
            }
        }

        val parent: PreferenceGroup = fragment.preferenceScreen
        for (i in mAvailableLanguages.indices) {
            val pref = CheckBoxPreference(this)
            val locale = mAvailableLanguages[i].locale
            pref.title = mAvailableLanguages[i].label + " [$locale]"
            val fivecode = get5Code(locale)
            val language = locale.language
            val checked = languageSelections.contains(fivecode)
            pref.setChecked(checked)
            val has4Row = arrayContains(KBD_4_ROW, fivecode) || arrayContains(KBD_4_ROW, language)
            val has5Row = arrayContains(KBD_5_ROW, fivecode) || arrayContains(KBD_5_ROW, language)
            val summaries = ArrayList<String>(3)
            if (has5Row) summaries.add("5-row")
            if (has4Row) summaries.add("4-row")
            if (hasDictionary(locale)) {
                summaries.add(resources.getString(R.string.has_dictionary))
            }
            if (summaries.isNotEmpty()) {
                val summary = StringBuilder()
                for (j in summaries.indices) {
                    if (j > 0) summary.append(", ")
                    summary.append(summaries[j])
                }
                pref.setSummary(summary.toString())
            }
            parent.addPreference(pref)
        }
    }

    private fun hasDictionary(locale: Locale): Boolean {
        val res = resources
        val conf = res.configuration
        val saveLocale = conf.locale
        var haveDictionary = false
        conf.locale = locale
        res.updateConfiguration(conf, res.displayMetrics)

        val dictionaries = LatinIME.getDictionary(res)
        var bd = BinaryDictionary(this, dictionaries, Suggest.DIC_MAIN)

        // Is the dictionary larger than a placeholder? Arbitrarily chose a lower limit of
        // 4000-5000 words, whereas the LARGE_DICTIONARY is about 20000+ words.
        if (bd.size > Suggest.LARGE_DICTIONARY_THRESHOLD / 4) {
            haveDictionary = true
        } else {
            val plug = PluginManager.getDictionary(applicationContext, locale.language)
            if (plug != null) {
                bd.close()
                bd = plug
                haveDictionary = true
            }
        }

        bd.close()
        conf.locale = saveLocale
        res.updateConfiguration(conf, res.displayMetrics)
        return haveDictionary
    }

    private fun get5Code(locale: Locale): String {
        val country = locale.country
        return (locale.language + if (TextUtils.isEmpty(country)) "" else "_$country")
    }

    override fun onResume() {
        super.onResume()
        init()
    }

    override fun onPause() {
        super.onPause()
        init()
        // Save the selected languages
        var checkedLanguages: StringBuilder? = StringBuilder()
        val parent: PreferenceGroup = fragment.preferenceScreen
        val count = parent.preferenceCount
        for (i in 0 until count) {
            val pref = parent.getPreference(i) as CheckBoxPreference
            if (pref.isChecked) {
                val locale = mAvailableLanguages[i].locale
                checkedLanguages!!.append(get5Code(locale)).append(",")
            }
        }
        if (checkedLanguages!!.isEmpty()) checkedLanguages = null // Save null
        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        val editor = sp.edit()
        editor.putString(LatinIME.PREF_SELECTED_LANGUAGES, checkedLanguages.toString())
        SharedPreferencesCompat.apply(editor)
    }

    val uniqueLocales: ArrayList<Loc>
        get() {
            val localeSet = HashSet<String>()
            val langSet = HashSet<String>()
            // Ignore the system (asset) locale list, it's inconsistent and incomplete
//        String[] sysLocales = getAssets().getLocales();
//        
//        // First, add zz_ZZ style full language+country locales
//        for (int i = 0; i < sysLocales.length; ++i) {
//        	String sl = sysLocales[i];
//        	if (sl.length() != 5) continue;
//        	localeSet.add(sl);
//        	langSet.add(sl.substring(0, 2));
//        }
//        
//        // Add entries for system languages without country, but only if there's
//        // no full locale for that language yet.
//        for (int i = 0; i < sysLocales.length; ++i) {
//        	String sl = sysLocales[i];
//        	if (sl.length() != 2 || langSet.contains(sl)) continue;
//        	localeSet.add(sl);
//        }

            // Add entries for additional languages supported by the keyboard.
            for (kl in KBD_LOCALIZATIONS) {
                if (kl.length == 2 && langSet.contains(kl)) continue
                // replace zz_rYY with zz_YY
                val kLocale =
                    if (kl.length == 6) kl.substring(0, 2) + "_" + kl.substring(4, 6)
                    else kl
                localeSet.add(kLocale)
            }
            Log.i(TAG, "localeSet=" + asString(localeSet))
            Log.i(TAG, "langSet=" + asString(langSet))

            // Now build the locale list for display
            var locales = arrayOfNulls<String>(localeSet.size)
            locales = localeSet.toArray(locales)
            Arrays.sort(locales)

            val origSize = locales.size
            val preprocess = arrayOfNulls<Loc>(origSize)
            var finalSize = 0
            for (s in locales) {
                val len = s!!.length
                if (len == 2 || len == 5 || len == 6) {
                    val language = s.substring(0, 2)
                    val l = when (len) {
                        5 -> {
                            val country = s.substring(3, 5)
                            Locale(language, country)
                        }
                        6 -> // zz_rYY
                            Locale(language, s.substring(4, 6))
                        else -> Locale(language)
                    }

                    // Exclude languages that are not relevant to LatinIME
                    if (arrayContains(BLACKLIST_LANGUAGES, language)) continue

                    if (finalSize == 0) {
                        preprocess[finalSize++] =
                            Loc(LanguageSwitcher.toTitleCase(l.getDisplayName(l)), l)
                    } else {
                        // check previous entry:
                        //  same lang and a country -> upgrade to full name and
                        //    insert ours with full name
                        //  diff lang -> insert ours with lang-only name
                        if (preprocess[finalSize - 1]!!.locale.language ==
                            language
                        ) {
                            preprocess[finalSize - 1]!!.label = getLocaleName(
                                preprocess[finalSize - 1]!!.locale
                            )
                            preprocess[finalSize++] = Loc(getLocaleName(l), l)
                        } else {
                            var displayName: String
                            if (s != "zz_ZZ") {
                                displayName = getLocaleName(l)
                                preprocess[finalSize++] = Loc(displayName, l)
                            }
                        }
                    }
                }
            }
            return ArrayList(preprocess.toList().subList(0,finalSize)).filterNotNull() as ArrayList<Loc>
        }

    private fun arrayContains(array: Array<String>, value: String): Boolean {
        for (s in array) {
            if (s.equals(value, ignoreCase = true)) return true
        }
        return false
    }

    companion object {
        private const val TAG = "PCKeyboardILS"
        private val BLACKLIST_LANGUAGES = arrayOf(
            "ko", "ja", "zh"
        )

        // Languages for which auto-caps should be disabled
        val NOCAPS_LANGUAGES = HashSet<String>()

        init {
            NOCAPS_LANGUAGES.add("ar")
            NOCAPS_LANGUAGES.add("iw")
            NOCAPS_LANGUAGES.add("th")
        }

        // Languages which should not use dead key logic. The modifier is entered after the base character.
        val NODEADKEY_LANGUAGES = HashSet<String>()

        init {
            NODEADKEY_LANGUAGES.add("ar")
            NODEADKEY_LANGUAGES.add("iw") // TODO: currently no niqqud in the keymap?
            NODEADKEY_LANGUAGES.add("th")
        }

        // Languages which should not auto-add space after completions
        val NOAUTOSPACE_LANGUAGES = HashSet<String>()

        init {
            NOAUTOSPACE_LANGUAGES.add("th")
        }

        // Run the GetLanguages.sh script to update the following lists based on
        // the available keyboard resources and dictionaries.
        private val KBD_LOCALIZATIONS = arrayOf(
            "ar", "bg", "bg_ST", "ca", "cs", "cs_QY", "da", "de", "de_NE",
            "el", "en", "en_CX", "en_DV", "en_GB", "es", "es_LA", "es_US",
            "fa", "fi", "fr", "fr_CA", "he", "hr", "hu", "hu_QY", "hy", "in",
            "it", "iw", "ja", "ka", "ko", "lo", "lt", "lv", "nb", "nl", "pl",
            "pt", "pt_PT", "rm", "ro", "ru", "ru_PH", "si", "sk", "sk_QY", "sl",
            "sr", "sv", "ta", "th", "tl", "tr", "uk", "vi", "zh_CN", "zh_TW"
        )
        private val KBD_5_ROW = arrayOf(
            "ar", "bg", "bg_ST", "cs", "cs_QY", "da", "de", "de_NE", "el",
            "en", "en_CX", "en_DV", "en_GB", "es", "es_LA", "fa", "fi", "fr",
            "fr_CA", "he", "hr", "hu", "hu_QY", "hy", "it", "iw", "lo", "lt",
            "nb", "pt_PT", "ro", "ru", "ru_PH", "si", "sk", "sk_QY", "sl",
            "sr", "sv", "ta", "th", "tr", "uk"
        )
        private val KBD_4_ROW = arrayOf(
            "ar", "bg", "bg_ST", "cs", "cs_QY", "da", "de", "de_NE", "el",
            "en", "en_CX", "en_DV", "es", "es_LA", "es_US", "fa", "fr", "fr_CA",
            "he", "hr", "hu", "hu_QY", "iw", "nb", "ru", "ru_PH", "sk", "sk_QY",
            "sl", "sr", "sv", "tr", "uk"
        )

        private fun getLocaleName(l: Locale): String {
            val lang = l.language
            val country = l.country
            return if (lang == "en" && country == "DV") {
                "English (Dvorak)"
            } else if (lang == "en" && country == "EX") {
                "English (4x11)"
            } else if (lang == "en" && country == "CX") {
                "English (Carpalx)"
            } else if (lang == "es" && country == "LA") {
                "Español (Latinoamérica)"
            } else if (lang == "cs" && country == "QY") {
                "Čeština (QWERTY)"
            } else if (lang == "de" && country == "NE") {
                "Deutsch (Neo2)"
            } else if (lang == "hu" && country == "QY") {
                "Magyar (QWERTY)"
            } else if (lang == "sk" && country == "QY") {
                "Slovenčina (QWERTY)"
            } else if (lang == "ru" && country == "PH") {
                "Русский (Phonetic)"
            } else if (lang == "bg") {
                if (country == "ST") {
                    "български език (Standard)"
                } else {
                    "български език (Phonetic)"
                }
            } else {
                LanguageSwitcher.toTitleCase(l.getDisplayName(l))
            }
        }

        private fun asString(set: Set<String>): String {
            val out = StringBuilder()
            out.append("set(")
            var parts = arrayOfNulls<String>(set.size)
            parts = (set as HashSet).toArray(parts)
            Arrays.sort(parts)
            for (i in parts.indices) {
                if (i > 0) out.append(", ")
                out.append(parts[i])
            }
            out.append(")")
            return out.toString()
        }
    }
}
