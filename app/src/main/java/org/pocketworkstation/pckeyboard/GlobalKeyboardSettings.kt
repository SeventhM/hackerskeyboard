package org.pocketworkstation.pckeyboard

import android.content.SharedPreferences
import android.content.res.Resources
import java.util.Locale

/**
 * Global current settings for the keyboard.
 *
 *
 *
 * Yes, globals are evil. But the persisted shared preferences are global data
 * by definition, and trying to hide this by propagating the current manually
 * just adds a lot of complication. This is especially annoying due to Views
 * getting constructed in a way that doesn't support adding additional
 * constructor arguments, requiring post-construction method calls, which is
 * error-prone and fragile.
 *
 *
 *
 * The comments below indicate which class is responsible for updating the
 * value, and for recreating keyboards or views as necessary. Other classes
 * MUST treat the fields as read-only values, and MUST NOT attempt to save
 * these values or results derived from them across re-initializations.
 *
 *
 * @author klaus.weidner@gmail.com
 */
class GlobalKeyboardSettings {

    companion object {
        private const val TAG = "HK/Globals"
        const val FLAG_PREF_NONE = 0
        const val FLAG_PREF_NEED_RELOAD = 0x1
        const val FLAG_PREF_NEW_PUNC_LIST = 0x2
        const val FLAG_PREF_RECREATE_INPUT_VIEW = 0x4
        const val FLAG_PREF_RESET_KEYBOARDS = 0x8
        const val FLAG_PREF_RESET_MODE_OVERRIDE = 0x10
    }
    /* Simple prefs updated by this class */ //
    // Read by Keyboard
    var popupKeyboardFlags = 0x1
    var topRowScale = 1.0f

    //
    // Read by LatinKeyboardView
    @JvmField
    var showTouchPos = false

    //
    // Read by LatinIME
    @JvmField
    var suggestedPunctuation: String? = "!?,."
    @JvmField
    var keyboardModePortrait = 0
    @JvmField
    var keyboardModeLandscape = 2
    @JvmField
    var compactModeEnabled = true // always on
    @JvmField
    var ctrlAOverride = 0
    @JvmField
    var chordingCtrlKey = 0
    @JvmField
    var chordingAltKey = 0
    @JvmField
    var chordingMetaKey = 0
    @JvmField
    var keyClickVolume = 0.0f
    @JvmField
    var keyClickMethod = 0
    @JvmField
    var capsLock = true
    var shiftLockModifiers = false

    //
    // Read by LatinKeyboardBaseView
    var labelScalePref = 1.0f

    //
    // Read by CandidateView
    var candidateScalePref = 1.0f

    //
    // Read by PointerTracker
    var sendSlideKeys = 0

    /* Updated by LatinIME */ //
    // Read by KeyboardSwitcher
    @JvmField
    var keyboardMode = 0
    @JvmField
    var useExtension = false

    //
    // Read by LatinKeyboardView and KeyboardSwitcher
    @JvmField
    var keyboardHeightPercent = 40.0f // percent of screen height

    //
    // Read by LatinKeyboardBaseView
    @JvmField
    var hintMode = 0
    @JvmField
    var renderMode = 1

    //
    // Read by PointerTracker
    @JvmField
    var longpressTimeout = 400

    //
    // Read by LatinIMESettings
    // These are cached values for informational display, don't use for other purposes
    @JvmField
    var editorPackageName: String? = null
    @JvmField
    var editorFieldName: String? = null
    @JvmField
    var editorFieldId = 0
    @JvmField
    var editorInputType = 0

    /* Updated by KeyboardSwitcher */ //
    // Used by LatinKeyboardBaseView and LatinIME
    /* Updated by LanguageSwitcher */ //
    // Used by Keyboard and KeyboardSwitcher
    @JvmField
    var inputLocale: Locale = Locale.getDefault()

    // Auto pref implementation follows
    private val mBoolPrefs = HashMap<String, BooleanPref>()
    private val mStringPrefs = HashMap<String, StringPref>()
    private var mCurrentFlags = 0

    private interface BooleanPref {
        fun set(`val`: Boolean)
        val default: Boolean
        val flags: Int
    }

    private interface StringPref {
        fun set(`val`: String?)
        val default: String
        val flags: Int
    }

