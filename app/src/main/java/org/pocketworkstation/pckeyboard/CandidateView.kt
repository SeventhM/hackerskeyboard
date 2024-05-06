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
import android.graphics.Paint.Align
import android.graphics.Rect
import android.graphics.Typeface
import android.graphics.drawable.Drawable
import android.os.Build
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.GestureDetector.SimpleOnGestureListener
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.PopupWindow
import android.widget.TextView
import androidx.core.content.ContextCompat
import java.util.Arrays
import kotlin.math.max
import kotlin.math.min

/**
 * Construct a CandidateView for showing suggested words for completion.
 * @param context
 * @param attrs
 */
class CandidateView(context: Context, attrs: AttributeSet?) : View(context, attrs) {
    private var mService: LatinIME? = null
    private val mSuggestions = ArrayList<CharSequence>()
    private var mShowingCompletions = false
    private var mSelectedString: CharSequence? = null
    private var mSelectedIndex = 0
    private var mTouchX = OUT_OF_BOUNDS_X_COORD
    private val mSelectionHighlight: Drawable?
    private var mTypedWordValid = false
    private var mHaveMinimalSuggestion = false
    private var mBgPadding: Rect? = null
    private val mPreviewText: TextView
    private val mPreviewPopup: PopupWindow
    private var mCurrentWordIndex = 0
    private val mDivider: Drawable?
    private val mWordWidth = IntArray(MAX_SUGGESTIONS)
    private val mWordX = IntArray(MAX_SUGGESTIONS)
    private var mPopupPreviewX = 0
    private var mPopupPreviewY = 0
    private val mColorNormal: Int
    private val mColorRecommended: Int
    private val mColorOther: Int
    private val mPaint: Paint
    private val mDescent: Int
    private var mScrolled = false
    private var mShowingAddToDictionary = false
    fun isShowingAddToDictionaryHint(): Boolean { return mShowingAddToDictionary }
    private val mAddToDictionaryHint: CharSequence
    private var mTargetScrollX = 0
    private val mMinTouchableWidth: Int
    private var mTotalWidth = 0
    private val mGestureDetector: GestureDetector

    companion object {
        private const val OUT_OF_BOUNDS_WORD_INDEX = -1
        private const val OUT_OF_BOUNDS_X_COORD = -1
        private const val MAX_SUGGESTIONS = 32
        private const val SCROLL_PIXELS = 20
        private const val X_GAP = 10
    }

    init {
        mSelectionHighlight =
            ContextCompat.getDrawable(context, R.drawable.list_selector_background_pressed)
        val inflate = context
            .getSystemService(Context.LAYOUT_INFLATER_SERVICE) as LayoutInflater
        val res = context.resources
        mPreviewPopup = PopupWindow(context)
        mPreviewText = inflate.inflate(R.layout.candidate_preview, null) as TextView
        mPreviewPopup.setWindowLayoutMode(
            ViewGroup.LayoutParams.WRAP_CONTENT,
            ViewGroup.LayoutParams.WRAP_CONTENT
        )
        mPreviewPopup.setContentView(mPreviewText)
        mPreviewPopup.setBackgroundDrawable(null)
        mPreviewPopup.animationStyle = R.style.KeyPreviewAnimation
        // Enable clipping for Android P, keep disabled for older versions.
        val clippingEnabled = Build.VERSION.SDK_INT >= 28 /* Build.VERSION_CODES.P */
        mPreviewPopup.isClippingEnabled = clippingEnabled
        mColorNormal = ContextCompat.getColor(context, R.color.candidate_normal)
        mColorRecommended = ContextCompat.getColor(context, R.color.candidate_recommended)
        mColorOther = ContextCompat.getColor(context, R.color.candidate_other)
        mDivider = ContextCompat.getDrawable(context, R.drawable.keyboard_suggest_strip_divider)
        mAddToDictionaryHint = ContextCompat.getString(context, R.string.hint_add_to_dictionary)
        mPaint = Paint()
        mPaint.setColor(mColorNormal)
        mPaint.isAntiAlias = true
        mPaint.textSize = mPreviewText.textSize * LatinIME.sKeyboardSettings.candidateScalePref
        mPaint.strokeWidth = 0f
        mPaint.textAlign = Align.CENTER
        mDescent = mPaint.descent().toInt()
        mMinTouchableWidth = res.getDimension(R.dimen.candidate_min_touchable_width).toInt()
        mGestureDetector =
            GestureDetector(getContext(), CandidateStripGestureListener(mMinTouchableWidth))
        setWillNotDraw(false)
        isHorizontalScrollBarEnabled = false
        isVerticalScrollBarEnabled = false
        scrollTo(0, scrollY)
    }

