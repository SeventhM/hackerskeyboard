package org.pocketworkstation.pckeyboard

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import java.util.regex.Pattern

/**
 * Variant of SeekBarPreference that stores values as string preferences.
 *
 *
 * This is for compatibility with existing preferences, switching types
 * leads to runtime errors when upgrading or downgrading.
 */
open class SeekBarPreferenceString(context: Context, attrs: AttributeSet?) :
    SeekBarPreference(context, attrs) {

    companion object {
        private val FLOAT_RE = Pattern.compile("(\\d+\\.?\\d*).*")
    }

    init {
        init(context, attrs)
    }

    // Some saved preferences from old versions have " ms" or "%" suffix, remove that.
    private fun floatFromString(pref: String?): Float {
        val num = FLOAT_RE.matcher(pref.toString())
        return if (!num.matches()) 0.0f else num.group(1).toFloat()
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Float {
        return floatFromString(a.getString(index))
    }

    @Deprecated("Deprecated in Java")
    override fun onSetInitialValue(restorePersistedValue: Boolean, defaultValue: Any) {
        if (restorePersistedValue) {
            setVal(floatFromString(getPersistedString("0.0")))
        } else {
            setVal(defaultValue as Float)
        }
        savePrevVal()
    }

    override fun saveVal() {
        if (shouldPersist()) {
            savePrevVal()
            persistString(getValString)
        }
        notifyChanged()
    }
}