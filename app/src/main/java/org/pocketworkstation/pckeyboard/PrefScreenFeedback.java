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

package org.pocketworkstation.pckeyboard;

import android.app.backup.BackupManager;
import android.content.SharedPreferences;
import android.os.Bundle;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;

public class PrefScreenFeedback extends FragmentActivity
    implements SharedPreferences.OnSharedPreferenceChangeListener {

    public static class PrefScreenFeedbackFragment extends PreferenceFragmentCompat {

        @Override
        public void onDisplayPreferenceDialog(@NonNull Preference preference) {
            if (preference instanceof SeekBarPreference) {
                DialogFragment dialogFragment = SeekBarDialog.newInstance(preference.getKey(), (SeekBarPreference) preference);
                dialogFragment.setTargetFragment(this, 0);
                dialogFragment.show(getParentFragmentManager(), getTag());
            } else super.onDisplayPreferenceDialog(preference);
        }

        @Override
        public void onCreatePreferences(@Nullable Bundle savedInstanceState, @Nullable String rootKey) {
            setPreferencesFromResource(R.xml.prefs_feedback, rootKey);
        }
    }
    PrefScreenFeedbackFragment fragment = new PrefScreenFeedbackFragment();

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        fragment = new PrefScreenFeedbackFragment();
        isinit = false;
        getSupportFragmentManager().beginTransaction().replace(android.R.id.content, fragment).commit();
    }
    boolean isinit = false;

    protected void init() {
        if (isinit) return;
        isinit = true;
        SharedPreferences prefs = fragment.getPreferenceManager().getSharedPreferences();
        prefs.registerOnSharedPreferenceChangeListener(this);
    }

    @Override
    protected void onDestroy() {
        fragment.getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(
            this);
        super.onDestroy();
    }

    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        init();
        (new BackupManager(this)).dataChanged();
    }

    @Override
    protected void onResume() {
        super.onResume();
        init();
    }
}