    private inner class CandidateStripGestureListener(touchSlop: Int) : SimpleOnGestureListener() {
        private val mTouchSlopSquare: Int

        init {
            // Slightly reluctant to scroll to be able to easily choose the suggestion
            mTouchSlopSquare = touchSlop * touchSlop
        }

        override fun onLongPress(me: MotionEvent) {
            if (mSuggestions.isNotEmpty()) {
                if (me.x + scrollX < mWordWidth[0] && scrollX < 10) {
                    longPressFirstWord()
                }
            }
        }

        override fun onDown(e: MotionEvent): Boolean {
            mScrolled = false
            return false
        }

        override fun onScroll(
            e1: MotionEvent?, e2: MotionEvent,
            distanceX: Float, distanceY: Float
        ): Boolean {
            if (!mScrolled) {
                // This is applied only when we recognize that scrolling is starting.
                val deltaX = (e2.x - e1!!.x).toInt()
                val deltaY = (e2.y - e1.y).toInt()
                val distance = (deltaX * deltaX) + (deltaY * deltaY)
                if (distance < mTouchSlopSquare) {
                    return true
                }
                mScrolled = true
            }
            val width = width
            mScrolled = true
            var scrollX = scrollX
            scrollX += distanceX.toInt()
            if (scrollX < 0) {
                scrollX = 0
            }
            if (distanceX > 0 && scrollX + width > mTotalWidth) {
                scrollX -= distanceX.toInt()
            }
            mTargetScrollX = scrollX
            scrollTo(scrollX, scrollY)
            hidePreview()
            invalidate()
            return true
        }
    }

    /**
     * A connection back to the service to communicate with the text field
     * @param listener
     */
    fun setService(listener: LatinIME?) {
        mService = listener
    }

