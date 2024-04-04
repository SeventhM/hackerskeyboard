/**
 *
 */
package org.pocketworkstation.pckeyboard

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import androidx.preference.ListPreference

class AutoSummaryListPreference : ListPreference {
    constructor(context: Context?) : super(context!!)
    constructor(context: Context?, attrs: AttributeSet?) : super(
        context!!, attrs
    )

    private fun trySetSummary() {
        var entry: CharSequence? = null
        try {
            entry = getEntry()
        } catch (e: ArrayIndexOutOfBoundsException) {
            Log.i(TAG, "Malfunctioning ListPreference, can't get entry")
        }
        if (entry != null) {
            //String percent = getResources().getString(R.string.percent);
            val percent = "percent"
            setSummary(entry.toString().replace("%", " $percent"))
        }
    }

    override fun setEntries(entries: Array<CharSequence>) {
        super.setEntries(entries)
        trySetSummary()
    }

    override fun setEntryValues(entryValues: Array<CharSequence>) {
        super.setEntryValues(entryValues)
        trySetSummary()
    }

    override fun setValue(value: String) {
        super.setValue(value)
        trySetSummary()
    }

    companion object {
        private const val TAG = "HK/AutoSummaryListPref"
    }
}
