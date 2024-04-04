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
import android.os.AsyncTask
import android.provider.BaseColumns
import android.util.Log
import kotlin.concurrent.Volatile

/**
 * Stores all the pairs user types in databases. Prune the database if the size
 * gets too big. Unlike AutoDictionary, it even stores the pairs that are already
 * in the dictionary.
 */
class UserBigramDictionary(
        context: Context, private val mIme: LatinIME?,
        /** Locale for which this auto dictionary is storing words  */
    private val mLocale: String?, dicTypeId: Int
) : ExpandableDictionary(context, dicTypeId) {
    private var mPendingWrites = HashSet<Bigram>()
    private val mPendingWritesLock = Any()

    private class Bigram(
        var word1: String,
        var word2: String,
        var frequency: Int
    ) {
        override fun equals(other: Any?): Boolean {
            if (other?.javaClass != Bigram::class.java) return false
            val bigram2 = other as Bigram?
            return word1 == bigram2!!.word1 && word2 == bigram2.word2
        }

        override fun hashCode(): Int {
            return "$word1 $word2".hashCode()
        }
    }

    fun setDatabaseMax(maxUserBigram: Int) {
        sMaxUserBigrams = maxUserBigram
    }

    fun setDatabaseDelete(deleteUserBigram: Int) {
        sDeleteUserBigrams = deleteUserBigram
    }

    init {
        if (sOpenHelper == null) {
            sOpenHelper = DatabaseHelper(context)
        }
        if (mLocale != null && mLocale.length > 1) {
            loadDictionary()
        }
    }

    override fun close() {
        flushPendingWrites()
        // Don't close the database as locale changes will require it to be reopened anyway
        // Also, the database is written to somewhat frequently, so it needs to be kept alive
        // throughout the life of the process.
        // mOpenHelper.close();
        super.close()
    }

    /**
     * Pair will be added to the userbigram database.
     */
    fun addBigrams(word1: String, word2: String): Int {
        // remove caps
        var word2 = word2
        if (mIme != null && mIme.currentWord.isAutoCapitalized) {
            word2 = word2[0].lowercaseChar().toString() + word2.substring(1)
        }

        var freq = super.addBigram(word1, word2, FREQUENCY_FOR_TYPED)
        if (freq > FREQUENCY_MAX) freq = FREQUENCY_MAX
        synchronized(mPendingWritesLock) {
            if (freq == FREQUENCY_FOR_TYPED || mPendingWrites.isEmpty()) {
                mPendingWrites.add(Bigram(word1, word2, freq))
            } else {
                val bi = Bigram(word1, word2, freq)
                mPendingWrites.remove(bi)
                mPendingWrites.add(bi)
            }
        }

        return freq
    }

    /**
     * Schedules a background thread to write any pending words to the database.
     */
    fun flushPendingWrites() {
        synchronized(mPendingWritesLock) {

            // Nothing pending? Return
            if (mPendingWrites.isEmpty()) return
            // Create a background thread to write the pending entries
            UpdateDbTask(context, sOpenHelper, mPendingWrites, mLocale).execute()
            // Create a new map for writing new entries into while the old one is written to db
            mPendingWrites = HashSet()
        }
    }

    /** Used for testing purpose  */
    fun waitUntilUpdateDBDone() {
        synchronized(mPendingWritesLock) {
            while (sUpdatingDB) {
                try {
                    Thread.sleep(100)
                } catch (ignored: InterruptedException) {
                }
            }
        }
    }

    override fun loadDictionaryAsync() {
        // Load the words that correspond to the current input locale
        query("$MAIN_COLUMN_LOCALE=?", arrayOf(mLocale)).use { cursor ->
            if (cursor.moveToFirst()) {
                val word1Index = cursor.getColumnIndex(MAIN_COLUMN_WORD1)
                val word2Index = cursor.getColumnIndex(MAIN_COLUMN_WORD2)
                val frequencyIndex = cursor.getColumnIndex(FREQ_COLUMN_FREQUENCY)
                while (!cursor.isAfterLast) {
                    val word1 = cursor.getString(word1Index)
                    val word2 = cursor.getString(word2Index)
                    val frequency = cursor.getInt(frequencyIndex)
                    // Safeguard against adding really long words. Stack may overflow due
                    // to recursive lookup
                    if (word1.length < MAX_WORD_LENGTH && word2.length < MAX_WORD_LENGTH) {
                        super.setBigram(word1, word2, frequency)
                    }
                    cursor.moveToNext()
                }
            }
        }
    }

    /**
     * Query the database
     */
    private fun query(selection: String, selectionArgs: Array<String?>): Cursor {
        val qb = SQLiteQueryBuilder()

        // main INNER JOIN frequency ON (main._id=freq.pair_id)
        qb.tables = (
                "$MAIN_TABLE_NAME INNER JOIN $FREQ_TABLE_NAME ON ("
                + "${MAIN_TABLE_NAME}.${MAIN_COLUMN_ID}=${FREQ_TABLE_NAME}."
                + "${FREQ_COLUMN_PAIR_ID})")
        qb.projectionMap = sDictProjectionMap

        // Get the database and run the query
        val db = sOpenHelper!!.readableDatabase
        return qb.query(
            db, arrayOf(MAIN_COLUMN_WORD1, MAIN_COLUMN_WORD2, FREQ_COLUMN_FREQUENCY),
            selection, selectionArgs, null, null, null
        )
    }

    /**
     * This class helps open, create, and upgrade the database file.
     */
    private class DatabaseHelper(context: Context?) :
        SQLiteOpenHelper(context, DATABASE_NAME, null, DATABASE_VERSION) {
        override fun onCreate(db: SQLiteDatabase) {
            db.execSQL("PRAGMA foreign_keys = ON;")
            db.execSQL(
                "CREATE TABLE " + MAIN_TABLE_NAME + " ("
                        + MAIN_COLUMN_ID + " INTEGER PRIMARY KEY,"
                        + MAIN_COLUMN_WORD1 + " TEXT,"
                        + MAIN_COLUMN_WORD2 + " TEXT,"
                        + MAIN_COLUMN_LOCALE + " TEXT"
                        + ");"
            )
            db.execSQL(
                "CREATE TABLE " + FREQ_TABLE_NAME + " ("
                        + FREQ_COLUMN_ID + " INTEGER PRIMARY KEY,"
                        + FREQ_COLUMN_PAIR_ID + " INTEGER,"
                        + FREQ_COLUMN_FREQUENCY + " INTEGER,"
                        + "FOREIGN KEY(" + FREQ_COLUMN_PAIR_ID + ") REFERENCES " + MAIN_TABLE_NAME
                        + "(" + MAIN_COLUMN_ID + ")" + " ON DELETE CASCADE"
                        + ");"
            )
        }

        override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {
            Log.w(
                TAG, "Upgrading database from version $oldVersion to "
                        + "${newVersion}, which will destroy all old data"
            )
            db.execSQL("DROP TABLE IF EXISTS $MAIN_TABLE_NAME")
            db.execSQL("DROP TABLE IF EXISTS $FREQ_TABLE_NAME")
            onCreate(db)
        }
    }

    /**
     * Async task to write pending words to the database so that it stays in sync with
     * the in-memory trie.
     */
    private class UpdateDbTask(
        context: Context?, private val mDbHelper: DatabaseHelper?,
        private val mMap: HashSet<Bigram>, private val mLocale: String?
    ) : AsyncTask<Void?, Void?, Void?>() {
        /** Prune any old data if the database is getting too big.  */
        private fun checkPruneData(db: SQLiteDatabase) {
            db.execSQL("PRAGMA foreign_keys = ON;")
            db.query(
                FREQ_TABLE_NAME, arrayOf(FREQ_COLUMN_PAIR_ID),
                null, null, null, null, null
            ).use { c ->
                val totalRowCount = c.count
                // prune out old data if we have too much data
                if (totalRowCount > sMaxUserBigrams) {
                    val numDeleteRows = totalRowCount - sMaxUserBigrams + sDeleteUserBigrams
                    val pairIdColumnId = c.getColumnIndex(FREQ_COLUMN_PAIR_ID)
                    c.moveToFirst()
                    var count = 0
                    while (count < numDeleteRows && !c.isAfterLast) {
                        val pairId = c.getString(pairIdColumnId)
                        // Deleting from MAIN table will delete the frequencies
                        // due to FOREIGN KEY .. ON DELETE CASCADE
                        db.delete(MAIN_TABLE_NAME, "$MAIN_COLUMN_ID=?", arrayOf(pairId))
                        c.moveToNext()
                        count++
                    }
                }
            }
        }

        @Deprecated("Deprecated in Java")
        override fun onPreExecute() {
            sUpdatingDB = true
        }

        @Deprecated("Deprecated in Java")
        override fun doInBackground(vararg v: Void?): Void? {
            val db = mDbHelper!!.writableDatabase
            db.execSQL("PRAGMA foreign_keys = ON;")
            // Write all the entries to the db
            for (bi in mMap) {
                // find pair id
                val c = db.query(
                    MAIN_TABLE_NAME,
                    arrayOf(MAIN_COLUMN_ID),
                    MAIN_COLUMN_WORD1 + "=? AND " + MAIN_COLUMN_WORD2 + "=? AND "
                            + MAIN_COLUMN_LOCALE + "=?",
                    arrayOf(bi.word1, bi.word2, mLocale),
                    null,
                    null,
                    null
                )
                var pairId: Int
                if (c.moveToFirst()) {
                    val columnIndex = c.getColumnIndex(MAIN_COLUMN_ID)
                    // existing pair
                    pairId = c.getInt(columnIndex)
                    db.delete(
                        FREQ_TABLE_NAME,
                        "$FREQ_COLUMN_PAIR_ID=?",
                        arrayOf(pairId.toString())
                    )
                } else {
                    // new pair
                    val pairIdLong = db.insert(
                        MAIN_TABLE_NAME, null,
                        getContentValues(bi.word1, bi.word2, mLocale)
                    )
                    pairId = pairIdLong.toInt()
                }
                c.close()

                // insert new frequency
                db.insert(FREQ_TABLE_NAME, null, getFrequencyContentValues(pairId, bi.frequency))
            }
            checkPruneData(db)
            sUpdatingDB = false
            return null
        }

        private fun getContentValues(word1: String, word2: String, locale: String?): ContentValues {
            val values = ContentValues(3)
            values.put(MAIN_COLUMN_WORD1, word1)
            values.put(MAIN_COLUMN_WORD2, word2)
            values.put(MAIN_COLUMN_LOCALE, locale)
            return values
        }

        private fun getFrequencyContentValues(pairId: Int, frequency: Int): ContentValues {
            val values = ContentValues(2)
            values.put(FREQ_COLUMN_PAIR_ID, pairId)
            values.put(FREQ_COLUMN_FREQUENCY, frequency)
            return values
        }
    }

    companion object {
        private const val TAG = "UserBigramDictionary"

        /** Any pair being typed or picked  */
        private const val FREQUENCY_FOR_TYPED = 2

        /** Maximum frequency for all pairs  */
        private const val FREQUENCY_MAX = 127

        /**
         * If this pair is typed 6 times, it would be suggested.
         * Should be smaller than ContactsDictionary.FREQUENCY_FOR_CONTACTS_BIGRAM
         */
        const val SUGGEST_THRESHOLD = 6 * FREQUENCY_FOR_TYPED

        /** Maximum number of pairs. Pruning will start when databases goes above this number.  */
        private var sMaxUserBigrams = 10000

        /**
         * When it hits maximum bigram pair, it will delete until you are left with
         * only (sMaxUserBigrams - sDeleteUserBigrams) pairs.
         * Do not keep this number small to avoid deleting too often.
         */
        private var sDeleteUserBigrams = 1000

        /**
         * Database version should increase if the database structure changes
         */
        private const val DATABASE_VERSION = 1
        private const val DATABASE_NAME = "userbigram_dict.db"

        /** Name of the words table in the database  */
        private const val MAIN_TABLE_NAME = "main"

        // TODO: Consume less space by using a unique id for locale instead of the whole
        // 2-5 character string. (Same TODO from AutoDictionary)
        private const val MAIN_COLUMN_ID = BaseColumns._ID
        private const val MAIN_COLUMN_WORD1 = "word1"
        private const val MAIN_COLUMN_WORD2 = "word2"
        private const val MAIN_COLUMN_LOCALE = "locale"

        /** Name of the frequency table in the database  */
        private const val FREQ_TABLE_NAME = "frequency"
        private const val FREQ_COLUMN_ID = BaseColumns._ID
        private const val FREQ_COLUMN_PAIR_ID = "pair_id"
        private const val FREQ_COLUMN_FREQUENCY = "freq"

        @Volatile
        private var sUpdatingDB = false
        private val sDictProjectionMap: HashMap<String, String> = HashMap()

        init {
            sDictProjectionMap[MAIN_COLUMN_ID] = MAIN_COLUMN_ID
            sDictProjectionMap[MAIN_COLUMN_WORD1] = MAIN_COLUMN_WORD1
            sDictProjectionMap[MAIN_COLUMN_WORD2] = MAIN_COLUMN_WORD2
            sDictProjectionMap[MAIN_COLUMN_LOCALE] = MAIN_COLUMN_LOCALE

            sDictProjectionMap[FREQ_COLUMN_ID] = FREQ_COLUMN_ID
            sDictProjectionMap[FREQ_COLUMN_PAIR_ID] = FREQ_COLUMN_PAIR_ID
            sDictProjectionMap[FREQ_COLUMN_FREQUENCY] = FREQ_COLUMN_FREQUENCY
        }

        private var sOpenHelper: DatabaseHelper? = null
    }
}
