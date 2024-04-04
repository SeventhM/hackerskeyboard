package org.pocketworkstation.pckeyboard

import android.os.Bundle
import android.view.View
import android.widget.SeekBar
import android.widget.SeekBar.OnSeekBarChangeListener
import androidx.preference.PreferenceDialogFragmentCompat

class SeekBarDialog : PreferenceDialogFragmentCompat() {
    lateinit var perference: SeekBarPreference

    companion object {
        @JvmStatic
        fun newInstance(key: String, perference: SeekBarPreference): SeekBarDialog {
            val fragment = SeekBarDialog()
            val b = Bundle(1)
            b.putString(ARG_KEY, key)
            fragment.setArguments(b)
            fragment.perference = perference
            return fragment
        }
    }
    
    override fun onDialogClosed(positiveResult: Boolean) {
        if (!positiveResult) {
            perference.restoreVal()
            return
        }
        perference.saveVal()
    }

    override fun onBindDialogView(view: View) {
        perference.mSeek = view.findViewById(R.id.seekBarPref)
        perference.mMinText = view.findViewById(R.id.seekMin)
        perference.mMaxText = view.findViewById(R.id.seekMax)
        perference.mValText = view.findViewById(R.id.seekVal)
        perference.showVal()
        perference.mMinText.text = perference.formatFloatDisplay(perference.mMin)
        perference.mMaxText.text = perference.formatFloatDisplay(perference.mMax)
        perference.mSeek.progress = perference.progressVal
        perference.mSeek.setOnSeekBarChangeListener(object : OnSeekBarChangeListener {
            override fun onStopTrackingTouch(seekBar: SeekBar) {}
            override fun onStartTrackingTouch(seekBar: SeekBar) {}
            override fun onProgressChanged(seekBar: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) {
                    val newVal = perference.percentToSteppedVal(
                        progress,
                        perference.mMin,
                        perference.mMax,
                        perference.mStep,
                        perference.mLogScale
                    )
                    if (newVal != perference.mVal) {
                        perference.onChange(newVal)
                    }
                    perference.setVal(newVal)
                    perference.mSeek.progress = perference.progressVal
                }
                perference.showVal()
            }
        })
        super.onBindDialogView(view)
    }
}
