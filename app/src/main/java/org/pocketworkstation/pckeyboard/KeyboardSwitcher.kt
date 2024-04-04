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

import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.os.Build
import android.util.Log
import android.view.InflateException
import androidx.preference.PreferenceManager
import java.lang.ref.SoftReference

class KeyboardSwitcher private constructor() : OnSharedPreferenceChangeListener {
    private var mInputView: LatinKeyboardView? = null

    private lateinit var mInputMethodService: LatinIME
    private var mSymbolsId: KeyboardId? = null
    private var mSymbolsShiftedId: KeyboardId? = null
    private var mCurrentId: KeyboardId? = null
    private val mKeyboards = HashMap<KeyboardId?, SoftReference<LatinKeyboard>>()
    private var mMode = MODE_NONE


    /** One of the MODE_XXX values  */
    private var mImeOptions = 0
    private var mIsSymbols = false
    private var mFullMode = 0

    /**
     * mIsAutoCompletionActive indicates that auto completed word will be input
     * instead of what user actually typed.
     */
    private var mIsAutoCompletionActive = false
    private var mHasVoice = false
    private var mVoiceOnPrimary = false
    private var mPreferSymbols = false
    private var mAutoModeSwitchState = AUTO_MODE_SWITCH_STATE_ALPHA

    // Indicates whether or not we have the settings key
    private var mHasSettingsKey = false
    private var mLastDisplayWidth = 0
    private var mLanguageSwitcher: LanguageSwitcher? = null
    private var mLayoutId = 0

    /**
     * Sets the input locale, when there are multiple locales for input. If no
     * locale switching is required, then the locale should be set to null.
     *
     * @param languageSwitcher the current input locale, or null for default locale with no
     * locale button.
     */
    fun setLanguageSwitcher(languageSwitcher: LanguageSwitcher) {
        mLanguageSwitcher = languageSwitcher
        languageSwitcher.inputLocale // for side effect
    }

    private fun makeSymbolsId(hasVoice: Boolean): KeyboardId {
        if (mFullMode == 1) {
            return KeyboardId(KBD_COMPACT_FN, KEYBOARDMODE_SYMBOLS, true, hasVoice)
        } else if (mFullMode == 2) {
            return KeyboardId(KBD_FULL_FN, KEYBOARDMODE_SYMBOLS, true, hasVoice)
        }
        return KeyboardId(
            KBD_SYMBOLS,
            if (mHasSettingsKey) KEYBOARDMODE_SYMBOLS_WITH_SETTINGS_KEY else KEYBOARDMODE_SYMBOLS,
            false,
            hasVoice
        )
    }

    private fun makeSymbolsShiftedId(hasVoice: Boolean): KeyboardId? {
        return if (mFullMode > 0) null else KeyboardId(
            KBD_SYMBOLS_SHIFT,
            if (mHasSettingsKey) KEYBOARDMODE_SYMBOLS_WITH_SETTINGS_KEY else KEYBOARDMODE_SYMBOLS,
            false,
            hasVoice
        )
    }

    fun makeKeyboards(forceCreate: Boolean) {
        mFullMode = LatinIME.sKeyboardSettings.keyboardMode
        mSymbolsId = makeSymbolsId(mHasVoice && !mVoiceOnPrimary)
        mSymbolsShiftedId = makeSymbolsShiftedId(mHasVoice && !mVoiceOnPrimary)

        if (forceCreate) mKeyboards.clear()
        // Configuration change is coming after the keyboard gets recreated. So
        // don't rely on that.
        // If keyboards have already been made, check if we have a screen width
        // change and
        // create the keyboard layouts again at the correct orientation
        val displayWidth = mInputMethodService.getMaxWidth()
        if (displayWidth == mLastDisplayWidth) return
        mLastDisplayWidth = displayWidth
        if (!forceCreate) mKeyboards.clear()
    }

