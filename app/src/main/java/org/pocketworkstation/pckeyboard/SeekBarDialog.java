package org.pocketworkstation.pckeyboard;

import android.os.Bundle;
import android.view.View;
import android.widget.SeekBar;

import androidx.preference.PreferenceDialogFragmentCompat;

public class SeekBarDialog extends PreferenceDialogFragmentCompat {

    SeekBarPreference perference;

    public static SeekBarDialog newInstance(String key, SeekBarPreference perference) {
        final SeekBarDialog fragment = new SeekBarDialog();
        final Bundle b = new Bundle(1);
        b.putString(ARG_KEY, key);
        fragment.setArguments(b);
        fragment.perference = perference;
        return fragment;
    }


    @Override
    public void onDialogClosed(boolean positiveResult) {
        if (!positiveResult) {
            perference.restoreVal();
            return;
        }

        perference.saveVal();
    }

    @Override
    protected void onBindDialogView(View view) {
        perference.mSeek = view.findViewById(R.id.seekBarPref);
        perference.mMinText = view.findViewById(R.id.seekMin);
        perference.mMaxText = view.findViewById(R.id.seekMax);
        perference.mValText = view.findViewById(R.id.seekVal);

        perference.showVal();
        perference.mMinText.setText(perference.formatFloatDisplay(perference.mMin));
        perference.mMaxText.setText(perference.formatFloatDisplay(perference.mMax));
        perference.mSeek.setProgress(perference.getProgressVal());

        perference.mSeek.setOnSeekBarChangeListener(new SeekBar.OnSeekBarChangeListener() {
            public void onStopTrackingTouch(SeekBar seekBar) {}
            public void onStartTrackingTouch(SeekBar seekBar) {}
            public void onProgressChanged(SeekBar seekBar, int progress, boolean fromUser) {
                if (fromUser) {
                    float newVal = perference.percentToSteppedVal(
                        progress,
                        perference.mMin,
                        perference.mMax,
                        perference.mStep,
                        perference.mLogScale
                    );
                    if (newVal != perference.mVal) {
                        perference.onChange(newVal);
                    }
                    perference.setVal(newVal);
                    perference.mSeek.setProgress(perference.getProgressVal());
                }
                perference.showVal();
            }
        });

        super.onBindDialogView(view);
    }

}
