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
import android.util.Log
import java.io.IOException
import java.io.InputStream
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.channels.Channels
import java.util.Arrays
import kotlin.math.max
import kotlin.math.min

/**
 * Implements a static, compacted, binary dictionary of standard words.
 */
class BinaryDictionary : Dictionary, AutoCloseable {
    private val mDicTypeId: Int
    private var mNativeDict: Long = 0

    private var mDictLength = 0

    private val mInputCodes = IntArray(MAX_WORD_LENGTH * MAX_ALTERNATIVES)
    private val mOutputChars = CharArray(MAX_WORD_LENGTH * MAX_WORDS)
    private val mOutputChars_bigrams = CharArray(MAX_WORD_LENGTH * MAX_BIGRAMS)
    private val mFrequencies = IntArray(MAX_WORDS)
    private val mFrequencies_bigrams = IntArray(MAX_BIGRAMS)

    // Keep a reference to the native dict direct buffer in Java to avoid
    // unexpected deallocation of the direct buffer.
    private var mNativeDictDirectBuffer: ByteBuffer? = null

    /**
     * Create a dictionary from a raw resource file
     * @param context application context for reading resources
     * @param resId the resource containing the raw binary dictionary
     */
    constructor(context: Context, resId: IntArray?, dicTypeId: Int) {
        if (resId != null && resId.isNotEmpty() && resId[0] != 0) {
            loadDictionary(context, resId)
        }
        mDicTypeId = dicTypeId
    }

    /**
     * Create a dictionary from input streams
     * @param context application context for reading resources
     * @param streams the resource streams containing the raw binary dictionary
     */
    constructor(context: Context?, streams: Array<InputStream?>?, dicTypeId: Int) {
        if (!streams.isNullOrEmpty()) {
            loadDictionary(streams)
        }
        mDicTypeId = dicTypeId
    }

    /**
     * Create a dictionary from a byte buffer. This is used for testing.
     * @param context application context for reading resources
     * @param byteBuffer a ByteBuffer containing the binary dictionary
     */
    constructor(context: Context?, byteBuffer: ByteBuffer?, dicTypeId: Int) {
        if (byteBuffer != null) {
            if (byteBuffer.isDirect) {
                mNativeDictDirectBuffer = byteBuffer
            } else {
                mNativeDictDirectBuffer = ByteBuffer.allocateDirect(byteBuffer.capacity())
                byteBuffer.rewind()
                mNativeDictDirectBuffer!!.put(byteBuffer)
            }
            mDictLength = byteBuffer.capacity()
            mNativeDict = openNative(
                mNativeDictDirectBuffer,
                TYPED_LETTER_MULTIPLIER, FULL_WORD_FREQ_MULTIPLIER, mDictLength
            )
        }
        mDicTypeId = dicTypeId
    }

    private external fun openNative(
        bb: ByteBuffer?, typedLetterMultiplier: Int,
        fullWordMultiplier: Int, dictSize: Int
    ): Long

    private external fun closeNative(dict: Long)
    private external fun isValidWordNative(
        nativeData: Long,
        word: CharArray,
        wordLength: Int
    ): Boolean

    private external fun getSuggestionsNative(
        dict: Long, inputCodes: IntArray, codesSize: Int,
        outputChars: CharArray, frequencies: IntArray, maxWordLength: Int, maxWords: Int,
        maxAlternatives: Int, skipPos: Int, nextLettersFrequencies: IntArray?, nextLettersSize: Int
    ): Int

    private external fun getBigramsNative(
        dict: Long, prevWord: CharArray, prevWordLength: Int,
        inputCodes: IntArray, inputCodesLength: Int, outputChars: CharArray, frequencies: IntArray,
        maxWordLength: Int, maxBigrams: Int, maxAlternatives: Int
    ): Int

    private fun loadDictionary(inputStreams: Array<InputStream?>?) {
        try {
            // merging separated dictionary into one if dictionary is separated
            var total = 0

            for (inputStream in inputStreams!!) {
                total += inputStream!!.available()
            }

            mNativeDictDirectBuffer =
                ByteBuffer.allocateDirect(total).order(ByteOrder.nativeOrder())
            var got = 0
            for (inputStream in inputStreams) {
                got += Channels.newChannel(inputStream).read(mNativeDictDirectBuffer)
            }
            if (got != total) {
                Log.e(TAG, "Read $got bytes, expected $total")
            } else {
                mNativeDict = openNative(
                    mNativeDictDirectBuffer,
                    TYPED_LETTER_MULTIPLIER, FULL_WORD_FREQ_MULTIPLIER, total
                )
                mDictLength = total
            }
            if (mDictLength > 10000) Log.i("PCKeyboard", "Loaded dictionary, len=$mDictLength")
        } catch (e: IOException) {
            Log.w(TAG, "No available memory for binary dictionary")
        } catch (e: UnsatisfiedLinkError) {
            Log.w(TAG, "Failed to load native dictionary", e)
        } finally {
            try {
                if (inputStreams != null) {
                    for (inputStream in inputStreams) {
                        inputStream!!.close()
                    }
                }
            } catch (e: IOException) {
                Log.w(TAG, "Failed to close input stream")
            }
        }
    }