    /**
     * Represents the parameters necessary to construct a new LatinKeyboard,
     * which also serve as a unique identifier for each keyboard type.
     */
    private class KeyboardId( // TODO: should have locale and portrait/landscape orientation?
    ) {
        var mKeyboardMode:Int = 0
        var mXml: Int = 0
        var mHasVoice: Boolean = false

        /** A KEYBOARDMODE_XXX value  */
        var  mEnableShiftLock:Boolean = false
        var mKeyboardHeightPercent: Float = 0.0f
        var mUsingExtension: Boolean = false
        private var mHashCode: Int = 0

        constructor(xml: Int, mode:Int, enableShiftLock: Boolean, hasVoice: Boolean ) : this() {
            mXml = xml
            mKeyboardMode = mode
            mEnableShiftLock = enableShiftLock
            mHasVoice = hasVoice
            mKeyboardHeightPercent = LatinIME.sKeyboardSettings.keyboardHeightPercent
            mUsingExtension = LatinIME.sKeyboardSettings.useExtension
            mHashCode = arrayOf<Any>(xml, mode, enableShiftLock, hasVoice).contentHashCode()
        }

        override fun equals(other: Any?): Boolean {
            return other is KeyboardId && equals(other as KeyboardId?)
        }

        private fun equals(other: KeyboardId?): Boolean {
            return other != null &&
                    other.mXml == mXml &&
                    other.mKeyboardMode == mKeyboardMode &&
                    other.mUsingExtension == mUsingExtension &&
                    other.mEnableShiftLock == mEnableShiftLock &&
                    other.mHasVoice == mHasVoice
        }

        override fun hashCode(): Int {
            return mHashCode
        }
    }

    fun setVoiceMode(enableVoice: Boolean, voiceOnPrimary: Boolean) {
        if (enableVoice != mHasVoice || voiceOnPrimary != mVoiceOnPrimary) {
            mKeyboards.clear()
        }
        mHasVoice = enableVoice
        mVoiceOnPrimary = voiceOnPrimary
        setKeyboardMode(mMode, mImeOptions, mHasVoice, mIsSymbols)
    }

    private fun hasVoiceButton(isSymbols: Boolean): Boolean {
        return mHasVoice && isSymbols != mVoiceOnPrimary
    }

    fun setKeyboardMode(mode: Int, imeOptions: Int, enableVoice: Boolean) {
        var mode = mode
        mAutoModeSwitchState = AUTO_MODE_SWITCH_STATE_ALPHA
        mPreferSymbols = mode == MODE_SYMBOLS
        if (mode == MODE_SYMBOLS) {
            mode = MODE_TEXT
        }
        try {
            setKeyboardMode(mode, imeOptions, enableVoice, mPreferSymbols)
        } catch (e: RuntimeException) {
            Log.e(
                TAG, "Got exception: ${mode},${imeOptions},"
                        + "$mPreferSymbols msg=${e.message}"
            )
        }
    }

    private fun setKeyboardMode(
        mode: Int, imeOptions: Int, enableVoice: Boolean,
        isSymbols: Boolean
    ) {
        if (mInputView == null) return
        mMode = mode
        mImeOptions = imeOptions
        if (enableVoice != mHasVoice) {
            // TODO clean up this unnecessary recursive call.
            setVoiceMode(enableVoice, mVoiceOnPrimary)
        }
        mIsSymbols = isSymbols

        mInputView!!.isPreviewEnabled = mInputMethodService.popupOn

        val id = getKeyboardId(mode, imeOptions, isSymbols)
        val keyboard: LatinKeyboard = getKeyboard(id)

        if (mode == MODE_PHONE) {
            mInputView!!.setPhoneKeyboard(keyboard)
        }

        mCurrentId = id
        mInputView!!.keyboard =keyboard
        keyboard.setShiftState(Keyboard.SHIFT_OFF)
        keyboard.setImeOptions(mInputMethodService.resources, mMode, imeOptions)
        keyboard.updateSymbolIcons(mIsAutoCompletionActive)
    }

