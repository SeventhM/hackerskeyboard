/*
 * Copyright (C) 2008-2009 Google Inc.
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
import android.content.res.TypedArray
import android.content.res.XmlResourceParser
import android.graphics.drawable.Drawable
import android.text.TextUtils
import android.util.Log
import android.util.TypedValue
import android.util.Xml
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.util.Locale
import java.util.StringTokenizer

/**
 * Loads an XML description of a keyboard and stores the attributes of the keys. A keyboard
 * consists of rows of keys.
 *
 * The layout file for a keyboard contains XML that looks like the following snippet:
 * <pre>
 * &lt;Keyboard
 * android:keyWidth="%10p"
 * android:keyHeight="50px"
 * android:horizontalGap="2px"
 * android:verticalGap="2px" &gt;
 * &lt;Row android:keyWidth="32px" &gt;
 * &lt;Key android:keyLabel="A" /&gt;
 * ...
 * &lt;/Row&gt;
 * ...
 * &lt;/Keyboard&gt;
</pre> *
 * @attr ref android.R.styleable#Keyboard_keyWidth
 * @attr ref android.R.styleable#Keyboard_keyHeight
 * @attr ref android.R.styleable#Keyboard_horizontalGap
 * @attr ref android.R.styleable#Keyboard_verticalGap
 * @param context the application or service context
 * @param xmlLayoutResId the resource file that contains the keyboard layout and keys.
 * @param modeId keyboard mode identifier
 * @param kbHeightPercent height of the keyboard as percentage of screen height
 */
