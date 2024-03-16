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

import android.app.Dialog;
import android.app.backup.BackupManager;
import android.content.DialogInterface;
import android.content.SharedPreferences;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.content.res.Resources;
import android.os.Bundle;
import android.text.AutoText;
import android.text.InputType;
import android.util.Log;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.fragment.app.DialogFragment;
import androidx.fragment.app.FragmentActivity;
import androidx.preference.CheckBoxPreference;
import androidx.preference.ListPreference;
import androidx.preference.Preference;
import androidx.preference.PreferenceFragmentCompat;
import androidx.preference.PreferenceGroup;

import java.util.HashMap;
import java.util.Map;

public class LatinIMESettings extends FragmentActivity
        implements SharedPreferences.OnSharedPreferenceChangeListener,
        DialogInterface.OnDismissListener {

    public static class LatinIMESettingsFragment extends PreferenceFragmentCompat {

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
            setPreferencesFromResource(R.xml.prefs, rootKey);
        }
    }
    LatinIMESettingsFragment fragment = new LatinIMESettingsFragment();

    private static final String QUICK_FIXES_KEY = "quick_fixes";
    private static final String PREDICTION_SETTINGS_KEY = "prediction_settings";
    private static final String VOICE_SETTINGS_KEY = "voice_mode";
    /* package */ static final String PREF_SETTINGS_KEY = "settings_key";
    static final String INPUT_CONNECTION_INFO = "input_connection_info";

    private static final String TAG = "LatinIMESettings";

    // Dialog ids
    private static final int VOICE_INPUT_CONFIRM_DIALOG = 0;

    private CheckBoxPreference mQuickFixes;
    private ListPreference mVoicePreference;
    private ListPreference mSettingsKeyPreference;
    private ListPreference mKeyboardModePortraitPreference;
    private ListPreference mKeyboardModeLandscapePreference;
    private Preference mInputConnectionInfo;
    private Preference mLabelVersion;

    protected boolean isInit = false;

    private boolean mVoiceOn;

    private boolean mOkClicked = false;
    private String mVoiceModeOff;

    @Override
    protected void onCreate(Bundle icicle) {
        super.onCreate(icicle);
        isInit = false;
        fragment = new LatinIMESettingsFragment();
        getSupportFragmentManager().beginTransaction().replace(android.R.id.content, fragment).commit();
    }

    protected void init() {
        isInit = true;
        mQuickFixes = fragment.findPreference(QUICK_FIXES_KEY);
        mVoicePreference = fragment.findPreference(VOICE_SETTINGS_KEY);
        mSettingsKeyPreference = fragment.findPreference(PREF_SETTINGS_KEY);
        mInputConnectionInfo = fragment.findPreference(INPUT_CONNECTION_INFO);
        mLabelVersion = fragment.findPreference("label_version");


        // TODO(klausw): remove these when no longer needed
        mKeyboardModePortraitPreference = fragment.findPreference("pref_keyboard_mode_portrait");
        mKeyboardModeLandscapePreference = fragment.findPreference("pref_keyboard_mode_landscape");

        //SharedPreferences prefs = PreferenceManager.getDefaultSharedPreferences(this);
        //prefs.registerOnSharedPreferenceChangeListener(this);

        SharedPreferences prefs = fragment.getPreferenceManager().getSharedPreferences();
        prefs.registerOnSharedPreferenceChangeListener(this);


        mVoiceModeOff = getString(R.string.voice_mode_off);
        mVoiceOn = !(prefs.getString(VOICE_SETTINGS_KEY, mVoiceModeOff).equals(mVoiceModeOff));
    }

    @Override
    protected void onResume() {
        super.onResume();
        if (!isInit) init();
        int autoTextSize = AutoText.getSize(fragment.getListView());
        if (autoTextSize < 1) {
            ((PreferenceGroup) fragment.findPreference(PREDICTION_SETTINGS_KEY)).removePreference(mQuickFixes);
        }

        Log.i(TAG, "compactModeEnabled=" + LatinIME.sKeyboardSettings.compactModeEnabled);
        if (!LatinIME.sKeyboardSettings.compactModeEnabled) {
            CharSequence[] oldEntries = mKeyboardModePortraitPreference.getEntries();
            CharSequence[] oldValues = mKeyboardModePortraitPreference.getEntryValues();

            if (oldEntries.length > 2) {
                CharSequence[] newEntries = new CharSequence[] { oldEntries[0], oldEntries[2] };
                CharSequence[] newValues = new CharSequence[] { oldValues[0], oldValues[2] };
                mKeyboardModePortraitPreference.setEntries(newEntries);
                mKeyboardModePortraitPreference.setEntryValues(newValues);
                mKeyboardModeLandscapePreference.setEntries(newEntries);
                mKeyboardModeLandscapePreference.setEntryValues(newValues);
            }
        }

        updateSummaries();

        String version = "";
        try {
            PackageInfo info = getPackageManager().getPackageInfo(getPackageName(), PackageManager.GET_SIGNATURES);
            version = info.versionName;
            boolean isOfficial = false;
            for (Signature sig : info.signatures) {
                byte[] b = sig.toByteArray();
                int out = 0;
                for (int i = 0; i < b.length; ++i) {
                    int pos = i % 4;
                    out ^= b[i] << (pos * 4);
                }
                if (out == -466825) {
                    isOfficial = true;
                }
                //version += " [" + Integer.toHexString(out) + "]";
            }
            version += isOfficial ? " official" : " custom";
        } catch (PackageManager.NameNotFoundException e) {
            Log.e(TAG, "Could not find version info.");
        }

        mLabelVersion.setSummary(version);
    }

    @Override
    protected void onDestroy() {
        fragment.getPreferenceManager().getSharedPreferences().unregisterOnSharedPreferenceChangeListener(
                this);
        super.onDestroy();
    }

    public void onSharedPreferenceChanged(SharedPreferences prefs, String key) {
        if (!isInit) init();
        (new BackupManager(this)).dataChanged();
        // If turning on voice input, show dialog
        if (key.equals(VOICE_SETTINGS_KEY) && !mVoiceOn) {
            if (!prefs.getString(VOICE_SETTINGS_KEY, mVoiceModeOff)
                    .equals(mVoiceModeOff)) {
                showVoiceConfirmation();
            }
        }
        mVoiceOn = !(prefs.getString(VOICE_SETTINGS_KEY, mVoiceModeOff).equals(mVoiceModeOff));
        updateVoiceModeSummary();
        updateSummaries();
    }

    static Map<Integer, String> INPUT_CLASSES = new HashMap<>();
    static Map<Integer, String> DATETIME_VARIATIONS = new HashMap<>();
    static Map<Integer, String> TEXT_VARIATIONS = new HashMap<>();
    static Map<Integer, String> NUMBER_VARIATIONS = new HashMap<>();
    static {
        INPUT_CLASSES.put(0x00000004, "DATETIME");
        INPUT_CLASSES.put(0x00000002, "NUMBER");
        INPUT_CLASSES.put(0x00000003, "PHONE");
        INPUT_CLASSES.put(0x00000001, "TEXT");
        INPUT_CLASSES.put(0x00000000, "NULL");

        DATETIME_VARIATIONS.put(0x00000010, "DATE");
        DATETIME_VARIATIONS.put(0x00000020, "TIME");

        NUMBER_VARIATIONS.put(0x00000010, "PASSWORD");

        TEXT_VARIATIONS.put(0x00000020, "EMAIL_ADDRESS");
        TEXT_VARIATIONS.put(0x00000030, "EMAIL_SUBJECT");
        TEXT_VARIATIONS.put(0x000000b0, "FILTER");
        TEXT_VARIATIONS.put(0x00000050, "LONG_MESSAGE");
        TEXT_VARIATIONS.put(0x00000080, "PASSWORD");
        TEXT_VARIATIONS.put(0x00000060, "PERSON_NAME");
        TEXT_VARIATIONS.put(0x000000c0, "PHONETIC");
        TEXT_VARIATIONS.put(0x00000070, "POSTAL_ADDRESS");
        TEXT_VARIATIONS.put(0x00000040, "SHORT_MESSAGE");
        TEXT_VARIATIONS.put(0x00000010, "URI");
        TEXT_VARIATIONS.put(0x00000090, "VISIBLE_PASSWORD");
        TEXT_VARIATIONS.put(0x000000a0, "WEB_EDIT_TEXT");
        TEXT_VARIATIONS.put(0x000000d0, "WEB_EMAIL_ADDRESS");
        TEXT_VARIATIONS.put(0x000000e0, "WEB_PASSWORD");

    }

    private static void addBit(StringBuffer buf, int bit, String str) {
        if (bit != 0) {
            buf.append("|");
            buf.append(str);
        }
    }

    private static String inputTypeDesc(int type) {
        int cls = type & 0x0000000f; // MASK_CLASS
        int flags = type & 0x00fff000; // MASK_FLAGS
        int var = type &  0x00000ff0; // MASK_VARIATION

        StringBuffer out = new StringBuffer();
        String clsName = INPUT_CLASSES.get(cls);
        out.append(clsName != null ? clsName : "?");

        if (cls == InputType.TYPE_CLASS_TEXT) {
            String varName = TEXT_VARIATIONS.get(var);
            if (varName != null) {
                out.append(".");
                out.append(varName);
            }
            addBit(out, flags & 0x00010000, "AUTO_COMPLETE");
            addBit(out, flags & 0x00008000, "AUTO_CORRECT");
            addBit(out, flags & 0x00001000, "CAP_CHARACTERS");
            addBit(out, flags & 0x00004000, "CAP_SENTENCES");
            addBit(out, flags & 0x00002000, "CAP_WORDS");
            addBit(out, flags & 0x00040000, "IME_MULTI_LINE");
            addBit(out, flags & 0x00020000, "MULTI_LINE");
            addBit(out, flags & 0x00080000, "NO_SUGGESTIONS");
        } else if (cls == InputType.TYPE_CLASS_NUMBER) {
            String varName = NUMBER_VARIATIONS.get(var);
            if (varName != null) {
                out.append(".");
                out.append(varName);
            }
            addBit(out, flags & 0x00002000, "DECIMAL");
            addBit(out, flags & 0x00001000, "SIGNED");
        } else if (cls == InputType.TYPE_CLASS_DATETIME) {
            String varName = DATETIME_VARIATIONS.get(var);
            if (varName != null) {
                out.append(".");
                out.append(varName);
            }
        }
        return out.toString();
    }

    private void updateSummaries() {
        Resources res = getResources();
        mSettingsKeyPreference.setSummary(
                res.getStringArray(R.array.settings_key_modes)
                        [mSettingsKeyPreference.findIndexOfValue(mSettingsKeyPreference.getValue())]);

        mInputConnectionInfo.setSummary(String.format("%s type=%s",
                LatinIME.sKeyboardSettings.editorPackageName,
                inputTypeDesc(LatinIME.sKeyboardSettings.editorInputType)
        ));
    }

    private void showVoiceConfirmation() {
        mOkClicked = false;
        showDialog(VOICE_INPUT_CONFIRM_DIALOG);
    }

    private void updateVoiceModeSummary() {
        mVoicePreference.setSummary(
                getResources().getStringArray(R.array.voice_input_modes_summary)
                        [mVoicePreference.findIndexOfValue(mVoicePreference.getValue())]);
    }

    @Override
    protected Dialog onCreateDialog(int id) {
        Log.e(TAG, "unknown dialog " + id);
        return null;
    }

    public void onDismiss(DialogInterface dialog) {
        if (!isInit) init();
        if (!mOkClicked) {
            // This assumes that onPreferenceClick gets called first, and this if the user
            // agreed after the warning, we set the mOkClicked value to true.
            mVoicePreference.setValue(mVoiceModeOff);
        }
    }

    private void updateVoicePreference() {
    }
}