    private fun getKeyboard(id: KeyboardId?): LatinKeyboard {
        val ref = mKeyboards[id]
        var keyboard = ref?.get()
        if (keyboard == null) {
            val orig = mInputMethodService.resources
            val conf = orig.configuration
            val saveLocale = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                conf.locales.get(0)
            else conf.locale
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                conf.setLocale(LatinIME.sKeyboardSettings.inputLocale)
            else conf.locale = LatinIME.sKeyboardSettings.inputLocale
            orig.updateConfiguration(conf, null)
            keyboard = LatinKeyboard(
                mInputMethodService, id!!.mXml,
                id.mKeyboardMode, id.mKeyboardHeightPercent
            )
            keyboard.setVoiceMode(hasVoiceButton(id.mXml == R.xml.kbd_symbols), mHasVoice)
            keyboard.setLanguageSwitcher(mLanguageSwitcher, mIsAutoCompletionActive)
//            if (isFullMode()) {
//                keyboard.setExtension(new LatinKeyboard(mInputMethodService,
//                        R.xml.kbd_extension_full, 0, id.mRowHeightPercent));
//            } else if (isAlphabetMode()) { // TODO: not in full keyboard mode? Per-mode extension kbd?
//                keyboard.setExtension(new LatinKeyboard(mInputMethodService,
//                        R.xml.kbd_extension, 0, id.mRowHeightPercent));
//            }
            if (id.mEnableShiftLock) {
                keyboard.enableShiftLock()
            }
            mKeyboards[id] = SoftReference(keyboard)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                conf.setLocale(saveLocale)
            else conf.locale = saveLocale
            orig.updateConfiguration(conf, null)
        }
        return keyboard
    }

    val isFullMode get() = mFullMode > 0

    private fun getKeyboardId(mode: Int, imeOptions: Int, isSymbols: Boolean): KeyboardId? {
        val hasVoice = hasVoiceButton(isSymbols)
        if (mFullMode > 0) {
            when (mode) {
                MODE_TEXT, MODE_URL, MODE_EMAIL, MODE_IM, MODE_WEB -> return KeyboardId(
                    if (mFullMode == 1) KBD_COMPACT else KBD_FULL,
                    KEYBOARDMODE_NORMAL,
                    true,
                    hasVoice
                )
            }
        }
        // TODO: generalize for any KeyboardId
        val keyboardRowsResId = KBD_QWERTY
        if (isSymbols) {
            return if (mode == MODE_PHONE) {
                KeyboardId(
                    KBD_PHONE_SYMBOLS,
                    0,
                    false,
                    hasVoice
                )
            } else {
                KeyboardId(
                    KBD_SYMBOLS,
                    if (mHasSettingsKey) KEYBOARDMODE_SYMBOLS_WITH_SETTINGS_KEY else KEYBOARDMODE_SYMBOLS,
                    false,
                    hasVoice
                )
            }
        }
        when (mode) {
            MODE_NONE, MODE_TEXT -> return KeyboardId(
                keyboardRowsResId,
                if (mHasSettingsKey) KEYBOARDMODE_NORMAL_WITH_SETTINGS_KEY else KEYBOARDMODE_NORMAL,
                true,
                hasVoice
            )

            MODE_SYMBOLS -> return KeyboardId(
                KBD_SYMBOLS,
                if (mHasSettingsKey) KEYBOARDMODE_SYMBOLS_WITH_SETTINGS_KEY else KEYBOARDMODE_SYMBOLS,
                false,
                hasVoice
            )

            MODE_PHONE -> return KeyboardId(KBD_PHONE, 0, false, hasVoice)
            MODE_URL -> return KeyboardId(
                keyboardRowsResId,
                if (mHasSettingsKey) KEYBOARDMODE_URL_WITH_SETTINGS_KEY else KEYBOARDMODE_URL,
                true,
                hasVoice
            )

            MODE_EMAIL -> return KeyboardId(
                keyboardRowsResId,
                if (mHasSettingsKey) KEYBOARDMODE_EMAIL_WITH_SETTINGS_KEY else KEYBOARDMODE_EMAIL,
                true,
                hasVoice
            )

            MODE_IM -> return KeyboardId(
                keyboardRowsResId,
                if (mHasSettingsKey) KEYBOARDMODE_IM_WITH_SETTINGS_KEY else KEYBOARDMODE_IM,
                true,
                hasVoice
            )

            MODE_WEB -> return KeyboardId(
                keyboardRowsResId,
                if (mHasSettingsKey) KEYBOARDMODE_WEB_WITH_SETTINGS_KEY else KEYBOARDMODE_WEB,
                true,
                hasVoice
            )
        }
        return null
    }

