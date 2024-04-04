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
import android.text.format.DateFormat
import android.util.Log
import java.io.FileOutputStream
import java.io.IOException
import java.util.Calendar

object TextEntryState {
    private const val DBG = false
    private const val TAG = "TextEntryState"
    private const val LOGGING = false
    private var sBackspaceCount = 0
    private var sAutoSuggestCount = 0
    private var sAutoSuggestUndoneCount = 0
    private var sManualSuggestCount = 0
    private var sWordNotInDictionaryCount = 0
    private var sSessionCount = 0
    private var sTypedChars = 0
    private var sActualChars = 0
    private var sState = State.UNKNOWN
    private var sKeyLocationFile: FileOutputStream? = null
    private var sUserActionFile: FileOutputStream? = null
    fun newSession(context: Context) {
        sSessionCount++
        sAutoSuggestCount = 0
        sBackspaceCount = 0
        sAutoSuggestUndoneCount = 0
        sManualSuggestCount = 0
        sWordNotInDictionaryCount = 0
        sTypedChars = 0
        sActualChars = 0
        sState = State.START

        if (LOGGING) {
            try {
                sKeyLocationFile = context.openFileOutput("key.txt", Context.MODE_APPEND)
                sUserActionFile = context.openFileOutput("action.txt", Context.MODE_APPEND)
            } catch (ioe: IOException) {
                Log.e(TAG, "Couldn't open file for output: $ioe")
            }
        }
    }

    fun endSession() {
        if (sKeyLocationFile == null) {
            return
        }
        try {
            sKeyLocationFile!!.close()
            // Write to log file            
            // Write timestamp, settings,
            val out = (DateFormat.format("MM:dd hh:mm:ss", Calendar.getInstance().time)
                .toString()
                    + " BS: " + sBackspaceCount
                    + " auto: " + sAutoSuggestCount
                    + " manual: " + sManualSuggestCount
                    + " typed: " + sWordNotInDictionaryCount
                    + " undone: " + sAutoSuggestUndoneCount
                    + " saved: " + ((sActualChars - sTypedChars).toFloat() / sActualChars)
                    + "\n")
            sUserActionFile!!.write(out.toByteArray())
            sUserActionFile!!.close()
            sKeyLocationFile = null
            sUserActionFile = null
        } catch (ignored: IOException) {
        }
    }

    fun acceptedDefault(typedWord: CharSequence?, actualWord: CharSequence) {
        if (typedWord == null) return
        if (typedWord != actualWord) {
            sAutoSuggestCount++
        }
        sTypedChars += typedWord.length
        sActualChars += actualWord.length
        sState = State.ACCEPTED_DEFAULT
        displayState()
    }

    // State.ACCEPTED_DEFAULT will be changed to other sub-states
    // (see "case ACCEPTED_DEFAULT" in typedCharacter() below),
    // and should be restored back to State.ACCEPTED_DEFAULT after processing for each sub-state.
    fun backToAcceptedDefault(typedWord: CharSequence?) {
        if (typedWord == null) return
        when (sState) {
            State.SPACE_AFTER_ACCEPTED,
            State.PUNCTUATION_AFTER_ACCEPTED,
            State.IN_WORD ->
                sState = State.ACCEPTED_DEFAULT
            else -> {}
        }
        displayState()
    }

    fun manualTyped(typedWord: CharSequence?) {
        sState = State.START
        displayState()
    }

    fun acceptedTyped(typedWord: CharSequence?) {
        sWordNotInDictionaryCount++
        sState = State.PICKED_SUGGESTION
        displayState()
    }

    fun acceptedSuggestion(typedWord: CharSequence, actualWord: CharSequence) {
        sManualSuggestCount++
        val oldState = sState
        if ((typedWord == actualWord)) {
            acceptedTyped(typedWord)
        }
        sState = if (oldState == State.CORRECTING || oldState == State.PICKED_CORRECTION) {
            State.PICKED_CORRECTION
        } else {
            State.PICKED_SUGGESTION
        }
        displayState()
    }

    fun selectedForCorrection() {
        sState = State.CORRECTING
        displayState()
    }

    fun typedCharacter(c: Char, isSeparator: Boolean) {
        val isSpace = c == ' '
        when (sState) {
            State.IN_WORD -> if (isSpace || isSeparator) {
                sState = State.START
            } else {
                // State hasn't changed.
            }

            State.ACCEPTED_DEFAULT, State.SPACE_AFTER_PICKED ->
                sState = if (isSpace) {
                    State.SPACE_AFTER_ACCEPTED
                } else if (isSeparator) {
                    State.PUNCTUATION_AFTER_ACCEPTED
                } else {
                    State.IN_WORD
                }

            State.PICKED_SUGGESTION, State.PICKED_CORRECTION ->
                sState = if (isSpace) {
                    State.SPACE_AFTER_PICKED
                } else if (isSeparator) {
                    // Swap
                    State.PUNCTUATION_AFTER_ACCEPTED
                } else {
                    State.IN_WORD
                }

            State.START, State.UNKNOWN,
            State.SPACE_AFTER_ACCEPTED,
            State.PUNCTUATION_AFTER_ACCEPTED,
            State.PUNCTUATION_AFTER_WORD ->
                sState = if (!isSpace && !isSeparator) {
                    State.IN_WORD
                } else {
                    State.START
                }

            State.UNDO_COMMIT ->
                sState = if (isSpace || isSeparator) {
                    State.ACCEPTED_DEFAULT
                } else {
                    State.IN_WORD
                }

            State.CORRECTING -> sState = State.START
        }
        displayState()
    }

    fun backspace() {
        if (sState == State.ACCEPTED_DEFAULT) {
            sState = State.UNDO_COMMIT
            sAutoSuggestUndoneCount++
        } else if (sState == State.UNDO_COMMIT) {
            sState = State.IN_WORD
        }
        sBackspaceCount++
        displayState()
    }

    fun reset() {
        sState = State.START
        displayState()
    }

    val state: State
        get() {
            if (DBG) {
                Log.d(TAG, "Returning state = $sState")
            }
            return sState
        }

    val isCorrecting: Boolean
        get() = sState == State.CORRECTING || sState == State.PICKED_CORRECTION

    fun keyPressedAt(key: Keyboard.Key, x: Int, y: Int) {
        if (LOGGING && (sKeyLocationFile != null) && (key.codes!![0] >= 32)) {
            val out = ("KEY: " + key.codes!![0]
                    + " X: " + x
                    + " Y: " + y
                    + " MX: " + (key.x + key.width / 2)
                    + " MY: " + (key.y + key.height / 2)
                    + "\n")
            try {
                sKeyLocationFile!!.write(out.toByteArray())
            } catch (ioe: IOException) {
                // TODO: May run out of space
            }
        }
    }

    private fun displayState() {
        if (DBG) {
            //Log.w(TAG, "State = " + sState, new Throwable());
            Log.i(TAG, "State = $sState")
        }
    }

    enum class State {
        UNKNOWN,
        START,
        IN_WORD,
        ACCEPTED_DEFAULT,
        PICKED_SUGGESTION,
        PUNCTUATION_AFTER_WORD,
        PUNCTUATION_AFTER_ACCEPTED,
        SPACE_AFTER_ACCEPTED,
        SPACE_AFTER_PICKED,
        UNDO_COMMIT,
        CORRECTING,
        PICKED_CORRECTION
    }
}