    private fun loadDictionary(context: Context, resId: IntArray) {
        val `is`: Array<InputStream?> = arrayOfNulls(resId.size)
        for (i in resId.indices) {
            `is`[i] = context.resources.openRawResource(resId[i])
        }
        loadDictionary(`is`)
    }

    override fun getBigrams(
            composer: WordComposer?, previousWord: CharSequence?,
            callback: WordCallback?, nextLettersFrequencies: IntArray?
    ) {
        val chars = previousWord.toString().toCharArray()
        Arrays.fill(mOutputChars_bigrams, 0.toChar())
        Arrays.fill(mFrequencies_bigrams, 0)

        val codesSize = composer!!.size
        Arrays.fill(mInputCodes, -1)
        val alternatives = composer.getCodesAt(0)
        System.arraycopy(
            alternatives, 0, mInputCodes, 0,
            min(alternatives.size, MAX_ALTERNATIVES)
        )

        val count = getBigramsNative(
            mNativeDict, chars, chars.size, mInputCodes, codesSize,
            mOutputChars_bigrams, mFrequencies_bigrams, MAX_WORD_LENGTH, MAX_BIGRAMS,
            MAX_ALTERNATIVES
        )

        for (j in 0 until count) {
            if (mFrequencies_bigrams[j] < 1) break
            val start = j * MAX_WORD_LENGTH
            var len = 0
            while (mOutputChars_bigrams[start + len].code != 0) {
                len++
            }
            if (len > 0) {
                callback!!.addWord(
                    mOutputChars_bigrams, start, len, mFrequencies_bigrams[j],
                    mDicTypeId, DataType.BIGRAM
                )
            }
        }
    }

    override fun getWords(
            composer: WordComposer?, callback: WordCallback?,
            nextLettersFrequencies: IntArray?
    ) {
        val codesSize = composer!!.size
        // Won't deal with really long words.
        if (codesSize > MAX_WORD_LENGTH - 1) return

        Arrays.fill(mInputCodes, -1)
        for (i in 0 until codesSize) {
            val alternatives = composer.getCodesAt(i)
            System.arraycopy(
                alternatives, 0, mInputCodes, i * MAX_ALTERNATIVES,
                min(alternatives.size, MAX_ALTERNATIVES)
            )
        }
        mOutputChars.fill(0.toChar())
        mFrequencies.fill(0)

        if (mNativeDict == 0L) return

        var count = getSuggestionsNative(
            mNativeDict, mInputCodes, codesSize,
            mOutputChars, mFrequencies,
            MAX_WORD_LENGTH, MAX_WORDS, MAX_ALTERNATIVES, -1,
            nextLettersFrequencies,
            nextLettersFrequencies?.size ?: 0
        )

        // If there aren't sufficient suggestions, search for words by allowing wild cards at
        // the different character positions. This feature is not ready for prime-time as we need
        // to figure out the best ranking for such words compared to proximity corrections and
        // completions.
        if (ENABLE_MISSED_CHARACTERS && count < 5) {
            for (skip in 0 until codesSize) {
                val tempCount = getSuggestionsNative(
                    mNativeDict, mInputCodes, codesSize,
                    mOutputChars, mFrequencies,
                    MAX_WORD_LENGTH, MAX_WORDS, MAX_ALTERNATIVES, skip,
                    null, 0
                )
                count = max(count.toDouble(), tempCount.toDouble()).toInt()
                if (tempCount > 0) break
            }
        }

        for (j in 0 until count) {
            if (mFrequencies[j] < 1) break
            val start = j * MAX_WORD_LENGTH
            var len = 0
            while (mOutputChars[start + len].code != 0) {
                len++
            }
            if (len > 0) {
                callback!!.addWord(
                    mOutputChars, start, len, mFrequencies[j], mDicTypeId,
                    DataType.UNIGRAM
                )
            }
        }
    }

    override fun isValidWord(word: CharSequence?): Boolean {
        if (word == null || mNativeDict == 0L) return false
        val chars = word.toString().toCharArray()
        return isValidWordNative(mNativeDict, chars, chars.size)
    }

    @Synchronized
    override fun close() {
        if (mNativeDict != 0L) {
            closeNative(mNativeDict)
            mNativeDict = 0
        }
    }

    @get:JvmName("size")
    val size get() = mDictLength // This value is initialized on the call to openNative()

    @Throws(Throwable::class)
    protected fun finalize() {
        close()
        //super.finalize() /* Not a thing in Kotlin proper*/
    }

    companion object {
        /**
         * There is difference between what java and native code can handle.
         * This value should only be used in BinaryDictionary.java
         * It is necessary to keep it at this value because some languages e.g. German have
         * really long words.
         */
        protected const val MAX_WORD_LENGTH = 48
        private const val TAG = "BinaryDictionary"
        private const val MAX_ALTERNATIVES = 16
        private const val MAX_WORDS = 18
        private const val MAX_BIGRAMS = 60
        private const val TYPED_LETTER_MULTIPLIER = 2
        private const val ENABLE_MISSED_CHARACTERS = true

        init {
            try {
                System.loadLibrary("jni_pckeyboard")
                Log.i("PCKeyboard", "loaded jni_pckeyboard")
            } catch (ule: UnsatisfiedLinkError) {
                Log.e("BinaryDictionary", "Could not load native library jni_pckeyboard")
            }
        }
    }
}