    val keyboardMode get() = mMode

    val isAlphabetMode: Boolean
        get() {
            if (mCurrentId == null) {
                return false
            }
            val currentMode = mCurrentId!!.mKeyboardMode
            if (mFullMode > 0 && currentMode == KEYBOARDMODE_NORMAL) return true
            for (mode in ALPHABET_MODES) {
                if (currentMode == mode) {
                    return true
                }
            }
            return false
        }

    fun setShiftState(shiftState: Int) {
        if (inputView != null) {
            inputView!!.setShiftState(shiftState)
        }
    }

    fun setFn(useFn: Boolean) {
        if (inputView == null) return
        val oldShiftState = inputView!!.shiftState
        if (useFn) {
            val kbd = getKeyboard(mSymbolsId)
            kbd.enableShiftLock()
            mCurrentId = mSymbolsId
            mInputView!!.keyboard = kbd
            mInputView!!.setShiftState(oldShiftState)
        } else {
            // Return to default keyboard state
            setKeyboardMode(mMode, mImeOptions, mHasVoice, false)
            mInputView!!.setShiftState(oldShiftState)
        }
    }

    fun setCtrlIndicator(active: Boolean) {
        if (mInputView == null) return
        mInputView!!.setCtrlIndicator(active)
    }

    fun setAltIndicator(active: Boolean) {
        if (mInputView == null) return
        mInputView!!.setAltIndicator(active)
    }

    fun setMetaIndicator(active: Boolean) {
        if (mInputView == null) return
        mInputView!!.setMetaIndicator(active)
    }

    fun toggleShift() {
        //Log.i(TAG, "toggleShift isAlphabetMode=" + isAlphabetMode() + " mSettings.fullMode=" + mSettings.fullMode);
        if (isAlphabetMode) return
        if (mFullMode > 0) {
            val shifted = mInputView!!.isShiftAll
            mInputView!!.setShiftState(if (shifted) Keyboard.SHIFT_OFF else Keyboard.SHIFT_ON)
            return
        }
        if (mCurrentId!! == mSymbolsId
            || mCurrentId!! != mSymbolsShiftedId
        ) {
            val symbolsShiftedKeyboard = getKeyboard(mSymbolsShiftedId)
            mCurrentId = mSymbolsShiftedId
            mInputView!!.keyboard = symbolsShiftedKeyboard
            // Symbol shifted keyboard has a ALT_SYM key that has a caps lock style indicator.
            // To enable the indicator, we need to set the shift state appropriately.
            symbolsShiftedKeyboard.enableShiftLock()
            symbolsShiftedKeyboard.setShiftState(Keyboard.SHIFT_LOCKED)
            symbolsShiftedKeyboard.setImeOptions(
                mInputMethodService.resources, mMode, mImeOptions
            )
        } else {
            val symbolsKeyboard = getKeyboard(mSymbolsId)
            mCurrentId = mSymbolsId
            mInputView!!.keyboard = symbolsKeyboard
            symbolsKeyboard.enableShiftLock()
            symbolsKeyboard.setShiftState(Keyboard.SHIFT_OFF)
            symbolsKeyboard.setImeOptions(
                mInputMethodService.resources, mMode, mImeOptions
            )
        }
    }

    fun onCancelInput() {
        // Snap back to the previous keyboard mode if the user cancels sliding
        // input.
        if (mAutoModeSwitchState == AUTO_MODE_SWITCH_STATE_MOMENTARY && pointerCount == 1)
            mInputMethodService.changeKeyboardMode()
    }

