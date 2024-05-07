package org.pocketworkstation.pckeyboard

import android.content.Context
import android.content.res.TypedArray
import android.util.AttributeSet
import android.widget.SeekBar
import android.widget.TextView
import androidx.preference.DialogPreference
import java.util.Locale
import kotlin.math.exp
import kotlin.math.ln

/**
 * SeekBarPreference provides a dialog for editing float-valued preferences with a slider.
 */
open class SeekBarPreference(context: Context, attrs: AttributeSet?) :
    DialogPreference(context, attrs) {
    lateinit var mMinText: TextView
    lateinit var mMaxText: TextView
    lateinit var mValText: TextView
    lateinit var mSeek: SeekBar
    var mMin = 0f
    var mMax = 0f
    var mVal = 0f
    private var mPrevVal = 0f
    var mStep = 0f
    private var mAsPercent = false
    var mLogScale = false
    private var mDisplayFormat: String? = null

    init {
        init(context, attrs)
    }

    protected fun init(context: Context, attrs: AttributeSet?) {
        dialogLayoutResource = R.layout.seek_bar_dialog
        val a = context.obtainStyledAttributes(attrs, R.styleable.SeekBarPreference)
        mMin = a.getFloat(R.styleable.SeekBarPreference_minValue, 0.0f)
        mMax = a.getFloat(R.styleable.SeekBarPreference_maxValue, 100.0f)
        mStep = a.getFloat(R.styleable.SeekBarPreference_step, 0.0f)
        mAsPercent = a.getBoolean(R.styleable.SeekBarPreference_asPercent, false)
        mLogScale = a.getBoolean(R.styleable.SeekBarPreference_logScale, false)
        mDisplayFormat = a.getString(R.styleable.SeekBarPreference_displayFormat)
        a.recycle()
    }

    override fun onGetDefaultValue(a: TypedArray, index: Int): Float? {
        return a.getFloat(index, 0.0f)
    }

    @Deprecated("Deprecated in Java")
    override fun onSetInitialValue(restorePersistedValue: Boolean, defaultValue: Any?) {
        if (restorePersistedValue) {
            setVal(getPersistedFloat(0.0f))
        } else {
            setVal(defaultValue as Float)
        }
        savePrevVal()
    }

    fun formatFloatDisplay(`val`: Float): String {
        // Use current locale for format, this is for display only.
        if (mAsPercent) {
            return String.format("%d%%", (`val` * 100).toInt())
        }
        return if (mDisplayFormat != null) {
            String.format(mDisplayFormat!!, `val`)
        } else {
            `val`.toString()
        }
    }

    fun showVal() {
        mValText.text = formatFloatDisplay(mVal)
    }

    fun setVal(`val`: Float) {
        mVal = `val`
    }

    protected fun savePrevVal() {
        mPrevVal = mVal
    }

    fun restoreVal() {
        mVal = mPrevVal
    }

    protected val getValString get() =  mVal.toString()

    fun percentToSteppedVal(
        percent: Int,
        min: Float,
        max: Float,
        step: Float,
        logScale: Boolean
    ): Float {
        var `val`: Float
        if (logScale) {
            `val` = exp(
                percentToSteppedVal(
                    percent,
                    ln(min),
                    ln(max),
                    step,
                    false
                ))
        } else {
            var delta = percent * (max - min) / 100
            if (step != 0.0f) {
                delta = Math.round(delta / step) * step
            }
            `val` = min + delta
        }
        // Hack: Round number to 2 significant digits so that it looks nicer.
        `val` = String.format(Locale.US, "%.2g", `val`).toFloat()
        return `val`
    }

    private fun getPercent(`val`: Float, min: Float, max: Float): Int {
        return (100 * (`val` - min) / (max - min)).toInt()
    }

    val progressVal: Int
        get() = if (mLogScale) {
            getPercent(ln(mVal), ln(mMin), ln(mMax))
        } else {
            getPercent(mVal, mMin, mMax)
        }

    open fun onChange(`val`: Float) {
        // override in subclasses
    }

    override fun getSummary(): CharSequence {
        return formatFloatDisplay(mVal)
    }

    open fun saveVal() {
        if (shouldPersist()) {
            persistFloat(mVal)
            savePrevVal()
        }
        notifyChanged()
    }
}