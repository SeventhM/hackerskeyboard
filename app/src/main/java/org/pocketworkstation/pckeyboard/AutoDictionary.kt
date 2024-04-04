/*
 * Copyright (C) 2010 Google Inc.
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

import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.database.sqlite.SQLiteQueryBuilder
import android.provider.BaseColumns
import android.util.Log
import java.util.concurrent.Executors

/**
 * Stores new words temporarily until they are promoted to the user dictionary
 * for longevity. Words in the auto dictionary are used to determine if it's ok
 * to accept a word that's not in the main or user dictionary. Using a new word
 * repeatedly will promote it to the user dictionary.
 */
class AutoDictionary(context: Context,
                     private val mIme: LatinIME, // Locale for which this auto dictionary is storing words
                     private val mLocale: String?,
                     dicTypeId: Int
) : ExpandableDictionary(context, dicTypeId) {
    private var mPendingWrites = HashMap<String, Int?>()
    private val mPendingWritesLock = Any()

    companion object {
        // Weight added to a user picking a new word from the suggestion strip
        const val FREQUENCY_FOR_PICKED = 3

        // Weight added to a user typing a new word that doesn't get corrected (or is reverted)
        const val FREQUENCY_FOR_TYPED = 1

        // A word that is frequently typed and gets promoted to the user dictionary, uses this
        // frequency.
        const val FREQUENCY_FOR_AUTO_ADD = 250

        // If the user touches a typed word 2 times or more, it will become valid.
        private const val VALIDITY_THRESHOLD = 2 * FREQUENCY_FOR_PICKED

        // If the user touches a typed word 4 times or more, it will be added to the user dict.
        private const val PROMOTION_THRESHOLD = 4 * FREQUENCY_FOR_PICKED
        private const val DATABASE_NAME = "auto_dict.db"
        private const val DATABASE_VERSION = 1

        // These are the columns in the dictionary
        // TODO: Consume less space by using a unique id for locale instead of the whole
        // 2-5 character string.
        private const val COLUMN_ID = BaseColumns._ID
        private const val COLUMN_WORD = "word"
        private const val COLUMN_FREQUENCY = "freq"
        private const val COLUMN_LOCALE = "locale"

        /** Sort by descending order of frequency.  */
        const val DEFAULT_SORT_ORDER = "$COLUMN_FREQUENCY DESC"

        /** Name of the words table in the auto_dict.db  */
        private const val AUTODICT_TABLE_NAME = "words"
        private val sDictProjectionMap: HashMap<String, String> = HashMap()

        init {
            sDictProjectionMap[COLUMN_ID] = COLUMN_ID
            sDictProjectionMap[COLUMN_WORD] = COLUMN_WORD
            sDictProjectionMap[COLUMN_FREQUENCY] = COLUMN_FREQUENCY
            sDictProjectionMap[COLUMN_LOCALE] = COLUMN_LOCALE
        }

        private var sOpenHelper: DatabaseHelper? = null
    }

    init {
        if (sOpenHelper == null) {
            sOpenHelper = DatabaseHelper(context)
        }
        if (mLocale != null && mLocale.length > 1) {
            loadDictionary()
        }
    }

    override fun isValidWord(word: CharSequence?): Boolean {
        val frequency = getWordFrequency(word)
        return frequency >= VALIDITY_THRESHOLD
    }

    override fun close() {
        flushPendingWrites()
        // Don't close the database as locale changes will require it to be reopened anyway
        // Also, the database is written to somewhat frequently, so it needs to be kept alive
        // throughout the life of the process.
        // mOpenHelper.close();
        super.close()
    }

    override fun loadDictionaryAsync() {
        // Load the words that correspond to the current input locale
        query("$COLUMN_LOCALE=?", arrayOf(mLocale)).use { cursor ->
            if (cursor.moveToFirst()) {
                val wordIndex = cursor.getColumnIndex(COLUMN_WORD)
                val frequencyIndex = cursor.getColumnIndex(COLUMN_FREQUENCY)
                while (!cursor.isAfterLast) {
                    val word = cursor.getString(wordIndex)
                    val frequency = cursor.getInt(frequencyIndex)
                    // Safeguard against adding really long words. Stack may overflow due
                    // to recursive lookup
                    if (word.length < MAX_WORD_LENGTH) {
                        super.addWord(word, frequency)
                    }
                    cursor.moveToNext()
                }
            }
        }
    }

    override fun addWord(word: String, frequency: Int) {
        var word = word
        val length = word.length
        // Don't add very short or very long words.
        if (length < 2 || length > MAX_WORD_LENGTH) return
        if (mIme.currentWord.isAutoCapitalized) {
            // Remove caps before adding
            word = word[0].lowercaseChar().toString() + word.substring(1)
        }
        var freq = getWordFrequency(word)
        freq = if (freq < 0) frequency else freq + frequency
        super.addWord(word, freq)
        if (freq >= PROMOTION_THRESHOLD) {
            mIme.promoteToUserDictionary(word, FREQUENCY_FOR_AUTO_ADD)
            freq = 0
        }
        synchronized(mPendingWritesLock) {
            // Write a null frequency if it is to be deleted from the db
            mPendingWrites.put(word, if (freq == 0) null else freq)
        }
    }

    /**
     * Schedules a background thread to write any pending words to the database.
     */
    fun flushPendingWrites() {
        synchronized(mPendingWritesLock) {

            // Nothing pending? Return
            if (mPendingWrites.isEmpty()) return
            // Create a background thread to write the pending entries
            updateDb(context, sOpenHelper, mPendingWrites, mLocale)
            // Create a new map for writing new entries into while the old one is written to db
            mPendingWrites = HashMap()
        }
    }

    /**
     * This class helps open, create, and upgrade the database file.
     */
    private class DatabaseHelper(context: Context?):
            SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("CREATE TABLE " + AUTODICT_TABLE_NAME + " ("
                    + COLUMN_ID + " INTEGER PRIMARY KEY,"
                    + COLUMN_WORD + " TEXT,"
                    + COLUMN_FREQUENCY + " INTEGER,"
                    + COLUMN_LOCALE + " TEXT"
                    + ");")
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            Log.w("AutoDictionary", "Upgrading database from version " + oldVersion + " to "
                    + newVersion + ", which will destroy all old data")
            db.execSQL("DROP TABLE IF EXISTS $AUTODICT_TABLE_NAME")
            onCreate(db)
        }
    }

    private fun query(selection: String, selectionArgs: Array<String?>): Cursor {
        val qb = SQLiteQueryBuilder()
        qb.tables = AUTODICT_TABLE_NAME
        qb.projectionMap = sDictProjectionMap

        // Get the database and run the query
        val db = sOpenHelper!!.readableDatabase
        return qb.query(db, null, selection, selectionArgs, null, null,
                DEFAULT_SORT_ORDER)
    }

    /**
     * Async task to write pending words to the database so that it stays in sync with
     * the in-memory trie.
     */
    private fun updateDb(context: Context, openHelper: DatabaseHelper?,
                         pendingWrites: HashMap<String, Int?>, locale: String?) {
        val executor = Executors.newSingleThreadExecutor()
        executor.execute(object : Runnable {
            override fun run() {
                val db = openHelper!!.writableDatabase
                // Write all the entries to the db
                val mEntries: Set<Map.Entry<String, Int?>> = pendingWrites.entries
                for ((key, freq) in mEntries) {
                    db.delete(AUTODICT_TABLE_NAME, "$COLUMN_WORD=? AND $COLUMN_LOCALE=?", arrayOf(key, locale))
                    if (freq != null) {
                        db.insert(AUTODICT_TABLE_NAME, null,
                                getContentValues(key, freq, locale))
                    }
                }
            }

            private fun getContentValues(word: String, frequency: Int, locale: String?): ContentValues {
                val values = ContentValues(4)
                values.put(COLUMN_WORD, word)
                values.put(COLUMN_FREQUENCY, frequency)
                values.put(COLUMN_LOCALE, locale)
                return values
            }
        })
    }
}
