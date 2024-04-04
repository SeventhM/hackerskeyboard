/*
 * Copyright (C) 2010 The Android Open Source Project
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
import android.os.AsyncTask
import android.text.format.DateUtils
import android.util.Log

object LatinIMEUtil {
    /**
     * Cancel an [AsyncTask].
     *
     * @param mayInterruptIfRunning <tt>true</tt> if the thread executing this
     * task should be interrupted; otherwise, in-progress tasks are allowed
     * to complete.
     */
    fun cancelTask(task: AsyncTask<*, *, *>?, mayInterruptIfRunning: Boolean) {
        if (task != null && task.status != AsyncTask.Status.FINISHED) {
            task.cancel(mayInterruptIfRunning)
        }
    }

    class GCUtils {

        private var mGCTryCount = 0
        fun reset() {
            mGCTryCount = 0
        }

        fun tryGCOrWait(metaData: String?, t: Throwable?): Boolean {
            if (mGCTryCount == 0) {
                System.gc()
            }
            return if (++mGCTryCount > GC_TRY_COUNT) {
                false
            } else {
                try {
                    Thread.sleep(GC_INTERVAL)
                    true
                } catch (e: InterruptedException) {
                    Log.e(TAG, "Sleep was interrupted.")
                    false
                }
            }
        }

        companion object {
            private const val TAG = "GCUtils"
            const val GC_TRY_COUNT = 2

            // GC_TRY_LOOP_MAX is used for the hard limit of GC wait,
            // GC_TRY_LOOP_MAX should be greater than GC_TRY_COUNT.
            const val GC_TRY_LOOP_MAX = 5
            private const val GC_INTERVAL = DateUtils.SECOND_IN_MILLIS
            val instance = GCUtils()
        }
    }

    /* package */
    internal class RingCharBuffer private constructor() {
        private var mContext: Context? = null
        private var mEnabled = false
        private var mEnd = 0

        /* package */
        var mLength = 0
        private val mCharBuf = CharArray(BUFSIZE)
        private val mXBuf = IntArray(BUFSIZE)
        private val mYBuf = IntArray(BUFSIZE)
        private fun normalize(`in`: Int): Int {
            val ret = `in` % BUFSIZE
            return if (ret < 0) ret + BUFSIZE else ret
        }

        fun push(c: Char, x: Int, y: Int) {
            if (!mEnabled) return
            mCharBuf[mEnd] = c
            mXBuf[mEnd] = x
            mYBuf[mEnd] = y
            mEnd = normalize(mEnd + 1)
            if (mLength < BUFSIZE) {
                ++mLength
            }
        }

        fun pop(): Char {
            return if (mLength < 1) {
                PLACEHOLDER_DELIMITER_CHAR
            } else {
                mEnd = normalize(mEnd - 1)
                --mLength
                mCharBuf[mEnd]
            }
        }

        val lastChar: Char
            get() = if (mLength < 1) {
                PLACEHOLDER_DELIMITER_CHAR
            } else {
                mCharBuf[normalize(mEnd - 1)]
            }

        fun getPreviousX(c: Char, back: Int): Int {
            val index = normalize(mEnd - 2 - back)
            return if (mLength <= back
                || c.lowercaseChar() != mCharBuf[index].lowercaseChar()
            ) {
                INVALID_COORDINATE
            } else {
                mXBuf[index]
            }
        }

        fun getPreviousY(c: Char, back: Int): Int {
            val index = normalize(mEnd - 2 - back)
            return if (mLength <= back
                || c.lowercaseChar() != mCharBuf[index].lowercaseChar()
            ) {
                INVALID_COORDINATE
            } else {
                mYBuf[index]
            }
        }

        val lastString: String
            get() {
                val sb = StringBuilder()
                for (i in 0 until mLength) {
                    val c = mCharBuf[normalize(mEnd - 1 - i)]
                    if (!(mContext as LatinIME?)!!.isWordSeparator(c.code)) {
                        sb.append(c)
                    } else {
                        break
                    }
                }
                return sb.reverse().toString()
            }

        fun reset() {
            mLength = 0
        }

        companion object {
            val instance = RingCharBuffer()
            private const val PLACEHOLDER_DELIMITER_CHAR = '\uFFFC'
            private const val INVALID_COORDINATE = -2

            /* package */
            const val BUFSIZE = 20
            fun init(context: Context?, enabled: Boolean): RingCharBuffer {
                instance.mContext = context
                instance.mEnabled = enabled
                return instance
            }
        }
    }
}
