/*
 * Copyright (C) 2009 Google Inc.
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

import android.text.TextUtils
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.util.regex.Pattern

/**
 * Utility methods to deal with editing text through an InputConnection.
 */
object EditingUtil {
    /**
     * Number of characters we want to look back in order to identify the previous word
     */
    private const val LOOKBACK_CHARACTER_NUM = 15

    // Cache Method pointers
    private var sMethodsInitialized = false
    private var sMethodGetSelectedText: Method? = null
    private var sMethodSetComposingRegion: Method? = null

    /**
     * Append newText to the text field represented by connection.
     * The new text becomes selected.
     */
    fun appendText(connection: InputConnection?, newText: String) {
        var newText = newText
        if (connection == null) {
            return
        }

        // Commit the composing text
        connection.finishComposingText()

        // Add a space if the field already has text.
        val charBeforeCursor = connection.getTextBeforeCursor(1, 0)
        if (!charBeforeCursor.isNullOrEmpty() && charBeforeCursor != " ") {
            newText = " $newText"
        }

        connection.setComposingText(newText, 1)
    }

    private fun getCursorPosition(connection: InputConnection): Int {
        val extracted = connection.getExtractedText(ExtractedTextRequest(), 0)
            ?: return -1
        return extracted.startOffset + extracted.selectionStart
    }

    /**
     * @param connection connection to the current text field.
     * @param separators characters which may separate words
     * @param range the range object to store the result into
     * @return the word that surrounds the cursor, including up to one trailing
     * separator. For example, if the field contains "he|llo world", where |
     * represents the cursor, then "hello " will be returned.
     */
    fun getWordAtCursor(
        connection: InputConnection?, separators: String?, range: Range?
    ): String? {
        val r = getWordRangeAtCursor(connection, separators, range)
        return r?.word
    }

    /**
     * Removes the word surrounding the cursor. Parameters are identical to
     * getWordAtCursor.
     */
    fun deleteWordAtCursor(
        connection: InputConnection, separators: String?
    ) {
        val range = getWordRangeAtCursor(connection, separators, null) ?: return

        connection.finishComposingText()
        // Move cursor to beginning of word, to avoid crash when cursor is outside
        // of valid range after deleting text.
        val newCursor = getCursorPosition(connection) - range.charsBefore
        connection.setSelection(newCursor, newCursor)
        connection.deleteSurroundingText(0, range.charsBefore + range.charsAfter)
    }

    private fun getWordRangeAtCursor(
        connection: InputConnection?, sep: String?, range: Range?
    ): Range? {
        if (connection == null || sep == null) {
            return null
        }
        val before = connection.getTextBeforeCursor(1000, 0)
        val after = connection.getTextAfterCursor(1000, 0)
        if (before == null || after == null) {
            return null
        }

        // Find first word separator before the cursor
        var start = before.length
        while (start > 0 && !isWhitespace(before[start - 1].code, sep)) start--

        // Find last word separator after the cursor
        var end = -1
        while (++end < after.length && !isWhitespace(after[end].code, sep));

        val cursor = getCursorPosition(connection)
        if (start >= 0 && cursor + end <= after.length + before.length) {
            val word = (before.toString().substring(start, before.length)
                    + after.toString().substring(0, end))

            val returnRange = range ?: Range()
            returnRange.charsBefore = before.length - start
            returnRange.charsAfter = end
            returnRange.word = word
            return returnRange
        }
        return null
    }

    private fun isWhitespace(code: Int, whitespace: String): Boolean {
        return whitespace.contains(code.toChar().toString())
    }

    private val spaceRegex = Pattern.compile("\\s+")

    fun getPreviousWord(
        connection: InputConnection,
        sentenceSeperators: String
    ): CharSequence? {
        //TODO: Should fix this. This could be slow!
        val prev = connection.getTextBeforeCursor(LOOKBACK_CHARACTER_NUM, 0)
            ?: return null
        val w = spaceRegex.split(prev)
        if (w.size >= 2 && w[w.size - 2].isNotEmpty()) {
            val lastChar = w[w.size - 2][w[w.size - 2].length - 1]
            if (sentenceSeperators.contains(lastChar.toString())) {
                return null
            } else return w[w.size - 2]
        } else {
            return null
        }
    }

    /**
     * Takes a character sequence with a single character and checks if the character occurs
     * in a list of word separators or is empty.
     * @param singleChar A CharSequence with null, zero or one character
     * @param wordSeparators A String containing the word separators
     * @return true if the character is at a word boundary, false otherwise
     */
    private fun isWordBoundary(singleChar: CharSequence?, wordSeparators: String): Boolean {
        return TextUtils.isEmpty(singleChar) || wordSeparators.contains(singleChar!!)
    }

