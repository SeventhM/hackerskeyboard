/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.app.Dialog
import android.app.backup.BackupManager
import android.content.DialogInterface
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.os.Bundle
import android.text.AutoText
import android.text.InputType
import android.util.Log
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.preference.CheckBoxPreference
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat
import androidx.preference.PreferenceGroup

class LatinIMESettings : FragmentActivity(), OnSharedPreferenceChangeListener,
    DialogInterface.OnDismissListener {

    class LatinIMESettingsFragment : PreferenceFragmentCompat() {
        override fun onDisplayPreferenceDialog(preference: Preference) {
            if (preference is SeekBarPreference) {
                val dialogFragment: DialogFragment = SeekBarDialog.newInstance(preference.getKey(), preference)
                dialogFragment.setTargetFragment(this, 0)
                dialogFragment.show(getParentFragmentManager(), tag)
            } else super.onDisplayPreferenceDialog(preference)
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs, rootKey)
        }
    }

    var fragment = LatinIMESettingsFragment()
    private var mQuickFixes: CheckBoxPreference? = null
    private var mVoicePreference: ListPreference? = null
    private var mSettingsKeyPreference: ListPreference? = null
    private var mKeyboardModePortraitPreference: ListPreference? = null
    private var mKeyboardModeLandscapePreference: ListPreference? = null
    private var mInputConnectionInfo: Preference? = null
    private var mLabelVersion: Preference? = null
    private var mVoiceOn = false
    private var mOkClicked = false
    private var mVoiceModeOff: String? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        isInit = false
        fragment = LatinIMESettingsFragment()
        supportFragmentManager.beginTransaction().replace(android.R.id.content, fragment).commit()
    }

    protected var isInit = false
    protected fun init() {
        if (isInit) return
        isInit = true
        mQuickFixes = fragment.findPreference(QUICK_FIXES_KEY)
        mVoicePreference = fragment.findPreference(VOICE_SETTINGS_KEY)
        mSettingsKeyPreference = fragment.findPreference(PREF_SETTINGS_KEY)
        mInputConnectionInfo = fragment.findPreference(INPUT_CONNECTION_INFO)
        mLabelVersion = fragment.findPreference("label_version")


        // TODO(klausw): remove these when no longer needed
        mKeyboardModePortraitPreference = fragment.findPreference("pref_keyboard_mode_portrait")
        mKeyboardModeLandscapePreference = fragment.findPreference("pref_keyboard_mode_landscape")

        //SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        //prefs.registerOnSharedPreferenceChangeListener(this);
        val prefs = fragment.preferenceManager.getSharedPreferences()
        prefs!!.registerOnSharedPreferenceChangeListener(this)

        mVoiceModeOff = getString(R.string.voice_mode_off)
        mVoiceOn = prefs.getString(VOICE_SETTINGS_KEY, mVoiceModeOff) != mVoiceModeOff
    }

    override fun onResume() {
        super.onResume()
        init()
        val autoTextSize = AutoText.getSize(fragment.listView)
        if (autoTextSize < 1) {
            (fragment.findPreference<Preference>(PREDICTION_SETTINGS_KEY) as PreferenceGroup?)!!.removePreference(
                mQuickFixes!!
            )
        }

        Log.i(TAG, "compactModeEnabled=" + LatinIME.sKeyboardSettings.compactModeEnabled)
        if (!LatinIME.sKeyboardSettings.compactModeEnabled) {
            val oldEntries = mKeyboardModePortraitPreference!!.entries
            val oldValues = mKeyboardModePortraitPreference!!.entryValues

            if (oldEntries.size > 2) {
                val newEntries = arrayOf(oldEntries[0], oldEntries[2])
                val newValues = arrayOf(oldValues[0], oldValues[2])
                mKeyboardModePortraitPreference!!.entries = newEntries
                mKeyboardModePortraitPreference!!.entryValues = newValues
                mKeyboardModeLandscapePreference!!.entries = newEntries
                mKeyboardModeLandscapePreference!!.entryValues = newValues
            }
        }

        updateSummaries()

        var version = ""
        try {
            val info = packageManager.getPackageInfo(packageName, PackageManager.GET_SIGNATURES)
            version = info.versionName
            var isOfficial = false
            for (sig in info.signatures) {
                val b = sig.toByteArray()
                var out = 0
                for (i in b.indices) {
                    val pos = i % 4
                    out = out xor (b[i].toInt() shl pos * 4)
                }
                if (out == -466825) {
                    isOfficial = true
                }
                //version += " [" + Integer.toHexString(out) + "]";
            }
            version += if (isOfficial) " official" else " custom"
        } catch (e: PackageManager.NameNotFoundException) {
            Log.e(TAG, "Could not find version info.")
        }
        mLabelVersion!!.setSummary(version)
    }

    override fun onDestroy() {
        fragment.preferenceManager.getSharedPreferences()!!
            .unregisterOnSharedPreferenceChangeListener(this)
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        init()
        BackupManager(this).dataChanged()
        // If turning on voice input, show dialog
        if (key == VOICE_SETTINGS_KEY && !mVoiceOn) {
            if (prefs.getString(VOICE_SETTINGS_KEY, mVoiceModeOff) != mVoiceModeOff) {
                showVoiceConfirmation()
            }
        }
        mVoiceOn = prefs.getString(VOICE_SETTINGS_KEY, mVoiceModeOff) != mVoiceModeOff
        updateVoiceModeSummary()
        updateSummaries()
    }

    private fun updateSummaries() {
        init()
        val res = resources
        mSettingsKeyPreference!!.setSummary(
            res.getStringArray(R.array.settings_key_modes)[mSettingsKeyPreference!!.findIndexOfValue(
                mSettingsKeyPreference!!.value)]
        )
        mInputConnectionInfo!!.setSummary(
            String.format(
                "%s type=%s",
                LatinIME.sKeyboardSettings.editorPackageName,
                inputTypeDesc(LatinIME.sKeyboardSettings.editorInputType)
            )
        )
    }

    private fun showVoiceConfirmation() {
        init()
        mOkClicked = false
        showDialog(VOICE_INPUT_CONFIRM_DIALOG)
    }

    private fun updateVoiceModeSummary() {
        init()
        mVoicePreference!!.setSummary(
            resources.getStringArray(R.array.voice_input_modes_summary)[mVoicePreference!!.findIndexOfValue(
                mVoicePreference!!.value
            )]
        )
    }

    @Deprecated("Deprecated in Java")
    override fun onCreateDialog(id: Int): Dialog? {
        Log.e(TAG, "unknown dialog $id")
        return null
    }

    override fun onDismiss(dialog: DialogInterface) {
        if (!mOkClicked) {
            // This assumes that onPreferenceClick gets called first, and this if the user
            // agreed after the warning, we set the mOkClicked value to true.
            mVoicePreference!!.setValue(mVoiceModeOff)
        }
    }

    private fun updateVoicePreference() {}

    companion object {
        private const val QUICK_FIXES_KEY = "quick_fixes"
        private const val PREDICTION_SETTINGS_KEY = "prediction_settings"
        private const val VOICE_SETTINGS_KEY = "voice_mode"

        /* package */
        const val PREF_SETTINGS_KEY = "settings_key"
        const val INPUT_CONNECTION_INFO = "input_connection_info"
        private const val TAG = "LatinIMESettings"

        // Dialog ids
        private const val VOICE_INPUT_CONFIRM_DIALOG = 0
        var INPUT_CLASSES = HashMap<Int, String>()
        var DATETIME_VARIATIONS = HashMap<Int, String>()
        var TEXT_VARIATIONS = HashMap<Int, String>()
        var NUMBER_VARIATIONS = HashMap<Int, String>()

        init {
            INPUT_CLASSES[0x00000004] = "DATETIME"
            INPUT_CLASSES[0x00000002] = "NUMBER"
            INPUT_CLASSES[0x00000003] = "PHONE"
            INPUT_CLASSES[0x00000001] = "TEXT"
            INPUT_CLASSES[0x00000000] = "NULL"
            DATETIME_VARIATIONS[0x00000010] = "DATE"
            DATETIME_VARIATIONS[0x00000020] = "TIME"
            NUMBER_VARIATIONS[0x00000010] = "PASSWORD"
            TEXT_VARIATIONS[0x00000020] = "EMAIL_ADDRESS"
            TEXT_VARIATIONS[0x00000030] = "EMAIL_SUBJECT"
            TEXT_VARIATIONS[0x000000b0] = "FILTER"
            TEXT_VARIATIONS[0x00000050] = "LONG_MESSAGE"
            TEXT_VARIATIONS[0x00000080] = "PASSWORD"
            TEXT_VARIATIONS[0x00000060] = "PERSON_NAME"
            TEXT_VARIATIONS[0x000000c0] = "PHONETIC"
            TEXT_VARIATIONS[0x00000070] = "POSTAL_ADDRESS"
            TEXT_VARIATIONS[0x00000040] = "SHORT_MESSAGE"
            TEXT_VARIATIONS[0x00000010] = "URI"
            TEXT_VARIATIONS[0x00000090] = "VISIBLE_PASSWORD"
            TEXT_VARIATIONS[0x000000a0] = "WEB_EDIT_TEXT"
            TEXT_VARIATIONS[0x000000d0] = "WEB_EMAIL_ADDRESS"
            TEXT_VARIATIONS[0x000000e0] = "WEB_PASSWORD"
        }

        private fun addBit(buf: StringBuffer, bit: Int, str: String) {
            if (bit != 0) {
                buf.append("|")
                buf.append(str)
            }
        }

        private fun inputTypeDesc(type: Int): String {
            val cls = type and 0x0000000f // MASK_CLASS
            val flags = type and 0x00fff000 // MASK_FLAGS
            val `var` = type and 0x00000ff0 // MASK_VARIATION

            val out = StringBuffer()
            val clsName = INPUT_CLASSES[cls]
            out.append(clsName ?: "?")

            if (cls == InputType.TYPE_CLASS_TEXT) {
                val varName = TEXT_VARIATIONS[`var`]
                if (varName != null) {
                    out.append(".")
                    out.append(varName)
                }
                addBit(out, flags and 0x00010000, "AUTO_COMPLETE")
                addBit(out, flags and 0x00008000, "AUTO_CORRECT")
                addBit(out, flags and 0x00001000, "CAP_CHARACTERS")
                addBit(out, flags and 0x00004000, "CAP_SENTENCES")
                addBit(out, flags and 0x00002000, "CAP_WORDS")
                addBit(out, flags and 0x00040000, "IME_MULTI_LINE")
                addBit(out, flags and 0x00020000, "MULTI_LINE")
                addBit(out, flags and 0x00080000, "NO_SUGGESTIONS")
            } else if (cls == InputType.TYPE_CLASS_NUMBER) {
                val varName = NUMBER_VARIATIONS[`var`]
                if (varName != null) {
                    out.append(".")
                    out.append(varName)
                }
                addBit(out, flags and 0x00002000, "DECIMAL")
                addBit(out, flags and 0x00001000, "SIGNED")
            } else if (cls == InputType.TYPE_CLASS_DATETIME) {
                val varName = DATETIME_VARIATIONS[`var`]
                if (varName != null) {
                    out.append(".")
                    out.append(varName)
                }
            }
            return out.toString()
        }
    }
}