    fun toggleSymbols() {
        setKeyboardMode(mMode, mImeOptions, mHasVoice, !mIsSymbols)
        mAutoModeSwitchState = if (mIsSymbols && !mPreferSymbols) {
            AUTO_MODE_SWITCH_STATE_SYMBOL_BEGIN
        } else {
            AUTO_MODE_SWITCH_STATE_ALPHA
        }
    }

    fun hasDistinctMultitouch(): Boolean {
        return mInputView != null && mInputView!!.hasDistinctMultitouch()
    }

    fun setAutoModeSwitchStateMomentary() {
        mAutoModeSwitchState = AUTO_MODE_SWITCH_STATE_MOMENTARY
    }

    val isInMomentaryAutoModeSwitchState
        get() = mAutoModeSwitchState == AUTO_MODE_SWITCH_STATE_MOMENTARY
    val isInChordingAutoModeSwitchState
        get() = mAutoModeSwitchState == AUTO_MODE_SWITCH_STATE_CHORDING
    val isVibrateAndSoundFeedbackRequired
        get() = mInputView != null && !mInputView!!.isInSlidingKeyInput
    private val pointerCount
        get() = if (mInputView == null) 0 else mInputView!!.pointerCount

    /**
     * Updates state machine to figure out when to automatically snap back to
     * the previous mode.
     */
    fun onKey(key: Int) {
        // Switch back to alpha mode if user types one or more non-space/enter
        // characters
        // followed by a space/enter
        when (mAutoModeSwitchState) {
            AUTO_MODE_SWITCH_STATE_MOMENTARY ->
                // Only distinct multi touch devices can be in this state.
                // On non-distinct multi touch devices, mode change key is handled
                // by {@link onKey},
                // not by {@link onPress} and {@link onRelease}. So, on such
                // devices,
                // {@link mAutoModeSwitchState} starts from {@link
                // AUTO_MODE_SWITCH_STATE_SYMBOL_BEGIN},
                // or {@link AUTO_MODE_SWITCH_STATE_ALPHA}, not from
                //{@link AUTO_MODE_SWITCH_STATE_MOMENTARY}.
                if (key == Keyboard.KEYCODE_MODE_CHANGE) {
                    // Detected only the mode change key has been pressed, and then
                    // released.
                    mAutoModeSwitchState = if (mIsSymbols) {
                        AUTO_MODE_SWITCH_STATE_SYMBOL_BEGIN
                    } else {
                        AUTO_MODE_SWITCH_STATE_ALPHA
                    }
                } else if (pointerCount == 1) {
                    // Snap back to the previous keyboard mode if the user pressed
                    // the mode change key
                    // and slid to other key, then released the finger.
                    // If the user cancels the sliding input, snapping back to the
                    // previous keyboard
                    // mode is handled by {@link #onCancelInput}.
                    mInputMethodService.changeKeyboardMode()
                } else {
                    // Chording input is being started. The keyboard mode will be
                    // snapped back to the
                    // previous mode in {@link onReleaseSymbol} when the mode change
                    // key is released.
                    mAutoModeSwitchState = AUTO_MODE_SWITCH_STATE_CHORDING
                }

            AUTO_MODE_SWITCH_STATE_SYMBOL_BEGIN ->
                if (key != LatinIME.ASCII_SPACE && key != LatinIME.ASCII_ENTER && key >= 0) {
                mAutoModeSwitchState = AUTO_MODE_SWITCH_STATE_SYMBOL
            }

            AUTO_MODE_SWITCH_STATE_SYMBOL ->
                // Snap back to alpha keyboard mode if user types one or more
                // non-space/enter
                // characters followed by a space/enter.
                if (key == LatinIME.ASCII_ENTER || key == LatinIME.ASCII_SPACE) {
                    mInputMethodService.changeKeyboardMode()
                }
        }
    }

    val inputView get() = mInputView

    fun recreateInputView() {
        changeLatinKeyboardView(mLayoutId, true)
    }

