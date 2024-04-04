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

import android.content.Context
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorFilter
import android.graphics.Paint
import android.graphics.Paint.Align
import android.graphics.PixelFormat
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.drawable.BitmapDrawable
import android.graphics.drawable.Drawable
import android.text.TextPaint
import android.util.Log
import android.view.ViewConfiguration
import android.view.inputmethod.EditorInfo
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

class LatinKeyboard @JvmOverloads constructor(
    context: Context,
    xmlLayoutResId: Int,
    mode: Int = 0,
    kbHeightPercent: Float = 0f
) : Keyboard(context, 0, xmlLayoutResId, mode, kbHeightPercent) {
    private val mShiftLockIcon: Drawable?
    private val mShiftLockPreviewIcon: Drawable?
    private var mOldShiftIcon: Drawable? = null
    private val mSpaceIcon: Drawable?
    private val mSpaceAutoCompletionIndicator: Drawable?
    private val mSpacePreviewIcon: Drawable?
    private val mMicIcon: Drawable?
    private val mMicPreviewIcon: Drawable?
    private val mSettingsIcon: Drawable?
    private val mSettingsPreviewIcon: Drawable?
    private val m123MicIcon: Drawable?
    private val m123MicPreviewIcon: Drawable?
    private val mButtonArrowLeftIcon: Drawable?
    private val mButtonArrowRightIcon: Drawable?
    private var mShiftKey: Key? = null
    private var mEnterKey: Key? = null
    private var mF1Key: Key? = null
    private val mHintIcon: Drawable?
    private var mSpaceKey: Key? = null
    private var m123Key: Key? = null
    private val mSpaceKeyIndexArray: IntArray
    private var mSpaceDragStartX = 0
    private var mSpaceDragLastDiff = 0
    private var mLocale: Locale? = null
    private var mLanguageSwitcher: LanguageSwitcher? = null
    private val mRes: Resources
    private val mContext: Context
    private var mMode: Int

    // Whether this keyboard has voice icon on it
    private var mHasVoiceButton = false

    // Whether voice icon is enabled at all
    private var mVoiceEnabled = false
    private val mIsAlphaKeyboard: Boolean
    private val mIsAlphaFullKeyboard: Boolean
    private val mIsFnFullKeyboard: Boolean
    private var m123Label: CharSequence? = null
    private var mCurrentlyInSpace = false
    private var mSlidingLocaleIcon: SlidingLocaleDrawable? = null
    private var mPrefLetterFrequencies: IntArray? = null
    private var mPrefLetter = 0
    private var mPrefLetterX = 0
    private var mPrefLetterY = 0
    private var mPrefDistance = 0
    private val mExtensionResId = 0

    // TODO: remove this attribute when either Keyboard.mDefaultVerticalGap or Key.parent becomes
    // non-private.
    private val mVerticalGap: Int
    private var mExtensionKeyboard: LatinKeyboard? = null

    init {
        val res = context.resources
        //Log.i("PCKeyboard", "keyHeight=" + this.getKeyHeight());
        //this.setKeyHeight(30); // is useless, see http://code.google.com/p/android/issues/detail?id=4532
        mContext = context
        mMode = mode
        mRes = res
        mShiftLockIcon = ContextCompat.getDrawable(context, R.drawable.sym_keyboard_shift_locked)
        mShiftLockPreviewIcon =
            ContextCompat.getDrawable(context, R.drawable.sym_keyboard_feedback_shift_locked)
        setDefaultBounds(mShiftLockPreviewIcon)
        mSpaceIcon = ContextCompat.getDrawable(context, R.drawable.sym_keyboard_space)
        mSpaceAutoCompletionIndicator =
            ContextCompat.getDrawable(context, R.drawable.sym_keyboard_space_led)
        mSpacePreviewIcon =
            ContextCompat.getDrawable(context, R.drawable.sym_keyboard_feedback_space)
        mMicIcon = ContextCompat.getDrawable(context, R.drawable.sym_keyboard_mic)
        mMicPreviewIcon = ContextCompat.getDrawable(context, R.drawable.sym_keyboard_feedback_mic)
        mSettingsIcon = ContextCompat.getDrawable(context, R.drawable.sym_keyboard_settings)
        mSettingsPreviewIcon =
            ContextCompat.getDrawable(context, R.drawable.sym_keyboard_feedback_settings)
        setDefaultBounds(mMicPreviewIcon)
        mButtonArrowLeftIcon =
            ContextCompat.getDrawable(context, R.drawable.sym_keyboard_language_arrows_left)
        mButtonArrowRightIcon =
            ContextCompat.getDrawable(context, R.drawable.sym_keyboard_language_arrows_right)
        m123MicIcon = ContextCompat.getDrawable(context, R.drawable.sym_keyboard_123_mic)
        m123MicPreviewIcon =
            ContextCompat.getDrawable(context, R.drawable.sym_keyboard_feedback_123_mic)
        mHintIcon = ContextCompat.getDrawable(context, R.drawable.hint_popup)
        setDefaultBounds(m123MicPreviewIcon)
        sSpacebarVerticalCorrection = res.getDimensionPixelOffset(
            R.dimen.spacebar_vertical_correction
        )
        mIsAlphaKeyboard = xmlLayoutResId == R.xml.kbd_qwerty
        mIsAlphaFullKeyboard = xmlLayoutResId == R.xml.kbd_full
        mIsFnFullKeyboard =
            xmlLayoutResId == R.xml.kbd_full_fn || xmlLayoutResId == R.xml.kbd_compact_fn
        // The index of space key is available only after Keyboard constructor has finished.
        mSpaceKeyIndexArray = intArrayOf(indexOf(LatinIME.ASCII_SPACE))
        // TODO remove this initialization after cleanup
        mVerticalGap = super.verticalGap
    }

    override fun createKeyFromXml(
        res: Resources, parent: Row?, x: Int, y: Int,
        parser: XmlResourceParser?
    ): Key {
        val key: Key = LatinKey(res, parent, x, y, parser)
        if (key.codes == null) return key
        when (key.codes!![0]) {
            LatinIME.ASCII_ENTER -> mEnterKey = key
            LatinKeyboardView.KEYCODE_F1 -> mF1Key = key
            LatinIME.ASCII_SPACE -> mSpaceKey = key
            KEYCODE_MODE_CHANGE -> {
                m123Key = key
                m123Label = key.label
            }
        }
        return key
    }

    fun setImeOptions(res: Resources, mode: Int, options: Int) {
        mMode = mode
        // TODO should clean up this method
        if (mEnterKey != null) {
            // Reset some of the rarely used attributes.
            mEnterKey!!.popupCharacters = null
            mEnterKey!!.popupResId = 0
            mEnterKey!!.text = null
            when (options and (EditorInfo.IME_MASK_ACTION or EditorInfo.IME_FLAG_NO_ENTER_ACTION)) {
                EditorInfo.IME_ACTION_GO -> {
                    mEnterKey!!.iconPreview = null
                    mEnterKey!!.icon = null
                    mEnterKey!!.label = res.getText(R.string.label_go_key)
                }

                EditorInfo.IME_ACTION_NEXT -> {
                    mEnterKey!!.iconPreview = null
                    mEnterKey!!.icon = null
                    mEnterKey!!.label = res.getText(R.string.label_next_key)
                }

                EditorInfo.IME_ACTION_DONE -> {
                    mEnterKey!!.iconPreview = null
                    mEnterKey!!.icon = null
                    mEnterKey!!.label = res.getText(R.string.label_done_key)
                }

                EditorInfo.IME_ACTION_SEARCH -> {
                    mEnterKey!!.iconPreview = ResourcesCompat.getDrawable(
                        res, R.drawable.sym_keyboard_feedback_search, null)
                    mEnterKey!!.icon =
                        ResourcesCompat.getDrawable(res, R.drawable.sym_keyboard_search, null)
                    mEnterKey!!.label = null
                }

                EditorInfo.IME_ACTION_SEND -> {
                    mEnterKey!!.iconPreview = null
                    mEnterKey!!.icon = null
                    mEnterKey!!.label = res.getText(R.string.label_send_key)
                }

                else -> {
                    // Keep Return key in IM mode, we have a dedicated smiley key.
                    mEnterKey!!.iconPreview = ResourcesCompat.getDrawable(
                        res, R.drawable.sym_keyboard_feedback_return, null)
                    mEnterKey!!.icon =
                        ResourcesCompat.getDrawable(res, R.drawable.sym_keyboard_return, null)
                    mEnterKey!!.label = null
                }
            }
            // Set the initial size of the preview icon
            if (mEnterKey!!.iconPreview != null) {
                setDefaultBounds(mEnterKey!!.iconPreview)
            }
        }
    }

    fun enableShiftLock() {
        val index = shiftKeyIndex
        if (index >= 0) {
            mShiftKey = keys[index]
            mOldShiftIcon = mShiftKey!!.icon
        }
    }

    override fun setShiftState(shiftState: Int): Boolean {
        return if (mShiftKey != null) {
            // Tri-state LED tracks "on" and "lock" states, icon shows Caps state.
            mShiftKey!!.on =
                shiftState == SHIFT_ON || shiftState == SHIFT_LOCKED
            mShiftKey!!.locked =
                shiftState == SHIFT_LOCKED || shiftState == SHIFT_CAPS_LOCKED
            mShiftKey!!.icon =
                if (shiftState == SHIFT_OFF || shiftState == SHIFT_ON || shiftState == SHIFT_LOCKED) mOldShiftIcon else mShiftLockIcon
            super.setShiftState(shiftState, false)
        } else {
            super.setShiftState(shiftState, true)
        }
    }

    val isAlphaKeyboard get() = mIsAlphaKeyboard

    var extension get() = mExtensionKeyboard
        set(extKeyboard) { mExtensionKeyboard = extKeyboard }

    fun updateSymbolIcons(isAutoCompletion: Boolean) {
        updateDynamicKeys()
        updateSpaceBarForLocale(isAutoCompletion)
    }

    private fun setDefaultBounds(drawable: Drawable?) {
        drawable!!.setBounds(0, 0, drawable.intrinsicWidth, drawable.intrinsicHeight)
    }

    fun setVoiceMode(hasVoiceButton: Boolean, hasVoice: Boolean) {
        mHasVoiceButton = hasVoiceButton
        mVoiceEnabled = hasVoice
        updateDynamicKeys()
    }

    private fun updateDynamicKeys() {
        update123Key()
        updateF1Key()
    }

    private fun update123Key() {
        // Update KEYCODE_MODE_CHANGE key only on alphabet mode, not on symbol mode.
        if (m123Key != null && mIsAlphaKeyboard) {
            if (mVoiceEnabled && !mHasVoiceButton) {
                m123Key!!.icon = m123MicIcon
                m123Key!!.iconPreview = m123MicPreviewIcon
                m123Key!!.label = null
            } else {
                m123Key!!.icon = null
                m123Key!!.iconPreview = null
                m123Key!!.label = m123Label
            }
        }
    }

    private fun updateF1Key() {
        // Update KEYCODE_F1 key. Please note that some keyboard layouts have no F1 key.
        if (mF1Key == null) return

        if (mIsAlphaKeyboard) {
            if (mMode == KeyboardSwitcher.MODE_URL) {
                setNonMicF1Key(mF1Key!!, "/", R.xml.popup_slash)
            } else if (mMode == KeyboardSwitcher.MODE_EMAIL) {
                setNonMicF1Key(mF1Key!!, "@", R.xml.popup_at)
            } else {
                if (mVoiceEnabled && mHasVoiceButton) {
                    setMicF1Key(mF1Key!!)
                } else {
                    setNonMicF1Key(mF1Key!!, ",", R.xml.popup_comma)
                }
            }
        } else if (mIsAlphaFullKeyboard) {
            if (mVoiceEnabled && mHasVoiceButton) {
                setMicF1Key(mF1Key!!)
            } else {
                setSettingsF1Key(mF1Key!!)
            }
        } else if (mIsFnFullKeyboard) {
            setMicF1Key(mF1Key!!)
        } else {  // Symbols keyboard
            if (mVoiceEnabled && mHasVoiceButton) {
                setMicF1Key(mF1Key!!)
            } else {
                setNonMicF1Key(mF1Key!!, ",", R.xml.popup_comma)
            }
        }
    }

    private fun setMicF1Key(key: Key) {
        // HACK: draw mMicIcon and mHintIcon at the same time
        val micWithSettingsHintDrawable: Drawable = BitmapDrawable(
            mRes, drawSynthesizedSettingsHintImage(key.width, key.height, mMicIcon, mHintIcon))

        if (key.popupResId == 0) {
            key.popupResId = R.xml.popup_mic
        } else {
            key.modifier = true
            if (key.label != null) {
                key.popupCharacters =
                    if (key.popupCharacters == null)
                        key.label.toString() + key.shiftLabel.toString()
                    else
                        key.label.toString() + key.shiftLabel.toString() + key.popupCharacters.toString()
            }
        }

        key.label = null
        key.shiftLabel = null
        key.codes = intArrayOf(LatinKeyboardView.KEYCODE_VOICE)
        key.icon = micWithSettingsHintDrawable
        key.iconPreview = mMicPreviewIcon
    }

    private fun setSettingsF1Key(key: Key) {
        if (key.shiftLabel != null && key.label != null) {
            key.codes = intArrayOf(key.label!![0].code)
            return  // leave key otherwise unmodified
        }
        val settingsHintDrawable: Drawable = BitmapDrawable(
            mRes, drawSynthesizedSettingsHintImage(key.width, key.height, mSettingsIcon, mHintIcon))
        key.label = null
        key.icon = settingsHintDrawable
        key.codes = intArrayOf(LatinKeyboardView.KEYCODE_OPTIONS)
        key.popupResId = R.xml.popup_mic
        key.iconPreview = mSettingsPreviewIcon
    }

    private fun setNonMicF1Key(key: Key, label: String, popupResId: Int) {
        if (key.shiftLabel != null) {
            key.codes = intArrayOf(key.label!![0].code)
            return  // leave key unmodified
        }
        key.label = label
        key.codes = intArrayOf(label[0].code)
        key.popupResId = popupResId
        key.icon = mHintIcon
        key.iconPreview = null
    }

    fun isF1Key(key: Key): Boolean {
        return key === mF1Key
    }

    /**
     * @return a key which should be invalidated.
     */
    fun onAutoCompletionStateChanged(isAutoCompletion: Boolean): Key? {
        updateSpaceBarForLocale(isAutoCompletion)
        return mSpaceKey
    }

    val isLanguageSwitchEnabled: Boolean
        get() = mLocale != null

    private fun updateSpaceBarForLocale(isAutoCompletion: Boolean) {
        if (mSpaceKey == null) return
        // If application locales are explicitly selected.
        if (mLocale != null) {
            mSpaceKey!!.icon = BitmapDrawable(
                mRes, drawSpaceBar(OPACITY_FULLY_OPAQUE, isAutoCompletion))
        } else {
            // sym_keyboard_space_led can be shared with Black and White symbol themes.
            if (isAutoCompletion) {
                mSpaceKey!!.icon = BitmapDrawable(
                    mRes,
                    drawSpaceBar(OPACITY_FULLY_OPAQUE, true)
                )
            } else {
                mSpaceKey!!.icon =
                    ResourcesCompat.getDrawable(mRes, R.drawable.sym_keyboard_space, null)
            }
        }
    }

    // Overlay two images: mainIcon and hintIcon.
    private fun drawSynthesizedSettingsHintImage(
        width: Int, height: Int, mainIcon: Drawable?, hintIcon: Drawable?
    ): Bitmap? {
        if (mainIcon == null || hintIcon == null) return null
        val hintIconPadding = Rect(0, 0, 0, 0)
        hintIcon.getPadding(hintIconPadding)
        val buffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(buffer)
        canvas.drawColor(mRes.getColor(R.color.latinkeyboard_transparent), PorterDuff.Mode.CLEAR)

        // Draw main icon at the center of the key visual
        // Assuming the hintIcon shares the same padding with the key's background drawable
        val drawableX = (width + hintIconPadding.left - hintIconPadding.right
                - mainIcon.intrinsicWidth) / 2
        val drawableY = (height + hintIconPadding.top - hintIconPadding.bottom
                - mainIcon.intrinsicHeight) / 2
        setDefaultBounds(mainIcon)
        canvas.translate(drawableX.toFloat(), drawableY.toFloat())
        mainIcon.draw(canvas)
        canvas.translate(-drawableX.toFloat(), -drawableY.toFloat())

        // Draw hint icon fully in the key
        hintIcon.setBounds(0, 0, width, height)
        hintIcon.draw(canvas)
        return buffer
    }

    private fun drawSpaceBar(opacity: Int, isAutoCompletion: Boolean): Bitmap {
        val width = mSpaceKey!!.width
        val height = mSpaceIcon!!.intrinsicHeight
        val buffer = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(buffer)
        canvas.drawColor(mRes.getColor(R.color.latinkeyboard_transparent), PorterDuff.Mode.CLEAR)

        // If application locales are explicitly selected.
        if (mLocale != null) {
            val paint = Paint()
            paint.setAlpha(opacity)
            paint.isAntiAlias = true
            paint.textAlign = Align.CENTER

            val allowVariableTextSize = true
            val locale = mLanguageSwitcher!!.inputLocale
            //Log.i("PCKeyboard", "input locale: " + locale);
            val language = layoutSpaceBar(
                paint,
                locale,
                mButtonArrowLeftIcon,
                mButtonArrowRightIcon,
                width,
                height,
                getTextSizeFromTheme(android.R.style.TextAppearance_Small, 14).toFloat(),
                allowVariableTextSize
            )

            // Draw language text with shadow
            val shadowColor = mRes.getColor(R.color.latinkeyboard_bar_language_shadow_white)
            val baseline = height * SPACEBAR_LANGUAGE_BASELINE
            val descent = paint.descent()
            paint.setColor(shadowColor)
            canvas.drawText(language, width.toFloat() / 2, baseline - descent - 1, paint)
            paint.setColor(mRes.getColor(R.color.latinkeyboard_dim_color_white))

            canvas.drawText(language, width.toFloat() / 2, baseline - descent, paint)

            // Put arrows that are already layed out on either side of the text
            if (mLanguageSwitcher!!.localeCount > 1) {
                mButtonArrowLeftIcon!!.draw(canvas)
                mButtonArrowRightIcon!!.draw(canvas)
            }
        }

        // Draw the spacebar icon at the bottom
        if (isAutoCompletion) {
            val iconWidth = width * SPACE_LED_LENGTH_PERCENT / 100
            val iconHeight = mSpaceAutoCompletionIndicator!!.intrinsicHeight
            val x = (width - iconWidth) / 2
            val y = height - iconHeight
            mSpaceAutoCompletionIndicator.setBounds(x, y, x + iconWidth, y + iconHeight)
            mSpaceAutoCompletionIndicator.draw(canvas)
        } else {
            val iconWidth = mSpaceIcon.intrinsicWidth
            val iconHeight = mSpaceIcon.intrinsicHeight
            val x = (width - iconWidth) / 2
            val y = height - iconHeight
            mSpaceIcon.setBounds(x, y, x + iconWidth, y + iconHeight)
            mSpaceIcon.draw(canvas)
        }
        return buffer
    }

    private val spacePreviewWidth: Int
        get() = min(
            max(
                mSpaceKey!!.width,
                (minWidth * SPACEBAR_POPUP_MIN_RATIO).toInt()
            ),
            (screenHeight * SPACEBAR_POPUP_MAX_RATIO).toInt()
        )

    private fun updateLocaleDrag(diff: Int) {
        if (mSlidingLocaleIcon == null) {
            val width = spacePreviewWidth
            val height = mSpacePreviewIcon!!.intrinsicHeight
            mSlidingLocaleIcon = SlidingLocaleDrawable(mSpacePreviewIcon, width, height)
            mSlidingLocaleIcon!!.setBounds(0, 0, width, height)
            mSpaceKey!!.iconPreview = mSlidingLocaleIcon
        }
        mSlidingLocaleIcon!!.setDiff(diff)
        if (abs(diff) == Int.MAX_VALUE) {
            mSpaceKey!!.iconPreview = mSpacePreviewIcon
        } else {
            mSpaceKey!!.iconPreview = mSlidingLocaleIcon
        }
        mSpaceKey!!.iconPreview!!.invalidateSelf()
    }

    val languageChangeDirection: Int
        get() {
            if (mSpaceKey == null || mLanguageSwitcher!!.localeCount < 2
                || abs(mSpaceDragLastDiff) < spacePreviewWidth * SPACEBAR_DRAG_THRESHOLD) {
                return 0 // No change
            }
            return if (mSpaceDragLastDiff > 0) 1 else -1
        }

    fun setLanguageSwitcher(switcher: LanguageSwitcher?, isAutoCompletion: Boolean) {
        mLanguageSwitcher = switcher
        var locale =
            if (mLanguageSwitcher!!.localeCount > 0) mLanguageSwitcher!!.inputLocale else null
        // If the language count is 1 and is the same as the system language, don't show it.
        if (
            locale != null
            && mLanguageSwitcher!!.localeCount == 1
            && mLanguageSwitcher!!.systemLocale!!.language
                .equals(locale.language, ignoreCase = true)
        ) {
            locale = null
        }
        mLocale = locale
        updateSymbolIcons(isAutoCompletion)
    }

    val isCurrentlyInSpace get() = mCurrentlyInSpace

    fun setPreferredLetters(frequencies: IntArray?) {
        mPrefLetterFrequencies = frequencies
        mPrefLetter = 0
    }

    fun keyReleased() {
        mCurrentlyInSpace = false
        mSpaceDragLastDiff = 0
        mPrefLetter = 0
        mPrefLetterX = 0
        mPrefLetterY = 0
        mPrefDistance = Int.MAX_VALUE
        if (mSpaceKey != null) {
            updateLocaleDrag(Int.MAX_VALUE)
        }
    }

    /**
     * Does the magic of locking the touch gesture into the spacebar when
     * switching input languages.
     */
    fun isInside(key: LatinKey, x: Int, y: Int): Boolean {
        var x = x
        var y = y
        val code = key.codes!![0]
        if (code == KEYCODE_SHIFT || code == KEYCODE_DELETE) {
            // Adjust target area for these keys
            y -= key.height / 10
            if (code == KEYCODE_SHIFT) {
                if (key.x == 0) {
                    x += key.width / 6 // left shift
                } else {
                    x -= key.width / 6 // right shift
                }
            }
            if (code == KEYCODE_DELETE) x -= key.width / 6
        } else if (code == LatinIME.ASCII_SPACE) {
            y += sSpacebarVerticalCorrection
            if (mLanguageSwitcher!!.localeCount > 1) {
                if (mCurrentlyInSpace) {
                    val diff = x - mSpaceDragStartX
                    if (abs(diff - mSpaceDragLastDiff) > 0) {
                        updateLocaleDrag(diff)
                    }
                    mSpaceDragLastDiff = diff
                    return true
                } else {
                    val insideSpace = key.isInsideSuper(x, y)
                    if (insideSpace) {
                        mCurrentlyInSpace = true
                        mSpaceDragStartX = x
                        updateLocaleDrag(0)
                    }
                    return insideSpace
                }
            }
        } else if (mPrefLetterFrequencies != null) {
            // New coordinate? Reset
            if (mPrefLetterX != x || mPrefLetterY != y) {
                mPrefLetter = 0
                mPrefDistance = Int.MAX_VALUE
            }
            // Handle preferred next letter
            val pref: IntArray = mPrefLetterFrequencies!!
            return if (mPrefLetter > 0) {
                if (DEBUG_PREFERRED_LETTER) {
                    if (mPrefLetter == code && !key.isInsideSuper(x, y)) {
                        Log.d(TAG, "CORRECTED !!!!!!")
                    }
                }
                mPrefLetter == code
            } else {
                val inside = key.isInsideSuper(x, y)
                val nearby = getNearestKeys(x, y)
                val nearbyKeys: List<Key> = keys
                if (inside) {
                    // If it's a preferred letter
                    if (inPrefList(code, pref)) {
                        // Check if its frequency is much lower than a nearby key
                        mPrefLetter = code
                        mPrefLetterX = x
                        mPrefLetterY = y
                        for (nearbyKey in nearby!!) {
                            val k = nearbyKeys[nearbyKey]
                            if (k !== key && inPrefList(k.codes!![0], pref)) {
                                val dist = distanceFrom(k, x, y)
                                if (dist < (k.width * OVERLAP_PERCENTAGE_LOW_PROB).toInt() && pref[k.codes!![0]] > pref[mPrefLetter] * 3) {
                                    mPrefLetter = k.codes!![0]
                                    mPrefDistance = dist
                                    if (DEBUG_PREFERRED_LETTER) {
                                        Log.d(TAG, "CORRECTED ALTHOUGH PREFERRED !!!!!!")
                                    }
                                    break
                                }
                            }
                        }

                        return mPrefLetter == code
                    }
                }

                // Get the surrounding keys and intersect with the preferred list
                // For all in the intersection
                //   if distance from touch point is within a reasonable distance
                //       make this the pref letter
                // If no pref letter
                //   return inside;
                // else return thiskey == prefletter;
                for (nearbyKey in nearby!!) {
                    val k = nearbyKeys[nearbyKey]
                    if (inPrefList(k.codes!![0], pref)) {
                        val dist = distanceFrom(k, x, y)
                        if (dist < (k.width * OVERLAP_PERCENTAGE_HIGH_PROB).toInt()
                            && dist < mPrefDistance
                        ) {
                            mPrefLetter = k.codes!![0]
                            mPrefLetterX = x
                            mPrefLetterY = y
                            mPrefDistance = dist
                        }
                    }
                }
                // Didn't find any
                if (mPrefLetter == 0) {
                    inside
                } else {
                    mPrefLetter == code
                }
            }
        }

        // Lock into the spacebar
        return if (mCurrentlyInSpace) false else key.isInsideSuper(x, y)
    }

    private fun inPrefList(code: Int, pref: IntArray): Boolean {
        return if (code < pref.size && code >= 0) pref[code] > 0 else false
    }

    private fun distanceFrom(k: Key, x: Int, y: Int): Int {
        return if (y > k.y && y < k.y + k.height) {
            abs(k.x + k.width / 2 - x)
        } else {
            Int.MAX_VALUE
        }
    }

    override fun getNearestKeys(x: Int, y: Int): IntArray? {
        return if (mCurrentlyInSpace) {
            mSpaceKeyIndexArray
        } else {
            // Avoid dead pixels at edges of the keyboard
            super.getNearestKeys(
                max(0, min(x, minWidth - 1)),
                max(0, min(y, height - 1))
            )
        }
    }

    private fun indexOf(code: Int): Int {
        val keys: List<Key> = keys
        val count = keys.size
        for (i in 0 until count) {
            if (keys[i].codes!![0] == code) return i
        }
        return -1
    }

    private fun getTextSizeFromTheme(style: Int, defValue: Int): Int {
        val array = mContext.theme.obtainStyledAttributes(
            style, intArrayOf(android.R.attr.textSize))
        val resId = array.getResourceId(0, 0)
        if (resId >= array.length()) {
            Log.i(TAG, "getTextSizeFromTheme error: resId " + resId + " > " + array.length())
            return defValue
        }
        return array.getDimensionPixelSize(resId, defValue)
    }

    // TODO LatinKey could be static class
    inner class LatinKey(
        res: Resources?, parent: Row?, x: Int, y: Int,
        parser: XmlResourceParser?
    ) : Key(
        res!!, parent, x, y, parser
    ) {
        // functional normal state (with properties)
        private val KEY_STATE_FUNCTIONAL_NORMAL = intArrayOf(
            android.R.attr.state_single
        )

        // functional pressed state (with properties)
        private val KEY_STATE_FUNCTIONAL_PRESSED = intArrayOf(
            android.R.attr.state_single,
            android.R.attr.state_pressed
        )
        private val isFunctionalKey: Boolean
            // sticky is used for shift key.  If a key is not sticky and is modifier,
            get() = !sticky && modifier

        /**
         * Overriding this method so that we can reduce the target area for certain keys.
         */
        override fun isInside(x: Int, y: Int): Boolean {
            // TODO This should be done by parent.isInside(this, x, y)
            // if Key.parent were protected.
            return this@LatinKeyboard.isInside(this, x, y)
        }

        fun isInsideSuper(x: Int, y: Int): Boolean {
            return super.isInside(x, y)
        }

        override val currentDrawableState: IntArray?
            get() = if (isFunctionalKey) {
                if (pressed) {
                    KEY_STATE_FUNCTIONAL_PRESSED
                } else {
                    KEY_STATE_FUNCTIONAL_NORMAL
                }
            } else super.currentDrawableState

        override fun squaredDistanceFrom(x: Int, y: Int): Int {
            // We should count vertical gap between rows to calculate the center of this Key.
            val verticalGap = mVerticalGap
            val xDist = this.x + width / 2 - x
            val yDist = this.y + (height + verticalGap) / 2 - y
            return xDist * xDist + yDist * yDist
        }
    }

    /**
     * Animation to be displayed on the spacebar preview popup when switching
     * languages by swiping the spacebar. It draws the current, previous and
     * next languages and moves them by the delta of touch movement on the spacebar.
     */
    internal inner class SlidingLocaleDrawable(
        private val mBackground: Drawable?,
        width: Int,
        height: Int
    ) : Drawable() {
        private val mWidth: Int
        private val mHeight: Int
        private val mTextPaint: TextPaint
        private val mMiddleX: Int
        private val mLeftDrawable: Drawable?
        private val mRightDrawable: Drawable?
        private val mThreshold: Int
        private var mDiff = 0
        private var mHitThreshold = false
        private var mCurrentLanguage: String? = null
        private var mNextLanguage: String? = null
        private var mPrevLanguage: String? = null

        init {
            setDefaultBounds(mBackground)
            mWidth = width
            mHeight = height
            mTextPaint = TextPaint()
            mTextPaint.textSize =
                getTextSizeFromTheme(android.R.style.TextAppearance_Medium, 18).toFloat()
            mTextPaint.setColor(mRes.getColor(R.color.latinkeyboard_transparent))
            mTextPaint.textAlign = Align.CENTER
            mTextPaint.setAlpha(OPACITY_FULLY_OPAQUE)
            mTextPaint.isAntiAlias = true
            mMiddleX = (mWidth - mBackground!!.intrinsicWidth) / 2
            mLeftDrawable = ResourcesCompat.getDrawable(
                mRes, R.drawable.sym_keyboard_feedback_language_arrows_left, null)
            mRightDrawable = ResourcesCompat.getDrawable(
                mRes, R.drawable.sym_keyboard_feedback_language_arrows_right, null)
            mThreshold = ViewConfiguration.get(mContext).scaledTouchSlop
        }

        fun setDiff(diff: Int) {
            if (diff == Int.MAX_VALUE) {
                mHitThreshold = false
                mCurrentLanguage = null
                return
            }
            mDiff = diff
            if (mDiff > mWidth) mDiff = mWidth
            if (mDiff < -mWidth) mDiff = -mWidth
            if (abs(mDiff) > mThreshold) mHitThreshold = true
            invalidateSelf()
        }

        private fun getLanguageName(locale: Locale?): String {
            return LanguageSwitcher.toTitleCase(locale!!.getDisplayLanguage(locale))
        }

        override fun draw(canvas: Canvas) {
            canvas.save()
            if (mHitThreshold) {
                val paint: Paint = mTextPaint
                val width = mWidth
                val height = mHeight
                val diff = mDiff
                val lArrow = mLeftDrawable
                val rArrow = mRightDrawable
                canvas.clipRect(0, 0, width, height)
                if (mCurrentLanguage == null) {
                    val languageSwitcher = mLanguageSwitcher
                    mCurrentLanguage = getLanguageName(languageSwitcher!!.inputLocale)
                    mNextLanguage = getLanguageName(languageSwitcher.nextInputLocale)
                    mPrevLanguage = getLanguageName(languageSwitcher.prevInputLocale)
                }
                // Draw language text with shadow
                val baseline = mHeight * SPACEBAR_LANGUAGE_BASELINE - paint.descent()
                paint.setColor(mRes.getColor(R.color.latinkeyboard_feedback_language_text))
                canvas.drawText(mCurrentLanguage!!, width.toFloat() / 2 + diff, baseline, paint)
                canvas.drawText(mNextLanguage!!, diff - width.toFloat() / 2, baseline, paint)
                canvas.drawText(
                    mPrevLanguage!!, diff + width + width.toFloat() / 2,
                    baseline, paint
                )

                setDefaultBounds(lArrow)
                rArrow!!.setBounds(
                    width - rArrow.intrinsicWidth, 0, width, rArrow.intrinsicHeight)
                lArrow!!.draw(canvas)
                rArrow.draw(canvas)
            }
            if (mBackground != null) {
                canvas.translate(mMiddleX.toFloat(), 0f)
                mBackground.draw(canvas)
            }
            canvas.restore()
        }

        @Deprecated("Deprecated in Java",
            ReplaceWith("PixelFormat.TRANSLUCENT", "android.graphics.PixelFormat")
        )
        override fun getOpacity(): Int {
            return PixelFormat.TRANSLUCENT
        }

        override fun setAlpha(alpha: Int) {
            // Ignore
        }

        override fun setColorFilter(cf: ColorFilter?) {
            // Ignore
        }

        override fun getIntrinsicWidth(): Int {
            return mWidth
        }

        override fun getIntrinsicHeight(): Int {
            return mHeight
        }
    }

    companion object {
        private const val DEBUG_PREFERRED_LETTER = true
        private const val TAG = "PCKeyboardLK"
        private const val OPACITY_FULLY_OPAQUE = 255
        private const val SPACE_LED_LENGTH_PERCENT = 80
        private const val SPACEBAR_DRAG_THRESHOLD = 0.51f
        private const val OVERLAP_PERCENTAGE_LOW_PROB = 0.70f
        private const val OVERLAP_PERCENTAGE_HIGH_PROB = 0.85f

        // Minimum width of space key preview (proportional to keyboard width)
        private const val SPACEBAR_POPUP_MIN_RATIO = 0.4f

        // Minimum width of space key preview (proportional to screen height)
        private const val SPACEBAR_POPUP_MAX_RATIO = 0.4f

        // Height in space key the language name will be drawn. (proportional to space key height)
        private const val SPACEBAR_LANGUAGE_BASELINE = 0.6f

        // If the full language name needs to be smaller than this value to be drawn on space key,
        // its short language name will be used instead.
        private const val MINIMUM_SCALE_OF_LANGUAGE_NAME = 0.8f
        private var sSpacebarVerticalCorrection: Int = 0
        fun hasPuncOrSmileysPopup(key: Key): Boolean {
            return key.popupResId == R.xml.popup_punctuation || key.popupResId == R.xml.popup_smileys
        }

        // Compute width of text with specified text size using paint.
        private fun getTextWidth(paint: Paint, text: String, textSize: Float, bounds: Rect): Int {
            paint.textSize = textSize
            paint.getTextBounds(text, 0, text.length, bounds)
            return bounds.width()
        }

        // Layout local language name and left and right arrow on space bar.
        private fun layoutSpaceBar(
            paint: Paint,
            locale: Locale?,
            lArrow: Drawable?,
            rArrow: Drawable?,
            width: Int, height: Int,
            origTextSize: Float,
            allowVariableTextSize: Boolean
        ): String {
            val arrowWidth = lArrow!!.intrinsicWidth.toFloat()
            val arrowHeight = lArrow.intrinsicHeight.toFloat()
            val maxTextWidth = width - (arrowWidth + arrowWidth)
            val bounds = Rect()

            // Estimate appropriate language name text size to fit in maxTextWidth.
            var language = LanguageSwitcher.toTitleCase(locale!!.getDisplayLanguage(locale))
            var textWidth = getTextWidth(paint, language, origTextSize, bounds)
            // Assuming text width and text size are proportional to each other.
            var textSize = origTextSize * min((maxTextWidth / textWidth), 1.0f)
            val useShortName: Boolean
            if (allowVariableTextSize) {
                textWidth = getTextWidth(paint, language, textSize, bounds)
                // If text size goes too small or text does not fit, use short name
                useShortName = (textSize / origTextSize < MINIMUM_SCALE_OF_LANGUAGE_NAME
                        || textWidth > maxTextWidth)
            } else {
                useShortName = textWidth > maxTextWidth
                textSize = origTextSize
            }
            if (useShortName) {
                language = LanguageSwitcher.toTitleCase(locale.language)
                textWidth = getTextWidth(paint, language, origTextSize, bounds)
                textSize = origTextSize * min(maxTextWidth / textWidth, 1.0f)
            }
            paint.textSize = textSize

            // Place left and right arrow just before and after language text.
            val baseline = height * SPACEBAR_LANGUAGE_BASELINE
            val top = (baseline - arrowHeight).toInt()
            val remains = (width - textWidth).toFloat() / 2
            lArrow.setBounds((remains - arrowWidth).toInt(), top, remains.toInt(), baseline.toInt())
            rArrow!!.setBounds(
                (remains + textWidth).toInt(),
                top,
                (remains + textWidth + arrowWidth).toInt(),
                baseline.toInt()
            )
            return language
        }
    }
}