    public override fun computeHorizontalScrollRange(): Int {
        return mTotalWidth
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)
        drawCanvas(canvas, false)
    }

    /**
     * If the canvas is null, then only touch calculations are performed to pick the target
     * candidate.
     */
    protected fun drawCanvas(canvas: Canvas?, manual: Boolean = true) {
        canvas?.let { if (manual) draw(it) }

        mTotalWidth = 0
        val height = height
        if (mBgPadding == null) {
            mBgPadding = Rect(0, 0, 0, 0)
            if (background != null) {
                background.getPadding(mBgPadding!!)
            }
            mDivider!!.setBounds(
                0, 0, mDivider.intrinsicWidth,
                mDivider.intrinsicHeight
            )
        }
        val count = mSuggestions.size
        val bgPadding = mBgPadding!!
        val paint = mPaint
        val touchX = mTouchX
        val scrollX = scrollX
        val scrolled = mScrolled
        val typedWordValid = mTypedWordValid
        val y = (height + mPaint.textSize - mDescent).toInt() / 2
        
        var existsAutoCompletion = false
        
        var x = 0
        for (i in 0 until count) {
            val suggestion = mSuggestions[i]
            val wordLength = suggestion.length
            
            paint.setColor(mColorNormal)
            if (mHaveMinimalSuggestion
                && (i == 1 && !typedWordValid || (i == 0 && typedWordValid))
            ) {
                paint.setTypeface(Typeface.DEFAULT_BOLD)
                paint.setColor(mColorRecommended)
                existsAutoCompletion = true
            } else if (i != 0 || (wordLength == 1 && count > 1)) {
                // HACK: even if i == 0, we use mColorOther when this suggestion's length is 1 and
                // there are multiple suggestions, such as the default punctuation list.
                paint.setColor(mColorOther)
            }
            var wordWidth: Int
            if (mWordWidth[i].also { wordWidth = it } == 0) {
                val textWidth = paint.measureText(suggestion, 0, wordLength)
                wordWidth = max(
                    mMinTouchableWidth,
                    textWidth.toInt() + X_GAP * 2
                )
                mWordWidth[i] = wordWidth
            }
            
            mWordX[i] = x
            
            if (touchX != OUT_OF_BOUNDS_X_COORD && !scrolled && touchX + scrollX >= x && touchX + scrollX < x + wordWidth) {
                if (canvas != null && !mShowingAddToDictionary) {
                    canvas.translate(x.toFloat(), 0f)
                    mSelectionHighlight!!.setBounds(0, bgPadding.top, wordWidth, height)
                    mSelectionHighlight.draw(canvas)
                    canvas.translate(-x.toFloat(), 0f)
                }
                mSelectedString = suggestion
                mSelectedIndex = i
            }
            if (canvas != null) {
                canvas.drawText(suggestion, 0, wordLength,
                    x + wordWidth.toFloat() / 2,
                    y.toFloat(),
                    paint
                )
                paint.setColor(mColorOther)
                canvas.translate((x + wordWidth).toFloat(), 0f)
                // Draw a divider unless it's after the hint
                if (!(mShowingAddToDictionary && i == 1)) {
                    mDivider!!.draw(canvas)
                }
                canvas.translate((-x - wordWidth).toFloat(), 0f)
            }
            paint.setTypeface(Typeface.DEFAULT)
            x += wordWidth
        }
        if (!isInEditMode) mService!!.onAutoCompletionStateChanged(existsAutoCompletion)
        mTotalWidth = x
        if (mTargetScrollX != scrollX) {
            scrollToTarget()
        }
    }

    private fun scrollToTarget() {
        var scrollX = scrollX
        if (mTargetScrollX > scrollX) {
            scrollX += SCROLL_PIXELS
            if (scrollX >= mTargetScrollX) {
                scrollX = mTargetScrollX
                scrollTo(scrollX, scrollY)
                requestLayout()
            } else {
                scrollTo(scrollX, scrollY)
            }
        } else {
            scrollX -= SCROLL_PIXELS
            if (scrollX <= mTargetScrollX) {
                scrollX = mTargetScrollX
                scrollTo(scrollX, scrollY)
                requestLayout()
            } else {
                scrollTo(scrollX, scrollY)
            }
        }
        invalidate()
    }

    fun setSuggestions(
        suggestions: List<CharSequence?>?, completions: Boolean,
        typedWordValid: Boolean, haveMinimalSuggestion: Boolean
    ) {
        clear()
        if (suggestions != null) {
            var insertCount = min(suggestions.size, MAX_SUGGESTIONS)
            for (suggestion in suggestions) {
                mSuggestions.add(suggestion!!)
                if (--insertCount == 0) break
            }
        }
        mShowingCompletions = completions
        mTypedWordValid = typedWordValid
        scrollTo(0, scrollY)
        mTargetScrollX = 0
        mHaveMinimalSuggestion = haveMinimalSuggestion
        // Compute the total width
        drawCanvas(null)
        invalidate()
        requestLayout()
    }

    fun showAddToDictionaryHint(word: CharSequence) {
        val suggestions = ArrayList<CharSequence>()
        suggestions.add(word)
        suggestions.add(mAddToDictionaryHint)
        setSuggestions(suggestions, false, false, false)
        mShowingAddToDictionary = true
    }

    fun dismissAddToDictionaryHint(): Boolean {
        if (!mShowingAddToDictionary) return false
        clear()
        return true
    }

    val suggestions: List<CharSequence>
        /* package */
        get() = mSuggestions

    fun clear() {
        // Don't call mSuggestions.clear() because it's being used for logging
        // in LatinIME.pickSuggestionManually().
        mSuggestions.clear()
        mTouchX = OUT_OF_BOUNDS_X_COORD
        mSelectedString = null
        mSelectedIndex = -1
        mShowingAddToDictionary = false
        invalidate()
        Arrays.fill(mWordWidth, 0)
        Arrays.fill(mWordX, 0)
    }

    override fun onTouchEvent(me: MotionEvent): Boolean {
        if (mGestureDetector.onTouchEvent(me)) {
            return true
        }
        val action = me.action
        val x = me.x.toInt()
        val y = me.y.toInt()
        mTouchX = x
        when (action) {
            MotionEvent.ACTION_DOWN -> invalidate()
            MotionEvent.ACTION_MOVE -> if (y <= 0) {
                // Fling up!?
                if (mSelectedString != null) {
                    // If there are completions from the application, we don't change the state to
                    // STATE_PICKED_SUGGESTION
                    if (!mShowingCompletions) {
                        // This "acceptedSuggestion" will not be counted as a word because
                        // it will be counted in pickSuggestion instead.
                        //TextEntryState.acceptedSuggestion(mSuggestions.get(0), mSelectedString);
                        //TextEntryState.manualTyped(mSelectedString);
                    }
                    mService!!.pickSuggestionManually(mSelectedIndex, mSelectedString!!)
                    mSelectedString = null
                    mSelectedIndex = -1
                }
            }

            MotionEvent.ACTION_UP -> {
                if (!mScrolled) {
                    if (mSelectedString != null) {
                        if (mShowingAddToDictionary) {
                            longPressFirstWord()
                            clear()
                        } else {
                            if (!mShowingCompletions) {
                                //TextEntryState.acceptedSuggestion(mSuggestions.get(0), mSelectedString);
                                //TextEntryState.manualTyped(mSelectedString);
                            }
                            mService!!.pickSuggestionManually(mSelectedIndex, mSelectedString!!)
                        }
                    }
                }
                mSelectedString = null
                mSelectedIndex = -1
                requestLayout()
                hidePreview()
                invalidate()
            }
        }
        return true
    }

    private fun hidePreview() {
        mTouchX = OUT_OF_BOUNDS_X_COORD
        mCurrentWordIndex = OUT_OF_BOUNDS_WORD_INDEX
        mPreviewPopup.dismiss()
    }

    private fun showPreview(wordIndex: Int, altText: String?) {
        val oldWordIndex = mCurrentWordIndex
        mCurrentWordIndex = wordIndex
        // If index changed or changing text
        if (oldWordIndex != mCurrentWordIndex || altText != null) {
            if (wordIndex == OUT_OF_BOUNDS_WORD_INDEX) {
                hidePreview()
            } else {
                val word = altText ?: mSuggestions[wordIndex]
                mPreviewText.text = word
                mPreviewText.measure(
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED),
                    MeasureSpec.makeMeasureSpec(0, MeasureSpec.UNSPECIFIED)
                )
                val wordWidth = (mPaint.measureText(word, 0, word.length) + X_GAP * 2).toInt()
                val popupWidth = (wordWidth
                        + mPreviewText.getPaddingLeft() + mPreviewText.getPaddingRight())
                val popupHeight = mPreviewText.measuredHeight
                //mPreviewText.setVisibility(INVISIBLE);
                mPopupPreviewX = (mWordX[wordIndex] - mPreviewText.getPaddingLeft() - scrollX
                        + (mWordWidth[wordIndex] - wordWidth) / 2)
                mPopupPreviewY = -popupHeight
                val offsetInWindow = IntArray(2)
                getLocationInWindow(offsetInWindow)
                if (mPreviewPopup.isShowing) {
                    mPreviewPopup.update(
                        mPopupPreviewX, mPopupPreviewY + offsetInWindow[1],
                        popupWidth, popupHeight
                    )
                } else {
                    mPreviewPopup.width = popupWidth
                    mPreviewPopup.height = popupHeight
                    mPreviewPopup.showAtLocation(
                        this, Gravity.NO_GRAVITY, mPopupPreviewX,
                        mPopupPreviewY + offsetInWindow[1]
                    )
                }
                mPreviewText.visibility = VISIBLE
            }
        }
    }

    private fun longPressFirstWord() {
        val word = mSuggestions[0]
        if (word.length < 2) return
        if (mService!!.addWordToDictionary(word.toString())) {
            showPreview(0, context.resources.getString(R.string.added_word, word))
        }
    }

    public override fun onDetachedFromWindow() {
        super.onDetachedFromWindow()
        hidePreview()
    }
}