open class Keyboard @JvmOverloads constructor(
        context: Context,
        defaultHeight: Int,
        xmlLayoutResId: Int,
        modeId: Int = 0,
        kbHeightPercent: Float = 0f
) {
    /** Horizontal gap default for all rows  */
    private var mDefaultHorizontalGap = 0f
    private var mHorizontalPad = 0f
    private var mVerticalPad = 0f

    /** Default key width  */
    private var mDefaultWidth = 0f

    /** Default key height  */
    protected var mDefaultHeight = 0

    /** Default gap between rows  */
    protected var mDefaultVerticalGap = 0

    /** Is the keyboard in the shifted state  */
    var shiftState = SHIFT_OFF
        private set

    /** Key instance for the shift key, if present  */
    private var mShiftKey: Key? = null
    private var mAltKey: Key? = null
    private var mCtrlKey: Key? = null
    private var mMetaKey: Key? = null

    /** Key index for the shift key, if present  */
    var shiftKeyIndex = -1
        private set
    /**
     * Returns the total height of the keyboard
     * @return the total height of the keyboard
     */
    /** Total height of the keyboard, including the padding and keys  */
    var mTotalHeight = 0

    /**
     * Total width of the keyboard, including left side gaps and keys, but not any gaps on the
     * right side.
     */
    private var mTotalWidth = 0

    /** List of keys in this keyboard  */
    private val mKeys: ArrayList<Key>

    /** List of modifier keys such as Shift & Alt, if any  */
    private val mModifierKeys: ArrayList<Key?>
    /** Width of the screen available to fit the keyboard  */
    private val mDisplayWidth: Int

    /** Height of the screen and keyboard  */
    val screenHeight: Int
    private val mKeyboardHeight: Int

    /** Keyboard mode, or zero, if none.   */
    private val mKeyboardMode: Int
    private val mUseExtension: Boolean
    var mLayoutRows = 0
    var mLayoutColumns = 0
    @JvmField
    var mRowCount = 1
    var mExtensionRowCount = 0

    // Variables for pre-computing nearest keys.
    private var mCellWidth = 0
    private var mCellHeight = 0
    private var mGridNeighbors: Array<IntArray?>? = null
    private var mProximityThreshold = 0

    /**
     * Container for keys in the keyboard. All keys in a row are at the same Y-coordinate.
     * Some of the key size defaults can be overridden per row from what the [Keyboard]
     * defines.
     * @attr ref android.R.styleable#Keyboard_keyWidth
     * @attr ref android.R.styleable#Keyboard_keyHeight
     * @attr ref android.R.styleable#Keyboard_horizontalGap
     * @attr ref android.R.styleable#Keyboard_verticalGap
     * @attr ref android.R.styleable#Keyboard_Row_keyboardMode
     */
    class Row {
        /** Default width of a key in this row.  */
        var defaultWidth = 0f

        /** Default height of a key in this row.  */
        var defaultHeight = 0

        /** Default horizontal gap between keys in this row.  */
        var defaultHorizontalGap = 0f

        /** Vertical gap following this row.  */
        var verticalGap = 0

        /** The keyboard mode for this row  */
        var mode = 0
        var extension = false
        val parent: Keyboard

        constructor(parent: Keyboard) {
            this.parent = parent
        }

        constructor(res: Resources, parent: Keyboard, parser: XmlResourceParser?) {
            this.parent = parent
            var a = res.obtainAttributes(
                    Xml.asAttributeSet(parser),
                    R.styleable.Keyboard
            )
            defaultWidth = getDimensionOrFraction(
                    a, R.styleable.Keyboard_keyWidth,
                    parent.mDisplayWidth, parent.mDefaultWidth
            )
            defaultHeight = Math.round(
                    getDimensionOrFraction(
                            a, R.styleable.Keyboard_keyHeight,
                            parent.screenHeight, parent.keyHeight.toFloat()
                    )
            )
            defaultHorizontalGap = getDimensionOrFraction(
                    a, R.styleable.Keyboard_horizontalGap,
                    parent.mDisplayWidth, parent.mDefaultHorizontalGap
            )
            verticalGap = Math.round(
                    getDimensionOrFraction(
                            a, R.styleable.Keyboard_verticalGap,
                            parent.screenHeight, parent.verticalGap.toFloat()
                    )
            )
            a.recycle()
            a = res.obtainAttributes(
                    Xml.asAttributeSet(parser),
                    R.styleable.Keyboard_Row
            )
            mode = a.getResourceId(R.styleable.Keyboard_Row_keyboardMode, 0)
            extension = a.getBoolean(R.styleable.Keyboard_Row_extension, false)
            if (parent.mLayoutRows >= 5 || extension) {
                // Apply optional scale factor to top (5th) row and/or extension row. If extension
                // row is visible on a 5-row keyboard, both use the smaller size.
                val scale = getKeyboardScale(parent)
                defaultHeight = Math.round(defaultHeight * scale)
            }
            a.recycle()
        }

        private fun getKeyboardScale(parent: Keyboard): Float {
            val isTop = extension || parent.mRowCount - parent.mExtensionRowCount <= 0
            val topScale = LatinIME.sKeyboardSettings.topRowScale
            // Apply scale factor to the top row(s), and redistribute the saved space to the
            // remaining rows. Saved space from the extension row doesn't count here since
            // the extension row is not part of the configured height percentage.
            val scale = if (isTop) topScale else 1.0f + (1.0f - topScale) / (parent.mLayoutRows - 1)
            return scale
        }
    }

    /**
     * Class for describing the position and characteristics of a single key in the keyboard.
     *
     * @attr ref android.R.styleable#Keyboard_keyWidth
     * @attr ref android.R.styleable#Keyboard_keyHeight
     * @attr ref android.R.styleable#Keyboard_horizontalGap
     * @attr ref android.R.styleable#Keyboard_Key_codes
     * @attr ref android.R.styleable#Keyboard_Key_keyIcon
     * @attr ref android.R.styleable#Keyboard_Key_keyLabel
     * @attr ref android.R.styleable#Keyboard_Key_iconPreview
     * @attr ref android.R.styleable#Keyboard_Key_isSticky
     * @attr ref android.R.styleable#Keyboard_Key_isRepeatable
     * @attr ref android.R.styleable#Keyboard_Key_isModifier
     * @attr ref android.R.styleable#Keyboard_Key_popupKeyboard
     * @attr ref android.R.styleable#Keyboard_Key_popupCharacters
     * @attr ref android.R.styleable#Keyboard_Key_keyOutputText
     */
    open class Key(parent: Row?) {
        /**
         * All the key codes (unicode or custom code) that this key could generate, zero'th
         * being the most important.
         */
        @JvmField
        var codes: IntArray? = null

        /** Label to display  */
        @JvmField
        var label: CharSequence? = null
        @JvmField
        var shiftLabel: CharSequence? = null
        var capsLabel: CharSequence? = null

        /** Icon to display instead of a label. Icon takes precedence over a label  */
        @JvmField
        var icon: Drawable? = null

        /** Preview version of the icon, for the preview popup  */
        @JvmField
        var iconPreview: Drawable? = null

        /** Width of the key, not including the gap  */
        @JvmField
        var width: Int

        /** Height of the key, not including the gap  */
        var realWidth: Float
        @JvmField
        var height: Int

        /** The horizontal gap before this key  */
        @JvmField
        var gap: Int
        var realGap: Float

        /** Whether this key is sticky, i.e., a toggle key  */
        @JvmField
        var sticky = false

        /** X coordinate of the key in the keyboard layout  */
        @JvmField
        var x = 0
        var realX = 0f

        /** Y coordinate of the key in the keyboard layout  */
        @JvmField
        var y = 0

        /** The current pressed state of this key  */
        @JvmField
        var pressed = false

        /** If this is a sticky key, is it on or locked?  */
        @JvmField
        var on = false
        @JvmField
        var locked = false

        /** Text to output when pressed. This can be multiple characters, like ".com"  */
        @JvmField
        var text: CharSequence? = null

        /** Popup characters  */
        @JvmField
        var popupCharacters: CharSequence? = null
        @JvmField
        var popupReversed = false
        @JvmField
        var isCursor = false
        var hint: String? = null // Set by LatinKeyboardBaseView
        var altHint: String? = null // Set by LatinKeyboardBaseView

        /**
         * Flags that specify the anchoring to edges of the keyboard for detecting touch events
         * that are just out of the boundary of the key. This is a bit mask of
         * [Keyboard.EDGE_LEFT], [Keyboard.EDGE_RIGHT], [Keyboard.EDGE_TOP] and
         * [Keyboard.EDGE_BOTTOM].
         */
        @JvmField
        var edgeFlags = 0

        /** Whether this is a modifier key, such as Shift or Alt  */
        @JvmField
        var modifier = false

        /** The keyboard that this key belongs to  */
        private val keyboard: Keyboard

        /**
         * If this key pops up a mini keyboard, this is the resource id for the XML layout for that
         * keyboard.
         */
        @JvmField
        var popupResId = 0

        /** Whether this key repeats itself when held down  */
        @JvmField
        var repeatable = false

        /** Is the shifted character the uppercase equivalent of the unshifted one?  */
        private var isSimpleUppercase = false

        /** Is the shifted character a distinct uppercase char that's different from the shifted char?  */
        private var isDistinctUppercase = false

        /** Create an empty key with no attributes.  */
        init {
            keyboard = parent!!.parent
            height = parent.defaultHeight
            width = Math.round(parent.defaultWidth)
            realWidth = parent.defaultWidth
            gap = Math.round(parent.defaultHorizontalGap)
            realGap = parent.defaultHorizontalGap
        }

        /** Create a key with the given top-left coordinate and extract its attributes from
         * the XML parser.
         * @param res resources associated with the caller's context
         * @param parent the row that this key belongs to. The row must already be attached to
         * a [Keyboard].
         * @param x the x coordinate of the top-left
         * @param y the y coordinate of the top-left
         * @param parser the XML parser containing the attributes for this key
         */
        constructor(
                res: Resources,
                parent: Row?,
                x: Int,
                y: Int,
                parser: XmlResourceParser?
        ) : this(parent) {
            this.x = x
            this.y = y
            var a = res.obtainAttributes(
                    Xml.asAttributeSet(parser),
                    R.styleable.Keyboard
            )
            realWidth = getDimensionOrFraction(
                    a, R.styleable.Keyboard_keyWidth,
                    keyboard.mDisplayWidth, parent!!.defaultWidth
            )
            var realHeight = getDimensionOrFraction(
                    a, R.styleable.Keyboard_keyHeight,
                    keyboard.screenHeight, parent.defaultHeight.toFloat()
            )
            realHeight -= parent.parent.mVerticalPad
            height = Math.round(realHeight)
            this.y += (parent.parent.mVerticalPad / 2).toInt()
            realGap = getDimensionOrFraction(
                    a, R.styleable.Keyboard_horizontalGap,
                    keyboard.mDisplayWidth, parent.defaultHorizontalGap
            )
            realGap += parent.parent.mHorizontalPad
            realWidth -= parent.parent.mHorizontalPad
            width = Math.round(realWidth)
            gap = Math.round(realGap)
            a.recycle()
            a = res.obtainAttributes(
                    Xml.asAttributeSet(parser),
                    R.styleable.Keyboard_Key
            )
            realX = this.x + realGap - parent.parent.mHorizontalPad / 2
            this.x = Math.round(realX)
            val codesValue = TypedValue()
            a.getValue(R.styleable.Keyboard_Key_codes, codesValue)
            if (codesValue.type == TypedValue.TYPE_INT_DEC
                    || codesValue.type == TypedValue.TYPE_INT_HEX
            ) {
                codes = intArrayOf(codesValue.data)
            } else if (codesValue.type == TypedValue.TYPE_STRING) {
                codes = parseCSV(codesValue.string.toString())
            }
            iconPreview = a.getDrawable(R.styleable.Keyboard_Key_iconPreview)
            if (iconPreview != null) {
                iconPreview!!.setBounds(
                        0, 0, iconPreview!!.intrinsicWidth,
                        iconPreview!!.intrinsicHeight
                )
            }
            popupCharacters = a.getText(R.styleable.Keyboard_Key_popupCharacters)
            popupResId = a.getResourceId(R.styleable.Keyboard_Key_popupKeyboard, 0)
            repeatable = a.getBoolean(R.styleable.Keyboard_Key_isRepeatable, false)
            modifier = a.getBoolean(R.styleable.Keyboard_Key_isModifier, false)
            sticky = a.getBoolean(R.styleable.Keyboard_Key_isSticky, false)
            isCursor = a.getBoolean(R.styleable.Keyboard_Key_isCursor, false)
            icon = a.getDrawable(R.styleable.Keyboard_Key_keyIcon)
            if (icon != null) {
                icon!!.setBounds(0, 0, icon!!.intrinsicWidth, icon!!.intrinsicHeight)
            }
            label = a.getText(R.styleable.Keyboard_Key_keyLabel)
            shiftLabel = a.getText(R.styleable.Keyboard_Key_shiftLabel)
            if (shiftLabel != null && shiftLabel!!.isEmpty()) shiftLabel = null
            capsLabel = a.getText(R.styleable.Keyboard_Key_capsLabel)
            if (capsLabel != null && capsLabel!!.isEmpty()) capsLabel = null
            text = a.getText(R.styleable.Keyboard_Key_keyOutputText)

            if (codes == null && !TextUtils.isEmpty(label)) {
                codes = getFromString(label)
                if (codes != null && codes!!.size == 1) {
                    val locale = LatinIME.sKeyboardSettings.inputLocale
                    val upperLabel = label.toString().uppercase(locale)
                    if (shiftLabel == null) {
                        // No shiftLabel supplied, auto-set to uppercase if possible.
                        if (upperLabel != label.toString() && upperLabel.length == 1) {
                            shiftLabel = upperLabel
                            isSimpleUppercase = true
                        }
                    } else {
                        // Both label and shiftLabel supplied. Check if
                        // the shiftLabel is the uppercased normal label.
                        // If not, treat it as a distinct uppercase variant.
                        if (capsLabel != null) {
                            isDistinctUppercase = true
                        } else if (upperLabel == shiftLabel.toString()) {
                            isSimpleUppercase = true
                        } else if (upperLabel.length == 1) {
                            capsLabel = upperLabel
                            isDistinctUppercase = true
                        }
                    }
                }
                if (LatinIME.sKeyboardSettings.popupKeyboardFlags and POPUP_DISABLE != 0) {
                    popupCharacters = null
                    popupResId = 0
                }
                if (LatinIME.sKeyboardSettings.popupKeyboardFlags and POPUP_AUTOREPEAT != 0) {
                    // Assume POPUP_DISABLED is set too, otherwise things may get weird.
                    repeatable = true
                }
            }
            //Log.i(TAG, "added key definition: " + this);
            a.recycle()
        }

        val isDistinctCaps: Boolean
            get() = isDistinctUppercase && keyboard.isShiftCaps
        val isShifted: Boolean
            get() {
                val shifted = keyboard.isShifted(isSimpleUppercase)
                //Log.i(TAG, "FIXME isShifted=" + shifted + " for " + this);
                return shifted
            }

        fun getPrimaryCode(isShiftCaps: Boolean, isShifted: Boolean): Int {
            if (isDistinctUppercase && isShiftCaps) {
                return capsLabel!![0].code
            }
            //Log.i(TAG, "getPrimaryCode(), shifted=" + shifted);
            return if (isShifted && shiftLabel != null) {
                if (shiftLabel!![0] == DEAD_KEY_PLACEHOLDER && shiftLabel!!.length >= 2) {
                    shiftLabel!![1].code
                } else {
                    shiftLabel!![0].code
                }
            } else {
                codes!![0]
            }
        }

        val primaryCode: Int
            get() = getPrimaryCode(keyboard.isShiftCaps, keyboard.isShifted(isSimpleUppercase))
        val isDeadKey: Boolean
            get() =
                if (codes == null || codes!!.isEmpty()) false
                else Character.getType(codes!![0]) == Character.NON_SPACING_MARK.toInt()

        fun getFromString(str: CharSequence?): IntArray {
            return if (str!!.length > 1) {
                if (str[0] == DEAD_KEY_PLACEHOLDER && str.length >= 2) {
                    intArrayOf(str[1].code) // FIXME: >1 length?
                } else {
                    text = str // TODO: add space?
                    intArrayOf(0)
                }
            } else {
                val c = str[0]
                intArrayOf(c.code)
            }
        }

        val caseLabel: String?
            get() {
                if (isDistinctUppercase && keyboard.isShiftCaps) {
                    return capsLabel.toString()
                }
                val isShifted = keyboard.isShifted(isSimpleUppercase)
                return if (isShifted && shiftLabel != null) {
                    shiftLabel.toString()
                } else {
                    if (label != null) label.toString() else null
                }
            }

        private fun getPopupKeyboardContent(
                isShiftCaps: Boolean,
                isShifted: Boolean,
                addExtra: Boolean
        ): String {
            var mainChar = getPrimaryCode(false, false)
            var shiftChar = getPrimaryCode(false, true)
            var capsChar = getPrimaryCode(true, true)

            // Remove duplicates
            if (shiftChar == mainChar) shiftChar = 0
            if (capsChar == shiftChar || capsChar == mainChar) capsChar = 0
            val popupLen = if (popupCharacters == null) 0 else popupCharacters!!.length
            val popup = StringBuilder(popupLen)
            for (i in 0 until popupLen) {
                var c = popupCharacters!![i]
                if (isShifted || isShiftCaps) {
                    val upper = c.toString().uppercase(LatinIME.sKeyboardSettings.inputLocale)
                    if (upper.length == 1) c = upper[0]
                }
                if (c.code == mainChar || c.code == shiftChar || c.code == capsChar) continue
                popup.append(c)
            }
            if (addExtra) {
                val extra = StringBuilder(3 + popup.length)
                val flags = LatinIME.sKeyboardSettings.popupKeyboardFlags
                if (flags and POPUP_ADD_SELF != 0) {
                    // if shifted, add unshifted key to extra, and vice versa
                    if (isDistinctUppercase && isShiftCaps) {
                        if (capsChar > 0) { extra.append(capsChar.toChar()); capsChar = 0 }
                    } else if (isShifted) {
                        if (shiftChar > 0) { extra.append(shiftChar.toChar()); shiftChar = 0 }
                    } else {
                        if (mainChar > 0) { extra.append(mainChar.toChar()); mainChar = 0 }
                    }
                }
                if (flags and POPUP_ADD_CASE != 0) {
                    // if shifted, add unshifted key to popup, and vice versa
                    if (isDistinctUppercase && isShiftCaps) {
                        if (mainChar > 0) { extra.append(mainChar.toChar()); mainChar = 0 }
                        if (shiftChar > 0) { extra.append(shiftChar.toChar()); shiftChar = 0 }
                    } else if (isShifted) {
                        if (mainChar > 0) { extra.append(mainChar.toChar()); mainChar = 0 }
                        if (capsChar > 0) { extra.append(capsChar.toChar()); capsChar = 0 }
                    } else {
                        if (shiftChar > 0) { extra.append(shiftChar.toChar()); shiftChar = 0 }
                        if (capsChar > 0) { extra.append(capsChar.toChar()); capsChar = 0 }
                    }
                }
                if (!isSimpleUppercase && flags and POPUP_ADD_SHIFT != 0) {
                    // if shifted, add unshifted key to popup, and vice versa
                    if (isShifted) {
                        if (mainChar > 0) { extra.append(mainChar.toChar()); mainChar = 0 }
                    } else {
                        if (shiftChar > 0) { extra.append(shiftChar.toChar()); shiftChar = 0 }
                    }
                }
                extra.append(popup)
                return extra.toString()
            }
            return popup.toString()
        }

        fun getPopupKeyboard(context: Context, padding: Int): Keyboard? {
            if (popupCharacters == null) {
                if (popupResId != 0) {
                    return Keyboard(context, keyboard.keyHeight, popupResId)
                } else {
                    if (modifier) return null // Space, Return etc.
                }
            }
            if (LatinIME.sKeyboardSettings.popupKeyboardFlags and POPUP_DISABLE != 0) return null
            val popup = getPopupKeyboardContent(
                    keyboard.isShiftCaps,
                    keyboard.isShifted(isSimpleUppercase),
                    true
            )
            //Log.i(TAG, "getPopupKeyboard: popup='" + popup + "' for " + this);
            return if (popup.isNotEmpty()) {
                var resId = popupResId
                if (resId == 0) resId = R.xml.kbd_popup_template
                Keyboard(context, keyboard.keyHeight, resId, popup, popupReversed, -1, padding)
            } else null
        }

        fun getHintLabel(wantAscii: Boolean, wantAll: Boolean): String {
            if (hint == null) {
                hint = ""
                if (shiftLabel != null && !isSimpleUppercase) {
                    val c = shiftLabel!![0]
                    if (wantAll || wantAscii && is7BitAscii(c)) {
                        hint = c.toString()
                    }
                }
            }
            return hint!!
        }

        fun getAltHintLabel(wantAscii: Boolean, wantAll: Boolean): String {
            if (altHint == null) {
                altHint = ""
                val popup = getPopupKeyboardContent(false, false, false)
                if (popup.isNotEmpty()) {
                    val c = popup[0]
                    if (wantAll || wantAscii && is7BitAscii(c)) {
                        altHint = c.toString()
                    }
                }
            }
            return altHint!!
        }

        /**
         * Informs the key that it has been pressed, in case it needs to change its appearance or
         * state.
         * @see .onReleased
         */
        fun onPressed() {
            pressed = !pressed
        }

        /**
         * Changes the pressed state of the key. Sticky key indicators are handled explicitly elsewhere.
         * @param inside whether the finger was released inside the key
         * @see .onPressed
         */
        fun onReleased(inside: Boolean) {
            pressed = !pressed
        }

        fun parseCSV(value: String): IntArray {
            var count = 0
            var lastIndex = 0
            if (value.isNotEmpty()) {
                count++
                while (value.indexOf(",", lastIndex + 1).also { lastIndex = it } > 0) {
                    count++
                }
            }
            val values = IntArray(count)
            count = 0
            val st = StringTokenizer(value, ",")
            while (st.hasMoreTokens()) {
                try {
                    values[count++] = st.nextToken().toInt()
                } catch (nfe: NumberFormatException) {
                    Log.e(TAG, "Error parsing keycodes $value")
                }
            }
            return values
        }

        /**
         * Detects if a point falls inside this key.
         * @param x the x-coordinate of the point
         * @param y the y-coordinate of the point
         * @return whether or not the point falls inside the key. If the key is attached to an edge,
         * it will assume that all points between the key and the edge are considered to be inside
         * the key.
         */
        open fun isInside(x: Int, y: Int): Boolean {
            val leftEdge = edgeFlags and EDGE_LEFT > 0
            val rightEdge = edgeFlags and EDGE_RIGHT > 0
            val topEdge = edgeFlags and EDGE_TOP > 0
            val bottomEdge = edgeFlags and EDGE_BOTTOM > 0
            return ((x >= this.x || leftEdge && x <= this.x + width)
                    && (x < this.x + width || rightEdge && x >= this.x)
                    && (y >= this.y || topEdge && y <= this.y + height)
                    && (y < this.y + height || bottomEdge && y >= this.y))
        }

        /**
         * Returns the square of the distance between the center of the key and the given point.
         * @param x the x-coordinate of the point
         * @param y the y-coordinate of the point
         * @return the square of the distance of the point from the center of the key
         */
        open fun squaredDistanceFrom(x: Int, y: Int): Int {
            val xDist = this.x + width / 2 - x
            val yDist = this.y + height / 2 - y
            return xDist * xDist + yDist * yDist
        }

        open val currentDrawableState: IntArray?
            /**
             * Returns the drawable state for the key, based on the current state and type of the key.
             * @return the drawable state of the key.
             * @see android.graphics.drawable.StateListDrawable.setState
             */
            get() {
                var states = KEY_STATE_NORMAL
                if (locked) {
                    states = if (pressed) KEY_STATE_PRESSED_LOCK
                    else KEY_STATE_NORMAL_LOCK
                } else if (on) {
                    states = if (pressed) { KEY_STATE_PRESSED_ON }
                    else { KEY_STATE_NORMAL_ON }
                } else {
                    if (sticky) {
                        states = if (pressed) { KEY_STATE_PRESSED_OFF }
                        else { KEY_STATE_NORMAL_OFF }
                    } else {
                        if (pressed) { states = KEY_STATE_PRESSED }
                    }
                }
                return states
            }

        override fun toString(): String {
            val code = if (codes != null && codes!!.isNotEmpty()) codes!![0] else 0
            val edges = (if (edgeFlags and EDGE_LEFT != 0) "L" else "-") +
                    (if (edgeFlags and EDGE_RIGHT != 0) "R" else "-") +
                    (if (edgeFlags and EDGE_TOP != 0) "T" else "-") +
                    if (edgeFlags and EDGE_BOTTOM != 0) "B" else "-"
            return "KeyDebugFIXME(label=$label" +
                    (if (shiftLabel != null) " shift=$shiftLabel" else "") +
                    (if (capsLabel != null) " caps=$capsLabel" else "") +
                    (if (text != null) " text=$text" else "") +
                    " code=$code" +
                    (if (code <= 0 || Character.isWhitespace(code)) "" else ":'${code.toChar()}'") +
                    " x=$x..${(x + width)}= y=$y..${(y + height)}" +
                    " edgeFlags=$edges" +
                    (if (popupCharacters != null) " pop=$popupCharacters" else "") +
                    " res=$popupResId" +
                    ")"
        }

        companion object {
            private val KEY_STATE_NORMAL_ON = intArrayOf(
                    android.R.attr.state_checkable,
                    android.R.attr.state_checked
            )
            private val KEY_STATE_PRESSED_ON = intArrayOf(
                    android.R.attr.state_pressed,
                    android.R.attr.state_checkable,
                    android.R.attr.state_checked
            )
            private val KEY_STATE_NORMAL_LOCK = intArrayOf(
                    android.R.attr.state_active,
                    android.R.attr.state_checkable,
                    android.R.attr.state_checked
            )
            private val KEY_STATE_PRESSED_LOCK = intArrayOf(
                    android.R.attr.state_active,
                    android.R.attr.state_pressed,
                    android.R.attr.state_checkable,
                    android.R.attr.state_checked
            )
            private val KEY_STATE_NORMAL_OFF = intArrayOf(
                    android.R.attr.state_checkable
            )
            private val KEY_STATE_PRESSED_OFF = intArrayOf(
                    android.R.attr.state_pressed,
                    android.R.attr.state_checkable
            )
            private val KEY_STATE_NORMAL = intArrayOf()
            private val KEY_STATE_PRESSED = intArrayOf(
                    android.R.attr.state_pressed
            )

            private fun is7BitAscii(c: Char): Boolean {
                return if (c in 'A'..'Z' || c in 'a'..'z') false
                else c.code in 32..126
            }
        }
    }
    /**
     * Creates a keyboard from the given xml key layout file. Weeds out rows
     * that have a keyboard mode defined but don't match the specified mode.
     * @param context the application or service context
     * @param xmlLayoutResId the resource file that contains the keyboard layout and keys.
     * @param modeId keyboard mode identifier
     * @param kbHeightPercent height of the keyboard as percentage of screen height
     */
    init {
        val dm = context.resources.displayMetrics
        mDisplayWidth = dm.widthPixels
        screenHeight = dm.heightPixels
        Log.v(TAG, "keyboard's display metrics:$dm, mDisplayWidth=$mDisplayWidth")

        mDefaultHorizontalGap = 0f
        mDefaultWidth = mDisplayWidth.toFloat() / 10
        mDefaultVerticalGap = 0
        mDefaultHeight = defaultHeight // may be zero, to be adjusted below
        mKeyboardHeight = Math.round(screenHeight * kbHeightPercent / 100)
        //Log.i("PCKeyboard", "mDefaultHeight=" + mDefaultHeight + "(arg=" + defaultHeight + ")" + " kbHeight=" + mKeyboardHeight + " displayHeight="+mDisplayHeight+")");
        mKeys = ArrayList()
        mModifierKeys = ArrayList()
        mKeyboardMode = modeId
        mUseExtension = LatinIME.sKeyboardSettings.useExtension
        loadKeyboard(context, context.resources.getXml(xmlLayoutResId))
        setEdgeFlags()
        fixAltChars(LatinIME.sKeyboardSettings.inputLocale)
    }

    /**
     *
     * Creates a blank keyboard from the given resource file and populates it with the specified
     * characters in left-to-right, top-to-bottom fashion, using the specified number of columns.
     *
     *
     * If the specified number of columns is -1, then the keyboard will fit as many keys as
     * possible in each row.
     * @param context the application or service context
     * @param layoutTemplateResId the layout template file, containing no keys.
     * @param characters the list of characters to display on the keyboard. One key will be created
     * for each character.
     * @param columns the number of columns of keys to display. If this number is greater than the
     * number of keys that can fit in a row, it will be ignored. If this number is -1, the
     * keyboard will fit as many keys as possible in each row.
     */
    private constructor(
            context: Context, defaultHeight: Int, layoutTemplateResId: Int,
            characters: CharSequence, reversed: Boolean, columns: Int, horizontalPadding: Int
    ) : this(context, defaultHeight, layoutTemplateResId) {
        var x = 0
        var y = 0
        var column = 0
        mTotalWidth = 0
        val row = Row(this)
        row.defaultHeight = keyHeight
        row.defaultWidth = mDefaultWidth
        row.defaultHorizontalGap = mDefaultHorizontalGap
        row.verticalGap = verticalGap
        val maxColumns = if (columns == -1) Int.MAX_VALUE else columns
        mLayoutRows = 1
        val start = if (reversed) characters.length - 1 else 0
        val end = if (reversed) -1 else characters.length
        val step = if (reversed) -1 else 1
        var i = start
        while (i != end) {
            val c = characters[i]
            if (column >= maxColumns
                    || x + mDefaultWidth + horizontalPadding > mDisplayWidth
            ) {
                x = 0
                y += verticalGap + keyHeight
                column = 0
                ++mLayoutRows
            }
            val key = Key(row)
            key.x = x
            key.realX = x.toFloat()
            key.y = y
            key.label = c.toString()
            key.codes = key.getFromString(key.label)
            column++
            x += key.width + key.gap
            mKeys.add(key)
            if (x > mTotalWidth) {
                mTotalWidth = x
            }
            i += step
        }
        mTotalHeight = y + keyHeight
        mLayoutColumns = if (columns == -1) column else maxColumns
        setEdgeFlags()
    }

    private fun setEdgeFlags() {
        if (mRowCount == 0) mRowCount = 1 // Assume one row if not set
        var row = 0
        var prevKey: Key? = null
        var rowFlags = 0
        for (key in mKeys) {
            var keyFlags = 0
            if (prevKey == null || key.x <= prevKey.x) {
                // Start new row.
                if (prevKey != null) {
                    // Add "right edge" to rightmost key of previous row.
                    // Need to do the last key separately below.
                    prevKey.edgeFlags = prevKey.edgeFlags or EDGE_RIGHT
                }

                // Set the row flags for the current row.
                rowFlags = 0
                if (row == 0) rowFlags = EDGE_TOP
                if (row == mRowCount - 1) rowFlags = rowFlags or EDGE_BOTTOM
                ++row

                // Mark current key as "left edge"
                keyFlags = EDGE_LEFT
            }
            key.edgeFlags = rowFlags or keyFlags
            prevKey = key
        }
        // Fix up the last key
        if (prevKey != null) prevKey.edgeFlags = prevKey.edgeFlags or EDGE_RIGHT

//        Log.i(TAG, "setEdgeFlags() done:");
//        for (Key key : mKeys) {
//            Log.i(TAG, "key=" + key);
//        }
    }

    private fun fixAltChars(locale: Locale?) {
        var locale: Locale? = locale
        if (locale == null) locale = Locale.getDefault()
        val mainKeys = HashSet<Char>()
        for (key in mKeys) {
            // Remember characters on the main keyboard so that they can be removed from popups.
            // This makes it easy to share popup char maps between the normal and shifted
            // keyboards.
            if (key.label != null && !key.modifier && key.label!!.length == 1) {
                val c = key.label!![0]
                mainKeys.add(c)
            }
        }
        for (key in mKeys) {
            if (key.popupCharacters == null) continue
            var popupLen = key.popupCharacters!!.length
            if (popupLen == 0) {
                continue
            }
            if (key.x >= mTotalWidth / 2) {
                key.popupReversed = true
            }

            // Uppercase the alt chars if the main key is uppercase
            val needUpcase = key.label != null && key.label!!.length == 1 && Character.isUpperCase(
                    key.label!![0]
            )
            if (needUpcase) {
                key.popupCharacters = key.popupCharacters.toString().uppercase()
                popupLen = (key.popupCharacters as String).length
            }
            val newPopup = StringBuilder(popupLen)
            for (i in 0 until popupLen) {
                val c = key.popupCharacters!![i]
                if (Character.isDigit(c) && mainKeys.contains(c)) continue  // already present elsewhere

                // Skip extra digit alt keys on 5-row keyboards
                if (key.edgeFlags and EDGE_TOP == 0 && Character.isDigit(c)) continue
                newPopup.append(c)
            }
            //Log.i("PCKeyboard", "popup for " + key.label + " '" + key.popupCharacters + "' => '"+ newPopup + "' length " + newPopup.length());
            key.popupCharacters = newPopup.toString()
        }
    }

    val keys: List<Key>
        get() = mKeys
    val modifierKeys: List<Key?>
        get() = mModifierKeys
    protected var horizontalGap: Int
        get() = Math.round(mDefaultHorizontalGap)
        protected set(gap) {
            mDefaultHorizontalGap = gap.toFloat()
        }
    protected var verticalGap: Int
        get() = mDefaultVerticalGap
        protected set(gap) {
            mDefaultVerticalGap = gap
        }
    protected var keyHeight: Int
        get() = mDefaultHeight
        protected set(height) {
            mDefaultHeight = height
        }
    protected var keyWidth: Int
        get() = Math.round(mDefaultWidth)
        protected set(width) {
            mDefaultWidth = width.toFloat()
        }
    val minWidth get() = mTotalWidth
    val height get() = mTotalHeight

    fun setShiftState(shiftState: Int, updateKey: Boolean): Boolean {
        //Log.i(TAG, "setShiftState " + mShiftState + " -> " + shiftState);
        if (updateKey && mShiftKey != null) {
            mShiftKey!!.on = shiftState != SHIFT_OFF
        }
        if (this.shiftState != shiftState) {
            this.shiftState = shiftState
            return true
        }
        return false
    }

    open fun setShiftState(shiftState: Int): Boolean {
        return setShiftState(shiftState, true)
    }

    fun setCtrlIndicator(active: Boolean): Key? {
        //Log.i(TAG, "setCtrlIndicator " + active + " ctrlKey=" + mCtrlKey);
        if (mCtrlKey != null) mCtrlKey!!.on = active
        return mCtrlKey
    }

    fun setAltIndicator(active: Boolean): Key? {
        if (mAltKey != null) mAltKey!!.on = active
        return mAltKey
    }

    fun setMetaIndicator(active: Boolean): Key? {
        if (mMetaKey != null) mMetaKey!!.on = active
        return mMetaKey
    }

    val isShiftCaps: Boolean
        get() = shiftState == SHIFT_CAPS || shiftState == SHIFT_CAPS_LOCKED

    fun isShifted(applyCaps: Boolean): Boolean {
        return if (applyCaps) {
            shiftState != SHIFT_OFF
        } else {
            shiftState == SHIFT_ON || shiftState == SHIFT_LOCKED
        }
    }

    private fun computeNearestNeighbors() {
        // Round-up so we don't have any pixels outside the grid
        mCellWidth = (minWidth + mLayoutColumns - 1) / mLayoutColumns
        mCellHeight = (height + mLayoutRows - 1) / mLayoutRows
        mGridNeighbors = arrayOfNulls(mLayoutColumns * mLayoutRows)
        val indices = IntArray(mKeys.size)
        val gridWidth = mLayoutColumns * mCellWidth
        val gridHeight = mLayoutRows * mCellHeight
        var x = 0
        while (x < gridWidth) {
            var y = 0
            while (y < gridHeight) {
                var count = 0
                for (i in mKeys.indices) {
                    val key = mKeys[i]
                    val isSpace =
                            key.codes != null && key.codes!!.isNotEmpty() && key.codes!![0] == LatinIME.ASCII_SPACE
                    if (key.squaredDistanceFrom(x, y) < mProximityThreshold ||
                            key.squaredDistanceFrom(x + mCellWidth - 1, y) < mProximityThreshold ||
                            (key.squaredDistanceFrom(x + mCellWidth - 1, y + mCellHeight - 1) < mProximityThreshold) ||
                            key.squaredDistanceFrom(x, y + mCellHeight - 1) < mProximityThreshold ||
                            isSpace && !(
                                    x + mCellWidth - 1 < key.x ||
                                            x > key.x + key.width ||
                                            y + mCellHeight - 1 < key.y ||
                                            y > key.y + key.height
                                    )
                    ) {
                        //if (isSpace) Log.i(TAG, "space at grid" + x + "," + y);
                        indices[count++] = i
                    }
                }
                val cell = IntArray(count)
                System.arraycopy(indices, 0, cell, 0, count)
                mGridNeighbors!![y / mCellHeight * mLayoutColumns + x / mCellWidth] = cell
                y += mCellHeight
            }
            x += mCellWidth
        }
    }

    /**
     * Returns the indices of the keys that are closest to the given point.
     * @param x the x-coordinate of the point
     * @param y the y-coordinate of the point
     * @return the array of integer indices for the nearest keys to the given point. If the given
     * point is out of range, then an array of size zero is returned.
     */
    open fun getNearestKeys(x: Int, y: Int): IntArray? {
        if (mGridNeighbors == null) computeNearestNeighbors()
        if (x in 0..<minWidth && y >= 0 && y < height) {
            val index = y / mCellHeight * mLayoutColumns + x / mCellWidth
            if (index < mLayoutRows * mLayoutColumns) {
                return mGridNeighbors!![index]
            }
        }
        return IntArray(0)
    }

    protected fun createRowFromXml(res: Resources, parser: XmlResourceParser?): Row {
        return Row(res, this, parser)
    }

    protected open fun createKeyFromXml(
            res: Resources, parent: Row?, x: Int, y: Int,
            parser: XmlResourceParser?
    ): Key = Key(res, parent, x, y, parser)

    private fun loadKeyboard(context: Context, parser: XmlResourceParser) {
        var inKey = false
        var inRow = false
        var x = 0f
        var y = 0
        var key: Key? = null
        var currentRow: Row? = null
        val res = context.resources
        var skipRow: Boolean
        mRowCount = 0
        try {
            var event: Int
            var prevKey: Key? = null
            while (parser.next().also { event = it } != XmlResourceParser.END_DOCUMENT) {
                if (event == XmlResourceParser.START_TAG) {
                    val tag = parser.name
                    if (TAG_ROW == tag) {
                        inRow = true
                        x = 0f
                        currentRow = createRowFromXml(res, parser)
                        skipRow = currentRow.mode != 0 && currentRow.mode != mKeyboardMode
                        if (currentRow.extension) {
                            if (mUseExtension) {
                                ++mExtensionRowCount
                            } else {
                                skipRow = true
                            }
                        }
                        if (skipRow) {
                            skipToEndOfRow(parser)
                            inRow = false
                        }
                    } else if (TAG_KEY == tag) {
                        inKey = true
                        key = createKeyFromXml(res, currentRow, Math.round(x), y, parser)
                        key.realX = x
                        if (key.codes == null) {
                            // skip this key, adding its width to the previous one
                            if (prevKey != null) {
                                prevKey.width += key.width
                            }
                        } else {
                            mKeys.add(key)
                            prevKey = key
                            if (key.codes!![0] == KEYCODE_SHIFT) {
                                if (shiftKeyIndex == -1) {
                                    mShiftKey = key
                                    shiftKeyIndex = mKeys.size - 1
                                }
                                mModifierKeys.add(key)
                            } else if (key.codes!![0] == KEYCODE_ALT_SYM) {
                                mModifierKeys.add(key)
                            } else if (key.codes!![0] == LatinKeyboardView.KEYCODE_CTRL_LEFT) {
                                mCtrlKey = key
                            } else if (key.codes!![0] == LatinKeyboardView.KEYCODE_ALT_LEFT) {
                                mAltKey = key
                            } else if (key.codes!![0] == LatinKeyboardView.KEYCODE_META_LEFT) {
                                mMetaKey = key
                            }
                        }
                    } else if (TAG_KEYBOARD == tag) {
                        parseKeyboardAttributes(res, parser)
                    }
                } else if (event == XmlResourceParser.END_TAG) {
                    if (inKey) {
                        inKey = false
                        x += key!!.realGap + key.realWidth
                        if (x > mTotalWidth) {
                            mTotalWidth = Math.round(x)
                        }
                    } else if (inRow) {
                        inRow = false
                        y += currentRow!!.verticalGap
                        y += currentRow.defaultHeight
                        mRowCount++
                    } else {
                        // TODO: error or extend?
                    }
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Parse error:$e")
            e.printStackTrace()
        }
        mTotalHeight = y - verticalGap
    }

    fun setKeyboardWidth(newWidth: Int) {
        Log.i(TAG, "setKeyboardWidth newWidth=$newWidth, mTotalWidth=$mTotalWidth")
        if (newWidth <= 0) return  // view not initialized?
        if (mTotalWidth <= newWidth) return  // it already fits
        val scale = newWidth.toFloat() / mDisplayWidth
        Log.i("PCKeyboard", "Rescaling keyboard: $mTotalWidth => $newWidth")
        for (key in mKeys) {
            key.x = Math.round(key.realX * scale)
        }
        mTotalWidth = newWidth
    }

    @Throws(XmlPullParserException::class, IOException::class)
    private fun skipToEndOfRow(parser: XmlResourceParser) {
        var event: Int
        while (parser.next().also { event = it } != XmlResourceParser.END_DOCUMENT) {
            if (event == XmlResourceParser.END_TAG && parser.name == TAG_ROW) {
                break
            }
        }
    }

    private fun parseKeyboardAttributes(res: Resources, parser: XmlResourceParser) {
        val a = res.obtainAttributes(Xml.asAttributeSet(parser), R.styleable.Keyboard)

        mDefaultWidth = getDimensionOrFraction(
                a, R.styleable.Keyboard_keyWidth,
                mDisplayWidth, mDisplayWidth.toFloat() / 10
        )
        keyHeight = Math.round(
                getDimensionOrFraction(
                        a, R.styleable.Keyboard_keyHeight,
                        screenHeight, keyHeight.toFloat()
                )
        )
        mDefaultHorizontalGap = getDimensionOrFraction(
                a, R.styleable.Keyboard_horizontalGap,
                mDisplayWidth, 0f
        )
        verticalGap = Math.round(
                getDimensionOrFraction(
                        a, R.styleable.Keyboard_verticalGap,
                        screenHeight, 0f
                )
        )
        mHorizontalPad = getDimensionOrFraction(
                a, R.styleable.Keyboard_horizontalPad,
                mDisplayWidth, res.getDimension(R.dimen.key_horizontal_pad)
        )
        mVerticalPad = getDimensionOrFraction(
                a, R.styleable.Keyboard_verticalPad,
                screenHeight, res.getDimension(R.dimen.key_vertical_pad)
        )
        mLayoutRows = a.getInteger(R.styleable.Keyboard_layoutRows, DEFAULT_LAYOUT_ROWS)
        mLayoutColumns = a.getInteger(R.styleable.Keyboard_layoutColumns, DEFAULT_LAYOUT_COLUMNS)
        if (keyHeight == 0 && mKeyboardHeight > 0 && mLayoutRows > 0) {
            keyHeight = mKeyboardHeight / mLayoutRows
            //Log.i(TAG, "got mLayoutRows=" + mLayoutRows + ", mDefaultHeight=" + mDefaultHeight);
        }
        mProximityThreshold = (mDefaultWidth * SEARCH_DISTANCE).toInt()
        mProximityThreshold *= mProximityThreshold // Square it for comparison
        a.recycle()
    }

    override fun toString(): String {
        return "Keyboard(${mLayoutColumns}x${mLayoutRows}" +
                " keys=${mKeys.size}" +
                " rowCount=$mRowCount" +
                " mode=$mKeyboardMode" +
                " size=${mTotalWidth}x${mTotalHeight}" +
                ")"
    }

    companion object {
        const val TAG = "Keyboard"
        const val DEAD_KEY_PLACEHOLDER = 0x25cc // dotted small circle
                .toChar()
        const val DEAD_KEY_PLACEHOLDER_STRING = DEAD_KEY_PLACEHOLDER.toString()

        // Keyboard XML Tags
        private const val TAG_KEYBOARD = "Keyboard"
        private const val TAG_ROW = "Row"
        private const val TAG_KEY = "Key"
        const val EDGE_LEFT = 0x01
        const val EDGE_RIGHT = 0x02
        const val EDGE_TOP = 0x04
        const val EDGE_BOTTOM = 0x08
        const val KEYCODE_SHIFT = -1
        const val KEYCODE_MODE_CHANGE = -2
        const val KEYCODE_CANCEL = -3
        const val KEYCODE_DONE = -4
        const val KEYCODE_DELETE = -5
        const val KEYCODE_ALT_SYM = -6

        // Backwards compatible setting to avoid having to change all the kbd_qwerty files
        const val DEFAULT_LAYOUT_ROWS = 4
        const val DEFAULT_LAYOUT_COLUMNS = 10

        // Flag values for popup key contents. Keep in sync with strings.xml values.
        const val POPUP_ADD_SHIFT = 1
        const val POPUP_ADD_CASE = 2
        const val POPUP_ADD_SELF = 4
        const val POPUP_DISABLE = 256
        const val POPUP_AUTOREPEAT = 512
        const val SHIFT_OFF = 0
        const val SHIFT_ON = 1
        const val SHIFT_LOCKED = 2
        const val SHIFT_CAPS = 3
        const val SHIFT_CAPS_LOCKED = 4

        /** Number of key widths from current touch point to search for nearest keys.  */
        private const val SEARCH_DISTANCE = 1.8f
        fun getDimensionOrFraction(a: TypedArray, index: Int, base: Int, defValue: Float): Float {
            val value = a.peekValue(index) ?: return defValue
            if (value.type == TypedValue.TYPE_DIMENSION) {
                return a.getDimensionPixelOffset(index, Math.round(defValue)).toFloat()
            } else if (value.type == TypedValue.TYPE_FRACTION) {
                // Round it to avoid values like 47.9999 from getting truncated
                //return Math.round(a.getFraction(index, base, base, defValue));
                return a.getFraction(index, base, base, defValue)
            }
            return defValue
        }
    }
}
