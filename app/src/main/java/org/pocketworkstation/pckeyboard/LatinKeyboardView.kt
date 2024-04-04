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
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.util.AttributeSet
import android.util.Log
import android.view.LayoutInflater
import android.view.MotionEvent
import android.widget.PopupWindow
import android.widget.TextView

class LatinKeyboardView @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet?,
    defStyle: Int = 0
) : LatinKeyboardBaseView(context, attrs, defStyle) {
    private var mPhoneKeyboard: Keyboard? = null

    /** Whether the extension of this keyboard is visible  */
    private var mExtensionVisible = false

    /** The view that is shown as an extension of this keyboard view  */
    private var mExtension: LatinKeyboardView? = null

    /** The popup window that contains the extension of this keyboard  */
    private var mExtensionPopup: PopupWindow? = null

    /** Whether this view is an extension of another keyboard  */
    private var mIsExtensionType = false
    private var mFirstEvent = false

    /** Whether we've started dropping move events because we found a big jump  */
    private var mDroppingEvents = false

    /**
     * Whether multi-touch disambiguation needs to be disabled for any reason. There are 2 reasons
     * for this to happen - (1) if a real multi-touch event has occured and (2) we've opened an
     * extension keyboard.
     */
    private var mDisableDisambiguation = false

    /** The distance threshold at which we start treating the touch session as a multi-touch  */
    private var mJumpThresholdSquare = Int.MAX_VALUE

    /** The y coordinate of the last row  */
    private var mLastRowY = 0
    private var mExtensionLayoutResId = 0
    private var mExtensionKeyboard: LatinKeyboard? = null

    fun setPhoneKeyboard(phoneKeyboard: Keyboard?) {
        mPhoneKeyboard = phoneKeyboard
    }

    fun setExtensionLayoutResId(id: Int) {
        mExtensionLayoutResId = id
    }

    override var isPreviewEnabled: Boolean
        get() = super.isPreviewEnabled
        set(previewEnabled) {
            if (keyboard === mPhoneKeyboard) {
                // Phone keyboard never shows popup preview (except language switch).
                super.isPreviewEnabled = false
            } else {
                super.isPreviewEnabled = previewEnabled
            }
        }

    override var keyboard: Keyboard?
        get() = super.keyboard
        set(newKeyboard) {
            val oldKeyboard = keyboard
            if (oldKeyboard is LatinKeyboard) {
                // Reset old keyboard state before switching to new keyboard.
                oldKeyboard.keyReleased()
            }
            super.keyboard = newKeyboard
            // One-seventh of the keyboard width seems like a reasonable threshold
            mJumpThresholdSquare = newKeyboard!!.minWidth / 7
            mJumpThresholdSquare *= mJumpThresholdSquare
            // Get Y coordinate of the last row based on the row count, assuming equal height
            val numRows = newKeyboard.mRowCount
            mLastRowY = newKeyboard.height * (numRows - 1) / numRows
            mExtensionKeyboard = (newKeyboard as LatinKeyboard).extension
            if (mExtensionKeyboard != null && mExtension != null)
                mExtension!!.keyboard = mExtensionKeyboard
            setKeyboardLocal(newKeyboard)
        }

    override fun enableSlideKeyHack(): Boolean {
        return true
    }

    override fun onLongPress(popupKey: Keyboard.Key): Boolean {
        PointerTracker.clearSlideKeys()

        val primaryCode = popupKey.codes!![0]
        return if (primaryCode == KEYCODE_OPTIONS) {
            invokeOnKey(KEYCODE_OPTIONS_LONGPRESS)
        } else if (primaryCode == KEYCODE_DPAD_CENTER) {
            invokeOnKey(KEYCODE_COMPOSE)
        } else if (primaryCode == '0'.code && keyboard === mPhoneKeyboard) {
            // Long pressing on 0 in phone number keypad gives you a '+'.
            invokeOnKey('+'.code)
        } else {
            super.onLongPress(popupKey)
        }
    }

    private fun invokeOnKey(primaryCode: Int): Boolean {
        onKeyboardActionListener!!.onKey(
            primaryCode, null,
            NOT_A_TOUCH_COORDINATE,
            NOT_A_TOUCH_COORDINATE
        )
        return true
    }

    /**
     * This function checks to see if we need to handle any sudden jumps in the pointer location
     * that could be due to a multi-touch being treated as a move by the firmware or hardware.
     * Once a sudden jump is detected, all subsequent move events are discarded
     * until an UP is received.<P>
     * When a sudden jump is detected, an UP event is simulated at the last position and when
     * the sudden moves subside, a DOWN event is simulated for the second key.
     * @param me the motion event
     * @return true if the event was consumed, so that it doesn't continue to be handled by
     * KeyboardView.
    </P> */
    private fun handleSuddenJump(me: MotionEvent): Boolean {
        val action = me.action
        val x = me.x.toInt()
        val y = me.y.toInt()
        var result = false

        // Real multi-touch event? Stop looking for sudden jumps
        if (me.pointerCount > 1) {
            mDisableDisambiguation = true
        }
        if (mDisableDisambiguation) {
            // If UP, reset the multi-touch flag
            if (action == MotionEvent.ACTION_UP) mDisableDisambiguation = false
            return false
        }

        when (action) {
            MotionEvent.ACTION_DOWN ->
                mDroppingEvents = false // Reset the "session"

            MotionEvent.ACTION_MOVE -> {
                // Is this a big jump?
                val distanceSquare = (mLastX - x) * (mLastX - x) + (mLastY - y) * (mLastY - y)
                // Check the distance and also if the move is not entirely within the bottom row
                // If it's only in the bottom row, it might be an intentional slide gesture
                // for language switching
                if (distanceSquare > mJumpThresholdSquare
                    && (mLastY < mLastRowY || y < mLastRowY)
                ) {
                    // If we're not yet dropping events, start dropping and send an UP event
                    if (!mDroppingEvents) {
                        mDroppingEvents = true
                        // Send an up event
                        val translated = MotionEvent.obtain(
                            me.eventTime, me.eventTime,
                            MotionEvent.ACTION_UP,
                            mLastX.toFloat(), mLastY.toFloat(), me.metaState
                        )
                        super.onTouchEvent(translated)
                        translated.recycle()
                    }
                    result = true
                } else if (mDroppingEvents) {
                    // If moves are small and we're already dropping events, continue dropping
                    result = true
                }
            }

            MotionEvent.ACTION_UP -> if (mDroppingEvents) {
                // Send a down event first, as we dropped a bunch of sudden jumps and assume that
                // the user is releasing the touch on the second key.
                val translated = MotionEvent.obtain(
                    me.eventTime, me.eventTime,
                    MotionEvent.ACTION_DOWN,
                    x.toFloat(), y.toFloat(), me.metaState
                )
                super.onTouchEvent(translated)
                translated.recycle()
                mDroppingEvents = false
                // Let the up event get processed as well, result = false
            }
        }
        // Track the previous coordinate
        mLastX = x
        mLastY = y
        return result
    }

    override fun onTouchEvent(me: MotionEvent): Boolean {
        val keyboard = keyboard as LatinKeyboard
        if (LatinIME.sKeyboardSettings.showTouchPos || DEBUG_LINE) {
            mLastX = me.x.toInt()
            mLastY = me.y.toInt()
            invalidate()
        }

        // If an extension keyboard is visible or this is an extension keyboard, don't look
        // for sudden jumps. Otherwise, if there was a sudden jump, return without processing the
        // actual motion event.
        if (!mExtensionVisible && !mIsExtensionType && handleSuddenJump(me))
            return true
        // Reset any bounding box controls in the keyboard
        if (me.action == MotionEvent.ACTION_DOWN) {
            keyboard.keyReleased()
        }

        if (me.action == MotionEvent.ACTION_UP) {
            val languageDirection = keyboard.languageChangeDirection
            if (languageDirection != 0) {
                onKeyboardActionListener!!.onKey(
                    if (languageDirection == 1) KEYCODE_NEXT_LANGUAGE else KEYCODE_PREV_LANGUAGE,
                    null, mLastX, mLastY
                )
                me.action = MotionEvent.ACTION_CANCEL
                keyboard.keyReleased()
                return super.onTouchEvent(me)
            }
        }

        // If we don't have an extension keyboard, don't go any further.
        if (keyboard.extension == null) {
            return super.onTouchEvent(me)
        }
        // If the motion event is above the keyboard and it's not an UP event coming
        // even before the first MOVE event into the extension area
        return if (me.y < 0 && (mExtensionVisible || me.action != MotionEvent.ACTION_UP)) {
            if (mExtensionVisible) {
                var action = me.action
                if (mFirstEvent) action = MotionEvent.ACTION_DOWN
                mFirstEvent = false
                val translated = MotionEvent.obtain(
                    me.eventTime, me.eventTime,
                    action,
                    me.x, me.y + mExtension!!.height, me.metaState
                )
                if (me.actionIndex > 0) return true // ignore second touches to avoid "pointerIndex out of range"
                val result = mExtension!!.onTouchEvent(translated)
                translated.recycle()
                if (me.action == MotionEvent.ACTION_UP || me.action == MotionEvent.ACTION_CANCEL) {
                    closeExtension()
                }
                result
            } else {
                if (swipeUp()) {
                    return true
                } else if (openExtension()) {
                    val cancel = MotionEvent.obtain(
                        me.downTime, me.eventTime,
                        MotionEvent.ACTION_CANCEL, me.x - 100, me.y - 100, 0
                    )
                    super.onTouchEvent(cancel)
                    cancel.recycle()
                    if (mExtension!!.height > 0) {
                        val translated = MotionEvent.obtain(
                            me.eventTime,
                            me.eventTime,
                            MotionEvent.ACTION_DOWN,
                            me.x, me.y + mExtension!!.height,
                            me.metaState
                        )
                        mExtension!!.onTouchEvent(translated)
                        translated.recycle()
                    } else {
                        mFirstEvent = true
                    }
                    // Stop processing multi-touch errors
                    mDisableDisambiguation = true
                }
                true
            }
        } else if (mExtensionVisible) {
            closeExtension()
            // Send a down event into the main keyboard first
            val down = MotionEvent.obtain(
                me.eventTime, me.eventTime,
                MotionEvent.ACTION_DOWN,
                me.x, me.y, me.metaState
            )
            super.onTouchEvent(down, true)
            down.recycle()
            // Send the actual event
            super.onTouchEvent(me)
        } else {
            super.onTouchEvent(me)
        }
    }

    private fun setExtensionType(isExtensionType: Boolean) {
        mIsExtensionType = isExtensionType
    }

    private fun openExtension(): Boolean {
        // If the current keyboard is not visible, or if the mini keyboard is active, don't show the popup
        if (!isShown() || popupKeyboardIsShowing()) {
            return false
        }
        PointerTracker.clearSlideKeys()
        if ((keyboard as LatinKeyboard).extension == null) return false
        makePopupWindow()
        mExtensionVisible = true
        return true
    }

    private fun makePopupWindow() {
        dismissPopupKeyboard()
        if (mExtensionPopup == null) {
            val windowLocation = IntArray(2)
            mExtensionPopup = PopupWindow(context)
            mExtensionPopup!!.setBackgroundDrawable(null)
            val li = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
            mExtension = li.inflate(
                if (mExtensionLayoutResId == 0) R.layout.input_trans else mExtensionLayoutResId,
                null
            ) as LatinKeyboardView
            val keyboard: Keyboard? = mExtensionKeyboard
            mExtension!!.keyboard = keyboard!!
            mExtension!!.setExtensionType(true)
            mExtension!!.setPadding(0, 0, 0, 0)
            mExtension!!.onKeyboardActionListener =
                ExtensionKeyboardListener(onKeyboardActionListener!!)
            mExtension!!.setPopupParent(this)
            mExtension!!.setPopupOffset(0, -windowLocation[1])
            mExtensionPopup!!.setContentView(mExtension)
            mExtensionPopup!!.width = width
            mExtensionPopup!!.height = keyboard.height
            mExtensionPopup!!.animationStyle = -1
            getLocationInWindow(windowLocation)
            // TODO: Fix the "- 30". 
            mExtension!!.setPopupOffset(0, -windowLocation[1] - 30)
            mExtensionPopup!!.showAtLocation(
                this,
                0,
                0,
                -keyboard.height + windowLocation[1] + this.paddingTop
            )
        } else {
            mExtension!!.visibility = VISIBLE
        }
        mExtension!!.setShiftState(shiftState) // propagate shift state
    }

    override fun closing() {
        super.closing()
        if (mExtensionPopup != null && mExtensionPopup!!.isShowing) {
            mExtensionPopup!!.dismiss()
            mExtensionPopup = null
        }
    }

    private fun closeExtension() {
        mExtension!!.closing()
        mExtension!!.visibility = INVISIBLE
        mExtensionVisible = false
    }

    private class ExtensionKeyboardListener(private val mTarget: OnKeyboardActionListener) :
        OnKeyboardActionListener {
        override fun onKey(primaryCode: Int, keyCodes: IntArray?, x: Int, y: Int) {
            mTarget.onKey(primaryCode, keyCodes, x, y)
        }

        override fun onPress(primaryCode: Int) {
            mTarget.onPress(primaryCode)
        }

        override fun onRelease(primaryCode: Int) {
            mTarget.onRelease(primaryCode)
        }

        override fun onText(text: CharSequence?) {
            mTarget.onText(text)
        }

        override fun onCancel() {
            mTarget.onCancel()
        }

        override fun swipeDown(): Boolean {
            // Don't pass through
            return true
        }

        override fun swipeLeft(): Boolean {
            // Don't pass through
            return true
        }

        override fun swipeRight(): Boolean {
            // Don't pass through
            return true
        }

        override fun swipeUp(): Boolean {
            // Don't pass through
            return true
        }
    }

    var mHandler2: Handler? = null
    private var mStringToPlay: String? = null
    private var mStringIndex = 0
    private var mDownDelivered = false
    private val mAsciiKeys = arrayOfNulls<Keyboard.Key>(256)
    private var mPlaying = false
    private var mLastX = 0
    private var mLastY = 0
    private var mPaint: Paint? = null

    init {

        // TODO(klausw): migrate attribute styles to LatinKeyboardView?
        val a = context.obtainStyledAttributes(
            attrs, R.styleable.LatinKeyboardBaseView, defStyle, R.style.LatinKeyboardBaseView
        )
        val inflate = context.getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater

        var previewLayout = 0
        val n = a.getIndexCount()
        for (i in 0 until n) {
            when(val attr = a.getIndex(i)) {
                R.styleable.LatinKeyboardBaseView_keyPreviewLayout -> {
                    previewLayout = a.getResourceId(attr, 0)
                    if (previewLayout == R.layout.null_layout) previewLayout = 0
                }
                R.styleable.LatinKeyboardBaseView_keyPreviewOffset ->
                    mPreviewOffset = a.getDimensionPixelOffset(attr, 0)

                R.styleable.LatinKeyboardBaseView_keyPreviewHeight ->
                    mPreviewHeight = a.getDimensionPixelSize(attr, 80)

                R.styleable.LatinKeyboardBaseView_popupLayout -> {
                    mPopupLayout = a.getResourceId(attr, 0)
                    if (mPopupLayout == R.layout.null_layout) mPopupLayout = 0
                }
            }
        }
        a.recycle()

        val res = resources

        // If true, popups are forced to remain inside the keyboard area. If false,
        // they can extend above it. Enable clipping just for Android P since drawing
        // outside the keyboard area doesn't work on that version.
        val clippingEnabled = Build.VERSION.SDK_INT >= 28 /* Build.VERSION_CODES.P */

        if (previewLayout != 0) {
            mPreviewPopup = PopupWindow(context)
            if (!isInEditMode) Log.i(TAG, "new mPreviewPopup $mPreviewPopup from $this")
            mPreviewText = inflate.inflate(previewLayout, null) as TextView
            mPreviewTextSizeLarge = res.getDimension(R.dimen.key_preview_text_size_large).toInt()
            mPreviewPopup!!.setContentView(mPreviewText)
            mPreviewPopup!!.setBackgroundDrawable(null)
            mPreviewPopup!!.isTouchable = false
            mPreviewPopup!!.animationStyle = R.style.KeyPreviewAnimation
            mPreviewPopup!!.isClippingEnabled = clippingEnabled
        } else {
            mShowPreview = false
        }
        if (mPopupLayout != 0) {
            mMiniKeyboardParent = this
            mMiniKeyboardPopup = PopupWindow(context)
            if (!isInEditMode) Log.i(TAG, "new mMiniKeyboardPopup $mMiniKeyboardPopup from $this")
            mMiniKeyboardPopup!!.setBackgroundDrawable(null)
            mMiniKeyboardPopup!!.animationStyle = R.style.MiniKeyboardAnimation
            mMiniKeyboardPopup!!.isClippingEnabled = clippingEnabled
            mMiniKeyboardVisible = false
        }
    }

    private fun setKeyboardLocal(k: Keyboard) {
        if (DEBUG_AUTO_PLAY) {
            findKeys()
            if (mHandler2 == null) {
                mHandler2 = object : Handler(Looper.getMainLooper()) {
                    override fun handleMessage(msg: Message) {
                        removeMessages(MSG_TOUCH_DOWN)
                        removeMessages(MSG_TOUCH_UP)
                        if (!mPlaying) return
                        when (msg.what) {
                            MSG_TOUCH_DOWN -> {
                                if (mStringIndex >= mStringToPlay!!.length) {
                                    mPlaying = false
                                    return
                                }
                                var c = mStringToPlay!![mStringIndex]
                                while (c.code > 255 || mAsciiKeys[c.code] == null) {
                                    mStringIndex++
                                    if (mStringIndex >= mStringToPlay!!.length) {
                                        mPlaying = false
                                        return
                                    }
                                    c = mStringToPlay!![mStringIndex]
                                }
                                val x = mAsciiKeys[c.code]!!.x + 10
                                val y = mAsciiKeys[c.code]!!.y + 26
                                val me = MotionEvent.obtain(
                                    SystemClock.uptimeMillis(),
                                    SystemClock.uptimeMillis(),
                                    MotionEvent.ACTION_DOWN, x.toFloat(), y.toFloat(), 0
                                )
                                dispatchTouchEvent(me)
                                me.recycle()
                                sendEmptyMessageDelayed(
                                    MSG_TOUCH_UP,
                                    500
                                ) // Deliver up in 500ms if nothing else
                                // happens
                                mDownDelivered = true
                            }

                            MSG_TOUCH_UP -> {
                                val cUp = mStringToPlay!![mStringIndex]
                                val x2 = mAsciiKeys[cUp.code]!!.x + 10
                                val y2 = mAsciiKeys[cUp.code]!!.y + 26
                                mStringIndex++
                                val me2 = MotionEvent.obtain(
                                    SystemClock.uptimeMillis(),
                                    SystemClock.uptimeMillis(),
                                    MotionEvent.ACTION_UP, x2.toFloat(), y2.toFloat(), 0
                                )
                                dispatchTouchEvent(me2)
                                me2.recycle()
                                sendEmptyMessageDelayed(
                                    MSG_TOUCH_DOWN,
                                    500
                                ) // Deliver up in 500ms if nothing else
                                // happens
                                mDownDelivered = false
                            }
                        }
                    }
                }
            }
        }
    }

    private fun findKeys() {
        val keys = keyboard!!.keys
        // Get the keys on this keyboard
        for (i in keys.indices) {
            val code = keys[i].codes!![0]
            if (code in 0..255) {
                mAsciiKeys[code] = keys[i]
            }
        }
    }

    fun startPlaying(s: String?) {
        if (DEBUG_AUTO_PLAY) {
            if (s == null) return
            mStringToPlay = s.lowercase()
            mPlaying = true
            mDownDelivered = false
            mStringIndex = 0
            mHandler2!!.sendEmptyMessageDelayed(MSG_TOUCH_DOWN, 10)
        }
    }

    override fun draw(c: Canvas) {
        LatinIMEUtil.GCUtils.instance.reset()
        var tryGC = true
        var i = 0
        while (i < LatinIMEUtil.GCUtils.GC_TRY_LOOP_MAX && tryGC) {
            tryGC = try {
                super.draw(c)
                false
            } catch (e: OutOfMemoryError) {
                LatinIMEUtil.GCUtils.instance.tryGCOrWait("LatinKeyboardView", e)
            }
            ++i
        }
        if (DEBUG_AUTO_PLAY) {
            if (mPlaying) {
                mHandler2!!.removeMessages(MSG_TOUCH_DOWN)
                mHandler2!!.removeMessages(MSG_TOUCH_UP)
                if (mDownDelivered) {
                    mHandler2!!.sendEmptyMessageDelayed(MSG_TOUCH_UP, 20)
                } else {
                    mHandler2!!.sendEmptyMessageDelayed(MSG_TOUCH_DOWN, 20)
                }
            }
        }
        if (LatinIME.sKeyboardSettings.showTouchPos || DEBUG_LINE) {
            if (mPaint == null) {
                mPaint = Paint()
                mPaint!!.setColor(-0x7f000001)
                mPaint!!.isAntiAlias = false
            }
            c.drawLine(mLastX.toFloat(), 0f, mLastX.toFloat(), height.toFloat(), mPaint!!)
            c.drawLine(0f, mLastY.toFloat(), width.toFloat(), mLastY.toFloat(), mPaint!!)
        }
    }

    companion object {
        const val TAG = "HK/LatinKeyboardView"

        // The keycode list needs to stay in sync with the
        // res/values/keycodes.xml file.
        // FIXME: The following keycodes should really be renumbered
        // since they conflict with existing KeyEvent keycodes.
        const val KEYCODE_OPTIONS = -100
        const val KEYCODE_OPTIONS_LONGPRESS = -101
        const val KEYCODE_VOICE = -102
        const val KEYCODE_F1 = -103
        const val KEYCODE_NEXT_LANGUAGE = -104
        const val KEYCODE_PREV_LANGUAGE = -105
        const val KEYCODE_COMPOSE = -10024

        // The following keycodes match (negative) KeyEvent keycodes.
        // Would be better to use the real KeyEvent values, but many
        // don't exist prior to the Honeycomb API (level 11).
        const val KEYCODE_DPAD_UP = -19
        const val KEYCODE_DPAD_DOWN = -20
        const val KEYCODE_DPAD_LEFT = -21
        const val KEYCODE_DPAD_RIGHT = -22
        const val KEYCODE_DPAD_CENTER = -23
        const val KEYCODE_ALT_LEFT = -57
        const val KEYCODE_PAGE_UP = -92
        const val KEYCODE_PAGE_DOWN = -93
        const val KEYCODE_ESCAPE = -111
        const val KEYCODE_FORWARD_DEL = -112
        const val KEYCODE_CTRL_LEFT = -113
        const val KEYCODE_CAPS_LOCK = -115
        const val KEYCODE_SCROLL_LOCK = -116
        const val KEYCODE_META_LEFT = -117
        const val KEYCODE_FN = -119
        const val KEYCODE_SYSRQ = -120
        const val KEYCODE_BREAK = -121
        const val KEYCODE_HOME = -122
        const val KEYCODE_END = -123
        const val KEYCODE_INSERT = -124
        const val KEYCODE_FKEY_F1 = -131
        const val KEYCODE_FKEY_F2 = -132
        const val KEYCODE_FKEY_F3 = -133
        const val KEYCODE_FKEY_F4 = -134
        const val KEYCODE_FKEY_F5 = -135
        const val KEYCODE_FKEY_F6 = -136
        const val KEYCODE_FKEY_F7 = -137
        const val KEYCODE_FKEY_F8 = -138
        const val KEYCODE_FKEY_F9 = -139
        const val KEYCODE_FKEY_F10 = -140
        const val KEYCODE_FKEY_F11 = -141
        const val KEYCODE_FKEY_F12 = -142
        const val KEYCODE_NUM_LOCK = -143

        /****************************  INSTRUMENTATION   */
        const val DEBUG_AUTO_PLAY = false
        const val DEBUG_LINE = false
        private const val MSG_TOUCH_DOWN = 1
        private const val MSG_TOUCH_UP = 2
    }
}