    private fun changeLatinKeyboardView(newLayout: Int, forceReset: Boolean) {
        var newLayout = newLayout
        if (mLayoutId != newLayout || mInputView == null || forceReset) {
            if (mInputView != null) {
                mInputView!!.closing()
            }
            if (THEMES.size <= newLayout) {
                newLayout = DEFAULT_LAYOUT_ID.toInt()
            }
            LatinIMEUtil.GCUtils.instance.reset()
            var tryGC = true
            var i = 0
            while (i < LatinIMEUtil.GCUtils.GC_TRY_LOOP_MAX && tryGC) {
                try {
                    mInputView = mInputMethodService.layoutInflater
                        ?.inflate(THEMES[newLayout], null)
                            as LatinKeyboardView
                    tryGC = false
                } catch (e: Throwable) {
                    when(e) {
                        is OutOfMemoryError, is InflateException -> {
                            tryGC = LatinIMEUtil.GCUtils.instance.tryGCOrWait(
                                "$mLayoutId,$newLayout", e
                            )
                        }
                        else -> throw e
                    }
                }
                ++i
            }
            mInputView!!.setExtensionLayoutResId(THEMES[newLayout])
            mInputView!!.onKeyboardActionListener = mInputMethodService
            mInputView!!.setPadding(0, 0, 0, 0)
            mLayoutId = newLayout
        }
        mInputMethodService.mHandler.post {
            if (mInputView != null) {
                mInputMethodService.setInputView(mInputView)
            }
            mInputMethodService.updateInputViewShown()
        }
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences,
        key: String?
    ) {
        if (PREF_KEYBOARD_LAYOUT == key) {
            changeLatinKeyboardView(
                sharedPreferences.getString(key, DEFAULT_LAYOUT_ID)!!.toInt(), true)
        } else if (LatinIMESettings.PREF_SETTINGS_KEY == key) {
            updateSettingsKeyState(sharedPreferences)
            recreateInputView()
        }
    }

    fun onAutoCompletionStateChanged(isAutoCompletion: Boolean) {
        if (isAutoCompletion != mIsAutoCompletionActive) {
            val keyboardView = inputView
            mIsAutoCompletionActive = isAutoCompletion
            keyboardView!!.invalidateKey(
                (keyboardView.keyboard as LatinKeyboard)
                    .onAutoCompletionStateChanged(isAutoCompletion)
            )
        }
    }

    private fun updateSettingsKeyState(prefs: SharedPreferences) {
        val resources = mInputMethodService.resources
        val settingsKeyMode = prefs.getString(
            LatinIMESettings.PREF_SETTINGS_KEY,
            resources.getString(DEFAULT_SETTINGS_KEY_MODE)
        )
        // We show the settings key when 1) SETTINGS_KEY_MODE_ALWAYS_SHOW or
        // 2) SETTINGS_KEY_MODE_AUTO and there are two or more enabled IMEs on
        // the system
        mHasSettingsKey = settingsKeyMode == resources.getString(SETTINGS_KEY_MODE_ALWAYS_SHOW)
                || settingsKeyMode == resources.getString(SETTINGS_KEY_MODE_AUTO)
    }