    /**
     * Checks if the cursor is inside a word or the current selection is a whole word.
     * @param ic the InputConnection for accessing the text field
     * @param selStart the start position of the selection within the text field
     * @param selEnd the end position of the selection within the text field. This could be
     * the same as selStart, if there's no selection.
     * @param wordSeparators the word separator characters for the current language
     * @return an object containing the text and coordinates of the selected/touching word,
     * null if the selection/cursor is not marking a whole word.
     */
    fun getWordAtCursorOrSelection(
        ic: InputConnection,
        selStart: Int,
        selEnd: Int,
        wordSeparators: String
    ): SelectedWord? {
        if (selStart == selEnd) {
            // There is just a cursor, so get the word at the cursor
            val range = Range()
            val touching: CharSequence? = getWordAtCursor(ic, wordSeparators, range)
            if (!TextUtils.isEmpty(touching)) {
                val selWord = SelectedWord()
                selWord.word = touching
                selWord.start = selStart - range.charsBefore
                selWord.end = selEnd + range.charsAfter
                return selWord
            }
        } else {
            // Is the previous character empty or a word separator? If not, return null.
            val charsBefore = ic.getTextBeforeCursor(1, 0)
            if (!isWordBoundary(charsBefore, wordSeparators)) {
                return null
            }

            // Is the next character empty or a word separator? If not, return null.
            val charsAfter = ic.getTextAfterCursor(1, 0)
            if (!isWordBoundary(charsAfter, wordSeparators)) {
                return null
            }

            // Extract the selection alone
            val touching = getSelectedText(ic, selStart, selEnd)
            if (TextUtils.isEmpty(touching)) return null
            // Is any part of the selection a separator? If so, return null.
            val length = touching!!.length
            for (i in 0 until length) {
                if (wordSeparators.contains(touching.subSequence(i, i + 1))) {
                    return null
                }
            }
            // Prepare the selected word
            val selWord = SelectedWord()
            selWord.start = selStart
            selWord.end = selEnd
            selWord.word = touching
            return selWord
        }
        return null
    }

    /**
     * Cache method pointers for performance
     */
    private fun initializeMethodsForReflection() {
        try {
            // These will either both exist or not, so no need for separate try/catch blocks.
            // If other methods are added later, use separate try/catch blocks.
            sMethodGetSelectedText = InputConnection::class.java.getMethod(
                "getSelectedText",
                Int::class.javaPrimitiveType
            )
            sMethodSetComposingRegion = InputConnection::class.java.getMethod(
                "setComposingRegion",
                Int::class.javaPrimitiveType, Int::class.javaPrimitiveType
            )
        } catch (exc: NoSuchMethodException) {
            // Ignore
        }
        sMethodsInitialized = true
    }

    /**
     * Returns the selected text between the selStart and selEnd positions.
     */
    private fun getSelectedText(ic: InputConnection, selStart: Int, selEnd: Int): CharSequence? {
        // Use reflection, for backward compatibility
        var result: CharSequence?
        if (!sMethodsInitialized) {
            initializeMethodsForReflection()
        }
        if (sMethodGetSelectedText != null) {
            try {
                result = sMethodGetSelectedText!!.invoke(ic, 0) as CharSequence
                return result
            } catch (exc: Throwable) {
                when(exc) {
                    is InvocationTargetException,
                    is IllegalArgumentException,
                    is IllegalAccessException -> {}
                    else -> throw exc
                }
            }
        }
        // Reflection didn't work, try it the poor way, by moving the cursor to the start,
        // getting the text after the cursor and moving the text back to selected mode.
        // TODO: Verify that this works properly in conjunction with 
        // LatinIME#onUpdateSelection
        ic.setSelection(selStart, selEnd)
        result = ic.getTextAfterCursor(selEnd - selStart, 0)
        ic.setSelection(selStart, selEnd)
        return result
    }

    /**
     * Tries to set the text into composition mode if there is support for it in the framework.
     */
    fun underlineWord(ic: InputConnection?, word: SelectedWord) {
        // Use reflection, for backward compatibility
        // If method not found, there's nothing we can do. It still works but just wont underline
        // the word.
        if (!sMethodsInitialized) {
            initializeMethodsForReflection()
        }
        if (sMethodSetComposingRegion != null) {
            try {
                sMethodSetComposingRegion!!.invoke(ic, word.start, word.end)
            } catch (exc: Throwable) {
                when (exc) {
                    is InvocationTargetException,
                    is IllegalArgumentException,
                    is IllegalAccessException -> {}
                    else -> throw exc
                }
            }
        }
    }

    /**
     * Represents a range of text, relative to the current cursor position.
     */
    class Range {
        /** Characters before selection start  */
        var charsBefore = 0

        /**
         * Characters after selection start, including one trailing word
         * separator.
         */
        var charsAfter = 0

        /** The actual characters that make up a word  */
        var word: String? = null

        constructor()

        constructor(charsBefore: Int, charsAfter: Int, word: String?) {
            if (charsBefore < 0 || charsAfter < 0) {
                throw IndexOutOfBoundsException()
            }
            this.charsBefore = charsBefore
            this.charsAfter = charsAfter
            this.word = word
        }
    }

    class SelectedWord {
        var start = 0
        var end = 0
        var word: CharSequence? = null
    }
}
