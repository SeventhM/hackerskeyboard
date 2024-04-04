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

import android.app.backup.BackupManager
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.os.Bundle
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.FragmentActivity
import androidx.preference.ListPreference
import androidx.preference.Preference
import androidx.preference.PreferenceFragmentCompat

class PrefScreenView : FragmentActivity(), OnSharedPreferenceChangeListener {
    class PrefScreenViewFragment : PreferenceFragmentCompat() {
        override fun onDisplayPreferenceDialog(preference: Preference) {
            if (preference is SeekBarPreference) {
                val dialogFragment: DialogFragment = SeekBarDialog.newInstance(preference.getKey(), preference)
                dialogFragment.setTargetFragment(this, 0)
                dialogFragment.show(getParentFragmentManager(), tag)
            } else super.onDisplayPreferenceDialog(preference)
        }

        override fun onCreatePreferences(savedInstanceState: Bundle?, rootKey: String?) {
            setPreferencesFromResource(R.xml.prefs_view, rootKey)
        }
    }

    var fragment = PrefScreenViewFragment()
    private var mRenderModePreference: ListPreference? = null
    override fun onCreate(icicle: Bundle?) {
        super.onCreate(icicle)
        fragment = PrefScreenViewFragment()
        isInit = false
        supportFragmentManager.beginTransaction().replace(android.R.id.content, fragment).commit()
    }

    var isInit = false
    protected fun init() {
        if (isInit) return
        isInit = true
        val prefs = fragment.preferenceManager.getSharedPreferences()
        prefs!!.registerOnSharedPreferenceChangeListener(this)
        mRenderModePreference = fragment.findPreference(LatinIME.PREF_RENDER_MODE)
    }

    override fun onDestroy() {
        fragment.preferenceManager.getSharedPreferences()!!
            .unregisterOnSharedPreferenceChangeListener(
                this
            )
        super.onDestroy()
    }

    override fun onSharedPreferenceChanged(prefs: SharedPreferences, key: String?) {
        init()
        BackupManager(this).dataChanged()
    }

    override fun onResume() {
        super.onResume()
        init()
        if (LatinKeyboardBaseView.sSetRenderMode == null) {
            mRenderModePreference!!.isEnabled = false
            mRenderModePreference!!.setSummary(R.string.render_mode_unavailable)
        }
    }
}