    fun initPrefs(prefs: SharedPreferences, resources: Resources) {
        addStringPref("pref_keyboard_mode_portrait", object : StringPref {
            override fun set(`val`: String?) {
                keyboardModePortrait = `val`!!.toInt()
            }

            override val default: String
                get() = resources.getString(R.string.default_keyboard_mode_portrait)
            override val flags: Int
                get() = FLAG_PREF_RESET_KEYBOARDS or FLAG_PREF_RESET_MODE_OVERRIDE
        })
        addStringPref("pref_keyboard_mode_landscape", object : StringPref {
            override fun set(`val`: String?) {
                keyboardModeLandscape = `val`!!.toInt()
            }

            override val default: String
                get() = resources.getString(R.string.default_keyboard_mode_landscape)
            override val flags: Int
                get() = FLAG_PREF_RESET_KEYBOARDS or FLAG_PREF_RESET_MODE_OVERRIDE
        })
        addStringPref("pref_slide_keys_int", object : StringPref {
            override fun set(`val`: String?) {
                sendSlideKeys = `val`!!.toInt()
            }

            override val default: String
                get() = "0"
            override val flags: Int
                get() = FLAG_PREF_NONE
        })
        addBooleanPref("pref_touch_pos", object : BooleanPref {
            override fun set(`val`: Boolean) {
                showTouchPos = `val`
            }

            override val default: Boolean
                get() = false
            override val flags: Int
                get() = FLAG_PREF_NONE
        })
        addStringPref("pref_popup_content", object : StringPref {
            override fun set(`val`: String?) {
                popupKeyboardFlags = `val`!!.toInt()
            }

            override val default: String
                get() = resources.getString(R.string.default_popup_content)
            override val flags: Int
                get() = FLAG_PREF_RESET_KEYBOARDS
        })
        addStringPref("pref_suggested_punctuation", object : StringPref {
            override fun set(`val`: String?) {
                suggestedPunctuation = `val`
            }

            override val default: String
                get() = resources.getString(R.string.suggested_punctuations_default)
            override val flags: Int
                get() = FLAG_PREF_NEW_PUNC_LIST
        })
        addStringPref("pref_label_scale_v2", object : StringPref {
            override fun set(`val`: String?) {
                labelScalePref = `val`!!.toFloat()
            }

            override val default: String
                get() = "1.0"
            override val flags: Int
                get() = FLAG_PREF_RECREATE_INPUT_VIEW
        })
        addStringPref("pref_candidate_scale", object : StringPref {
            override fun set(`val`: String?) {
                candidateScalePref = `val`!!.toFloat()
            }

            override val default: String
                get() = "1.0"
            override val flags: Int
                get() = FLAG_PREF_RESET_KEYBOARDS
        })
        addStringPref("pref_top_row_scale", object : StringPref {
            override fun set(`val`: String?) {
                topRowScale = `val`!!.toFloat()
            }

            override val default: String
                get() = "1.0"
            override val flags: Int
                get() = FLAG_PREF_RESET_KEYBOARDS
        })
        addStringPref("pref_ctrl_a_override", object : StringPref {
            override fun set(`val`: String?) {
                ctrlAOverride = `val`!!.toInt()
            }

            override val default: String
                get() = resources.getString(R.string.default_ctrl_a_override)
            override val flags: Int
                get() = FLAG_PREF_RESET_KEYBOARDS
        })
        addStringPref("pref_chording_ctrl_key", object : StringPref {
            override fun set(`val`: String?) {
                chordingCtrlKey = `val`!!.toInt()
            }

            override val default: String
                get() = resources.getString(R.string.default_chording_ctrl_key)
            override val flags: Int
                get() = FLAG_PREF_RESET_KEYBOARDS
        })
        addStringPref("pref_chording_alt_key", object : StringPref {
            override fun set(`val`: String?) {
                chordingAltKey = `val`!!.toInt()
            }

            override val default: String
                get() = resources.getString(R.string.default_chording_alt_key)
            override val flags: Int
                get() = FLAG_PREF_RESET_KEYBOARDS
        })
        addStringPref("pref_chording_meta_key", object : StringPref {
            override fun set(`val`: String?) {
                chordingMetaKey = `val`!!.toInt()
            }

            override val default: String
                get() = resources.getString(R.string.default_chording_meta_key)
            override val flags: Int
                get() = FLAG_PREF_RESET_KEYBOARDS
        })
        addStringPref("pref_click_volume", object : StringPref {
            override fun set(`val`: String?) {
                keyClickVolume = `val`!!.toFloat()
            }

            override val default: String
                get() = resources.getString(R.string.default_click_volume)
            override val flags: Int
                get() = FLAG_PREF_NONE
        })
        addStringPref("pref_click_method", object : StringPref {
            override fun set(`val`: String?) {
                keyClickMethod = `val`!!.toInt()
            }

            override val default: String
                get() = resources.getString(R.string.default_click_method)
            override val flags: Int
                get() = FLAG_PREF_NONE
        })
        addBooleanPref("pref_caps_lock", object : BooleanPref {
            override fun set(`val`: Boolean) {
                capsLock = `val`
            }

            override val default: Boolean
                get() = resources.getBoolean(R.bool.default_caps_lock)
            override val flags: Int
                get() = FLAG_PREF_NONE
        })
        addBooleanPref("pref_shift_lock_modifiers", object : BooleanPref {
            override fun set(`val`: Boolean) {
                shiftLockModifiers = `val`
            }

            override val default: Boolean
                get() = resources.getBoolean(R.bool.default_shift_lock_modifiers)
            override val flags: Int
                get() = FLAG_PREF_NONE
        })

        // Set initial values
        for (key in mBoolPrefs.keys) {
            val pref = mBoolPrefs[key]
            pref?.set(prefs.getBoolean(key, pref.default))
        }
        for (key in mStringPrefs.keys) {
            val pref = mStringPrefs[key]
            pref?.set(prefs.getString(key, pref.default))
        }
    }

    fun sharedPreferenceChanged(prefs: SharedPreferences, key: String) {
        var found = false
        mCurrentFlags = FLAG_PREF_NONE
        val bPref = mBoolPrefs[key]
        if (bPref != null) {
            found = true
            bPref.set(prefs.getBoolean(key, bPref.default))
            mCurrentFlags = mCurrentFlags or bPref.flags
        }
        val sPref = mStringPrefs[key]
        if (sPref != null) {
            found = true
            sPref.set(prefs.getString(key, sPref.default))
            mCurrentFlags = mCurrentFlags or sPref.flags
        }
        //if (!found) Log.i(TAG, "sharedPreferenceChanged: unhandled key=" + key);
    }

    fun hasFlag(flag: Int): Boolean {
        if ((mCurrentFlags and flag) != 0) {
            mCurrentFlags = mCurrentFlags and flag.inv()
            return true
        }
        return false
    }

    fun unhandledFlags(): Int {
        return mCurrentFlags
    }

    private fun addBooleanPref(key: String, setter: BooleanPref) {
        mBoolPrefs[key] = setter
    }

    private fun addStringPref(key: String, setter: StringPref) {
        mStringPrefs[key] = setter
    }
}
