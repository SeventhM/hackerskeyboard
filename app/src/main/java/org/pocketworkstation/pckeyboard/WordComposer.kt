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

/**
 * A place to store the currently composing word with information such as adjacent key codes as well
 */
class WordComposer {
    /**
     * The list of unicode values for each keystroke (including surrounding keys)
     */
    private val mCodes: ArrayList<IntArray>

    /**
     * The word chosen from the candidate list, until it is committed.
     */
    private var mPreferredWord: String? = null
    private val mTypedWord: StringBuilder
    private var mCapsCount = 0

    private var mAutoCapitalized = false

    private var mIsFirstCharCapitalized = false

    constructor() {
        mCodes = ArrayList(12)
        mTypedWord = StringBuilder(20)
    }

    internal constructor(copy: WordComposer) {
        mCodes = ArrayList(copy.mCodes)
        mPreferredWord = copy.mPreferredWord
        mTypedWord = StringBuilder(copy.mTypedWord)
        mCapsCount = copy.mCapsCount
        isAutoCapitalized = copy.isAutoCapitalized
        isFirstCharCapitalized = copy.isFirstCharCapitalized
    }

    /**
     * Clear out the keys registered so far.
     */
    fun reset() {
        mCodes.clear()
        isFirstCharCapitalized = false
        mPreferredWord = null
        mTypedWord.setLength(0)
        mCapsCount = 0
    }

    /**
     * Number of keystrokes in the composing word.
     * @return the number of keystrokes
     */
    @get:JvmName("size")
    val size get() = mCodes.size

    /**
     * Returns the codes at a particular position in the word.
     * @param index the position in the word
     * @return the unicode for the pressed and surrounding keys
     */
    fun getCodesAt(index: Int): IntArray {
        return mCodes[index]
    }

    /**
     * Add a new keystroke, with codes[0] containing the pressed key's unicode and the rest of
     * the array containing unicode for adjacent keys, sorted by reducing probability/proximity.
     * @param codes the array of unicode values
     */
    fun add(primaryCode: Int, codes: IntArray) {
        mTypedWord.append(primaryCode.toChar())
        correctPrimaryJuxtapos(primaryCode, codes)
        correctCodesCase(codes)
        mCodes.add(codes)
        if (primaryCode.toChar().isUpperCase()) mCapsCount++
    }

    /**
     * Swaps the first and second values in the codes array if the primary code is not the first
     * value in the array but the second. This happens when the preferred key is not the key that
     * the user released the finger on.
     * @param primaryCode the preferred character
     * @param codes array of codes based on distance from touch point
     */
    private fun correctPrimaryJuxtapos(primaryCode: Int, codes: IntArray) {
        if (codes.size < 2) return
        if (codes[0] > 0 && codes[1] > 0 && codes[0] != primaryCode && codes[1] == primaryCode) {
            codes[1] = codes[0]
            codes[0] = primaryCode
        }
    }

    // Prediction expects the keyKodes to be lowercase
    private fun correctCodesCase(codes: IntArray) {
        for (i in codes.indices) {
            val code = codes[i]
            if (code > 0) codes[i] = code.toChar().lowercaseChar().code
        }
    }

    /**
     * Delete the last keystroke as a result of hitting backspace.
     */
    fun deleteLast() {
        val codesSize = mCodes.size
        if (codesSize > 0) {
            mCodes.removeAt(codesSize - 1)
            val lastPos = mTypedWord.length - 1
            val last = mTypedWord[lastPos]
            mTypedWord.deleteCharAt(lastPos)
            if (Character.isUpperCase(last)) mCapsCount--
        }
    }

    val typedWord: CharSequence?
        /**
         * Returns the word as it was typed, without any correction applied.
         * @return the word that was typed so far
         */
        get() {
            val wordSize = mCodes.size
            return if (wordSize == 0) null else mTypedWord
        }

    var isFirstCharCapitalized
        /**
         * Whether or not the user typed a capital letter as the first letter in the word
         * @return capitalization preference
         */
        get() = mIsFirstCharCapitalized
        /**
         * Whether the user chose to capitalize the first char of the word.
         */
        set(capitalized) { mIsFirstCharCapitalized = capitalized }

    val isAllUpperCase: Boolean
        /**
         * Whether or not all of the user typed chars are upper case
         * @return true if all user typed chars are upper case, false otherwise
         */
        get() = mCapsCount > 0 && mCapsCount == size

    /**
     * Stores the user's selected word, before it is actually committed to the text field.
     * @param preferred
     */
    fun setPreferredWord(preferred: String?) {
        mPreferredWord = preferred
    }

    val preferredWord: CharSequence
        /**
         * Return the word chosen by the user, or the typed word if no other word was chosen.
         * @return the preferred word
         */
        get() = if (mPreferredWord != null) mPreferredWord!! else typedWord!!

    val isMostlyCaps: Boolean
        /**
         * Returns true if more than one character is upper case, otherwise returns false.
         */
        get() = mCapsCount > 1

    var isAutoCapitalized
        /**
         * Returns whether the word was automatically capitalized.
         * @return whether the word was automatically capitalized
         */
        get() = mAutoCapitalized
        /**
         * Saves the reason why the word is capitalized - whether it was automatic or
         * due to the user hitting shift in the middle of a sentence.
         * @param auto whether it was an automatic capitalization due to start of sentence
         */
        set(auto) { mAutoCapitalized = auto }
}
