/*
 * Copyright (C) 2009 The Android Open Source Project
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

import android.content.Context
import android.database.ContentObserver
import android.database.Cursor
import android.os.SystemClock
import android.provider.ContactsContract
import android.text.TextUtils
import android.util.Log

class ContactsDictionary(context: Context, dicTypeId: Int) :
    ExpandableDictionary(context, dicTypeId) {
    private var mObserver: ContentObserver? = null
    private var mLastLoadedContacts: Long = 0

    init {
        // Perform a managed query. The Activity will handle closing and requerying the cursor
        // when needed.
        val cres = context.contentResolver

        cres.registerContentObserver(
            ContactsContract.Contacts.CONTENT_URI, true, object : ContentObserver(null) {
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

    override fun startDictionaryLoadingTaskLocked() {
        val now = SystemClock.uptimeMillis()
        if (mLastLoadedContacts == 0L
            || now - mLastLoadedContacts > 30 * 60 * 1000 /* 30 minutes */) {
            super.startDictionaryLoadingTaskLocked()
        }
    }

    override fun loadDictionaryAsync() {
        try {
            val cursor = context.contentResolver
                .query(ContactsContract.Contacts.CONTENT_URI, PROJECTION, null, null, null)
            cursor?.let { addWords(it) }
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Contacts DB is having problems")
        }
        mLastLoadedContacts = SystemClock.uptimeMillis()
    }

    private fun addWords(cursor: Cursor) {
        clearDictionary()
        val maxWordLength: Int = MAX_WORD_LENGTH
        try {
            if (cursor.moveToFirst()) {
                while (!cursor.isAfterLast) {
                    val name = cursor.getString(INDEX_NAME)
                    if (name != null) {
                        val len = name.length
                        var prevWord: String? = null

                        // TODO: Better tokenization for non-Latin writing systems
                        var i = 0
                        while (i < len) {
                            if (Character.isLetter(name[i])) {
                                var j: Int = i + 1
                                while (j < len) {
                                    val c = name[j]
                                    if (!(c == '-' || c == '\'' ||
                                                Character.isLetter(c))
                                    ) {
                                        break
                                    }
                                    j++
                                }
                                val word = name.substring(i, j)
                                i = j - 1

                                // Safeguard against adding really long words. Stack
                                // may overflow due to recursion
                                // Also don't add single letter words, possibly confuses
                                // capitalization of i.
                                val wordLen = word.length
                                if (wordLen in 2..<maxWordLength) {
                                    super.addWord(word, FREQUENCY_FOR_CONTACTS)
                                    if (!TextUtils.isEmpty(prevWord)) {
                                        // TODO Do not add email address
                                        // Not so critical
                                        super.setBigram(
                                            prevWord!!, word,
                                            FREQUENCY_FOR_CONTACTS_BIGRAM
                                        )
                                    }
                                    prevWord = word
                                }
                            }
                            i++
                        }
                    }
                    cursor.moveToNext()
                }
            }
            cursor.close()
        } catch (e: IllegalStateException) {
            Log.e(TAG, "Contacts DB is having problems")
        }
    }

    companion object {
        private val PROJECTION = arrayOf(
            ContactsContract.Contacts._ID,
            ContactsContract.Contacts.DISPLAY_NAME
        )
        private const val TAG = "ContactsDictionary"

        /**
         * Frequency for contacts information into the dictionary
         */
        private const val FREQUENCY_FOR_CONTACTS = 128
        private const val FREQUENCY_FOR_CONTACTS_BIGRAM = 90
        private const val INDEX_NAME = 1
    }
}