    companion object {
        private const val TAG = "PCKeyboardKbSw"
        const val MODE_NONE = 0
        const val MODE_TEXT = 1
        const val MODE_SYMBOLS = 2
        const val MODE_PHONE = 3
        const val MODE_URL = 4
        const val MODE_EMAIL = 5
        const val MODE_IM = 6
        const val MODE_WEB = 7

        // Main keyboard layouts without the settings key
        val KEYBOARDMODE_NORMAL = R.id.mode_normal
        val KEYBOARDMODE_URL = R.id.mode_url
        val KEYBOARDMODE_EMAIL = R.id.mode_email
        val KEYBOARDMODE_IM = R.id.mode_im
        val KEYBOARDMODE_WEB = R.id.mode_webentry

        // Main keyboard layouts with the settings key
        val KEYBOARDMODE_NORMAL_WITH_SETTINGS_KEY = R.id.mode_normal_with_settings_key
        val KEYBOARDMODE_URL_WITH_SETTINGS_KEY = R.id.mode_url_with_settings_key
        val KEYBOARDMODE_EMAIL_WITH_SETTINGS_KEY = R.id.mode_email_with_settings_key
        val KEYBOARDMODE_IM_WITH_SETTINGS_KEY = R.id.mode_im_with_settings_key
        val KEYBOARDMODE_WEB_WITH_SETTINGS_KEY = R.id.mode_webentry_with_settings_key

        // Symbols keyboard layout without the settings key
        val KEYBOARDMODE_SYMBOLS = R.id.mode_symbols

        // Symbols keyboard layout with the settings key
        val KEYBOARDMODE_SYMBOLS_WITH_SETTINGS_KEY = R.id.mode_symbols_with_settings_key
        val DEFAULT_LAYOUT_ID = "0"
        val PREF_KEYBOARD_LAYOUT = "pref_keyboard_layout"
        private val THEMES = intArrayOf(
            R.layout.input_ics,
            R.layout.input_gingerbread,
            R.layout.input_stone_bold,
            R.layout.input_trans_neon,
            R.layout.input_material_dark,
            R.layout.input_material_light,
            R.layout.input_ics_darker,
            R.layout.input_material_black
        )

        // Tables which contains resource ids for each character theme color
        private val KBD_PHONE = R.xml.kbd_phone
        private val KBD_PHONE_SYMBOLS = R.xml.kbd_phone_symbols
        private val KBD_SYMBOLS = R.xml.kbd_symbols
        private val KBD_SYMBOLS_SHIFT = R.xml.kbd_symbols_shift
        private val KBD_QWERTY = R.xml.kbd_qwerty
        private val KBD_FULL = R.xml.kbd_full
        private val KBD_FULL_FN = R.xml.kbd_full_fn
        private val KBD_COMPACT = R.xml.kbd_compact
        private val KBD_COMPACT_FN = R.xml.kbd_compact_fn
        private val ALPHABET_MODES = intArrayOf(
            KEYBOARDMODE_NORMAL,
            KEYBOARDMODE_URL, KEYBOARDMODE_EMAIL, KEYBOARDMODE_IM,
            KEYBOARDMODE_WEB, KEYBOARDMODE_NORMAL_WITH_SETTINGS_KEY,
            KEYBOARDMODE_URL_WITH_SETTINGS_KEY,
            KEYBOARDMODE_EMAIL_WITH_SETTINGS_KEY,
            KEYBOARDMODE_IM_WITH_SETTINGS_KEY,
            KEYBOARDMODE_WEB_WITH_SETTINGS_KEY
        )
        private const val AUTO_MODE_SWITCH_STATE_ALPHA = 0
        private const val AUTO_MODE_SWITCH_STATE_SYMBOL_BEGIN = 1
        private const val AUTO_MODE_SWITCH_STATE_SYMBOL = 2

        // The following states are used only on the distinct multi-touch panel
        // devices.
        private val AUTO_MODE_SWITCH_STATE_MOMENTARY = 3
        private val AUTO_MODE_SWITCH_STATE_CHORDING = 4
        private val SETTINGS_KEY_MODE_AUTO = R.string.settings_key_mode_auto
        private val SETTINGS_KEY_MODE_ALWAYS_SHOW = R.string.settings_key_mode_always_show

        // NOTE: No need to have SETTINGS_KEY_MODE_ALWAYS_HIDE here because it's not
        // being referred to
        // in the source code now.
        // Default is SETTINGS_KEY_MODE_AUTO.
        private val DEFAULT_SETTINGS_KEY_MODE = SETTINGS_KEY_MODE_AUTO

        val instance = KeyboardSwitcher()

        @JvmStatic
        fun init(ims: LatinIME) {
            instance.mInputMethodService = ims
            val prefs = PreferenceManager
                .getDefaultSharedPreferences(ims)
            instance.mLayoutId = prefs.getString(
                PREF_KEYBOARD_LAYOUT,
                DEFAULT_LAYOUT_ID
            )!!.toInt()
            instance.updateSettingsKeyState(prefs)
            prefs.registerOnSharedPreferenceChangeListener(instance)
            instance.mSymbolsId = instance.makeSymbolsId(false)
            instance.mSymbolsShiftedId = instance.makeSymbolsShiftedId(false)
        }
    }
}
