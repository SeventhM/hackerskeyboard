/*
 * Copyright (C) 2010 Google Inc.
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

import android.content.SharedPreferences
import android.text.TextUtils
import androidx.preference.PreferenceManager
import org.pocketworkstation.pckeyboard.DeprecatedExtensions.depLocale
import java.util.Locale

/**
 * Keeps track of list of selected input languages and the current
 * input language that the user has selected.
 */
class LanguageSwitcher(private val mIme: LatinIME) {
    private var mLocales: Array<Locale?> = arrayOfNulls(0)
    private var mSelectedLanguageArray: Array<String> = emptyArray()
    private var mSelectedLanguages: String? = null
    private var mCurrentIndex = 0
    private var mDefaultInputLanguage: String? = null
    private var mDefaultInputLocale: Locale? = null


    var systemLocale: Locale? = null
        /**
         * Returns the system locale.
         * @return the system locale
         */
        get() = field
        /**
         * Sets the system locale (display UI) used for comparing with the input language.
         * @param locale the locale of the system
         */
        set(locale) { field = locale }

    val locales get() = mLocales

    val localeCount get() = mLocales.size

    /**
     * Loads the currently selected input languages from shared preferences.
     * @param sp
     * @return whether there was any change
     */
    fun loadLocales(sp: SharedPreferences): Boolean {
        val selectedLanguages = sp.getString(LatinIME.PREF_SELECTED_LANGUAGES, null)
        val currentLanguage = sp.getString(LatinIME.PREF_INPUT_LANGUAGE, null)
        if (selectedLanguages.isNullOrEmpty()) {
            loadDefaults()
            if (mLocales.isEmpty()) {
                return false
            }
            mLocales = arrayOfNulls(0)
            return true
        }
        if (selectedLanguages == mSelectedLanguages) {
            return false
        }
        mSelectedLanguageArray =
            selectedLanguages.split(",".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()
        mSelectedLanguages = selectedLanguages // Cache it for comparison later
        constructLocales()
        mCurrentIndex = 0
        if (currentLanguage != null) {
            // Find the index
            for (i in mLocales.indices) {
                if (mSelectedLanguageArray[i] == currentLanguage) {
                    mCurrentIndex = i
                    break
                }
            }
            // If we didn't find the index, use the first one
        }
        return true
    }

    private fun loadDefaults() {
        mDefaultInputLocale = mIme.resources.configuration.depLocale
        val country = mDefaultInputLocale!!.country
        mDefaultInputLanguage = mDefaultInputLocale!!.language +
                if (TextUtils.isEmpty(country)) "" else "_$country"
    }

    private fun constructLocales() {
        mLocales = arrayOfNulls(mSelectedLanguageArray.size)
        for (i in mLocales.indices) {
            val lang = mSelectedLanguageArray[i]
            mLocales[i] = Locale(
                lang.substring(0, 2),
                if (lang.length > 4) lang.substring(3, 5) else ""
            )
        }
    }

    val inputLanguage: String?
        /**
         * Returns the currently selected input language code, or the display language code if
         * no specific locale was selected for input.
         */
        get() = if (localeCount == 0) mDefaultInputLanguage else mSelectedLanguageArray[mCurrentIndex]

    fun allowAutoCap(): Boolean {
        var lang = inputLanguage
        if (lang!!.length > 2) lang = lang.substring(0, 2)
        return !InputLanguageSelection.NOCAPS_LANGUAGES.contains(lang)
    }

    fun allowDeadKeys(): Boolean {
        var lang = inputLanguage
        if (lang!!.length > 2) lang = lang.substring(0, 2)
        return !InputLanguageSelection.NODEADKEY_LANGUAGES.contains(lang)
    }

    fun allowAutoSpace(): Boolean {
        var lang = inputLanguage
        if (lang!!.length > 2) lang = lang.substring(0, 2)
        return !InputLanguageSelection.NOAUTOSPACE_LANGUAGES.contains(lang)
    }

    /**
     * Returns the list of enabled language codes.
     */
    val enabledLanguages get() = mSelectedLanguageArray

    val inputLocale: Locale?
        /**
         * Returns the currently selected input locale, or the display locale if no specific
         * locale was selected for input.
         * @return
         */
        get() {
            val locale = if (localeCount == 0) {
                mDefaultInputLocale
            } else {
                mLocales[mCurrentIndex]
            }
            LatinIME.sKeyboardSettings.inputLocale = locale ?: Locale.getDefault()
            return locale
        }

    val nextInputLocale: Locale?
        /**
         * Returns the next input locale in the list. Wraps around to the beginning of the
         * list if we're at the end of the list.
         * @return
         */
        get() = if (localeCount == 0) mDefaultInputLocale
        else mLocales[(mCurrentIndex + 1) % mLocales.size]

    val prevInputLocale: Locale?
        /**
         * Returns the previous input locale in the list. Wraps around to the end of the
         * list if we're at the beginning of the list.
         * @return
         */
        get() = if (localeCount == 0) mDefaultInputLocale else
            mLocales[(mCurrentIndex - 1 + mLocales.size) % mLocales.size]

    fun reset() {
        mCurrentIndex = 0
        mSelectedLanguages = ""
        loadLocales(PreferenceManager.getDefaultSharedPreferences(mIme))
    }

    operator fun next() {
        mCurrentIndex++
        if (mCurrentIndex >= mLocales.size) mCurrentIndex = 0 // Wrap around
    }

    fun prev() {
        mCurrentIndex--
        if (mCurrentIndex < 0) mCurrentIndex = mLocales.size - 1 // Wrap around
    }

    fun persist() {
        val sp = PreferenceManager.getDefaultSharedPreferences(mIme)
        val editor = sp.edit()
        editor.putString(LatinIME.PREF_INPUT_LANGUAGE, inputLanguage)
        SharedPreferencesCompat.apply(editor)
    }

    companion object {
        private const val TAG = "HK/LanguageSwitcher"

        fun toTitleCase(s: String): String {
            return if (s.isEmpty()) s
            else s[0].uppercaseChar().toString() + s.substring(1)
        }
    }
}
