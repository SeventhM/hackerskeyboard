/*
 * Copyright (C) 2008 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package org.pocketworkstation.pckeyboard

import android.content.ContentValues
import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.provider.UserDictionary.Words
import android.util.Log

class UserDictionary(context: Context, private val mLocale: String) :
    ExpandableDictionary(context, Suggest.DIC_USER) {
    private var mObserver: ContentObserver? = null

    init {
        // Perform a managed query. The Activity will handle closing and requerying the cursor
        // when needed.
        val cres = context.contentResolver

        cres.registerContentObserver(Words.CONTENT_URI, true, object : ContentObserver(null) {
            override fun onChange(self: Boolean) {
                requiresReload = true
            }
        }.also { mObserver = it })

        loadDictionary()
    }

    @Synchronized
    override fun close() {
        if (mObserver != null) {
            context.contentResolver.unregisterContentObserver(mObserver!!)
            mObserver = null
        }
        super.close()
    }

    override fun loadDictionaryAsync() {
        val cursor = context.contentResolver
            .query(
                Words.CONTENT_URI,
                PROJECTION,
                "(locale IS NULL) or (locale=?)",
                arrayOf(mLocale),
                null
            )
        addWords(cursor)
    }

    /**
     * Adds a word to the dictionary and makes it persistent.
     * @param word the word to add. If the word is capitalized, then the dictionary will
     * recognize it as a capitalized word when searched.
     * @param frequency the frequency of occurrence of the word. A frequency of 255 is considered
     * the highest.
     * @TODO use a higher or float range for frequency
     */
    @Synchronized
    override fun addWord(word: String, frequency: Int) {
        // Force load the dictionary here synchronously
        if (requiresReload) loadDictionaryAsync()
        // Safeguard against adding long words. Can cause stack overflow.
        if (word.length >= MAX_WORD_LENGTH) return

        super.addWord(word, frequency)

        // Update the user dictionary provider
        val values = ContentValues(5)
        values.put(Words.WORD, word)
        values.put(Words.FREQUENCY, frequency)
        values.put(Words.LOCALE, mLocale)
        values.put(Words.APP_ID, 0)

        val contentResolver = context.contentResolver
        object : Thread("addWord") {
            override fun run() {
                contentResolver.insert(Words.CONTENT_URI, values)
            }
        }.start()

        // In case the above does a synchronous callback of the change observer
        requiresReload = false
    }

    @Synchronized
    override fun getWords(
            composer: WordComposer?, callback: WordCallback?,
            nextLettersFrequencies: IntArray?
    ) {
        super.getWords(composer, callback, nextLettersFrequencies)
    }

    @Synchronized
    override fun isValidWord(word: CharSequence?): Boolean {
        return super.isValidWord(word)
    }

    private fun addWords(cursor: Cursor?) {
        if (cursor == null) {
            Log.w(TAG, "Unexpected null cursor in addWords()")
            return
        }
        clearDictionary()

        if (cursor.moveToFirst()) {
            while (!cursor.isAfterLast) {
                val word = cursor.getString(INDEX_WORD)
                val frequency = cursor.getInt(INDEX_FREQUENCY)
                // Safeguard against adding really long words. Stack may overflow due
                // to recursion
                if (word.length < MAX_WORD_LENGTH) {
                    super.addWord(word, frequency)
                }
                cursor.moveToNext()
            }
        }
        cursor.close()
    }

    companion object {
        private val PROJECTION = arrayOf(
            Words._ID,
            Words.WORD,
            Words.FREQUENCY
        )
        private const val INDEX_WORD = 1
        private const val INDEX_FREQUENCY = 2
        private const val TAG = "HK/UserDictionary"
    }
}
