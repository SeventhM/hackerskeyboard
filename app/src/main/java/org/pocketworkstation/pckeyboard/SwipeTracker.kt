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

import android.view.MotionEvent
import kotlin.math.max
import kotlin.math.min

internal class SwipeTracker {
    val mBuffer = EventRingBuffer(NUM_PAST)
    var mYVelocity = 0f
    var mXVelocity = 0f

    fun addMovement(ev: MotionEvent) {
        if (ev.action == MotionEvent.ACTION_DOWN) {
            mBuffer.clear()
            return
        }
        val time = ev.eventTime
        val count = ev.historySize
        for (i in 0 until count) {
            addPoint(ev.getHistoricalX(i), ev.getHistoricalY(i), ev.getHistoricalEventTime(i))
        }
        addPoint(ev.x, ev.y, time)
    }

    private fun addPoint(x: Float, y: Float, time: Long) {
        val buffer = mBuffer
        while (buffer.size > 0) {
            val lastT = buffer.getTime(0)
            if (lastT >= time - LONGEST_PAST_TIME) break
            buffer.dropOldest()
        }
        buffer.add(x, y, time)
    }

    @JvmOverloads
    fun computeCurrentVelocity(units: Int, maxVelocity: Float = Float.MAX_VALUE) {
        val buffer = mBuffer
        val oldestX = buffer.getX(0)
        val oldestY = buffer.getY(0)
        val oldestTime = buffer.getTime(0)

        var accumX = 0f
        var accumY = 0f
        val count = buffer.size
        for (pos in 1 until count) {
            val dur = (buffer.getTime(pos) - oldestTime).toInt()
            if (dur == 0) continue
            var dist = buffer.getX(pos) - oldestX
            var vel = dist / dur * units // pixels/frame.
            accumX = if (accumX == 0f) vel
            else (accumX + vel) * .5f

            dist = buffer.getY(pos) - oldestY
            vel = dist / dur * units // pixels/frame.
            accumY = if (accumY == 0f) vel
            else (accumY + vel) * .5f
        }
        mXVelocity = if (accumX < 0.0f) max(accumX, -maxVelocity)
        else min(accumX, maxVelocity)
        mYVelocity = if (accumY < 0.0f) max(accumY, -maxVelocity)
        else min(accumY, maxVelocity)
    }

    val yVelocity get() = mYVelocity
    val xVelocity get() = mXVelocity

    internal class EventRingBuffer(private val bufSize: Int) {
        private val xBuf: FloatArray = FloatArray(bufSize)
        private val yBuf: FloatArray = FloatArray(bufSize)
        private val timeBuf: LongArray = LongArray(bufSize)
        private var top = 0 // points new event
        private var end = 0 // points oldest event
        private var count = 0 // the number of valid data

        fun clear() {
            count = 0
            end = 0
            top = 0
        }

        @get:JvmName("size")
        val size get() = count

        // Position 0 points oldest event
        private fun index(pos: Int): Int {
            return (end + pos) % bufSize
        }

        private fun advance(index: Int): Int {
            return (index + 1) % bufSize
        }

        fun add(x: Float, y: Float, time: Long) {
            xBuf[top] = x
            yBuf[top] = y
            timeBuf[top] = time
            top = advance(top)
            if (count < bufSize) {
                count++
            } else {
                end = advance(end)
            }
        }

        fun getX(pos: Int): Float {
            return xBuf[index(pos)]
        }

        fun getY(pos: Int): Float {
            return yBuf[index(pos)]
        }

        fun getTime(pos: Int): Long {
            return timeBuf[index(pos)]
        }

        fun dropOldest() {
            count--
            end = advance(end)
        }
    }

    companion object {
        private const val NUM_PAST = 4
        private const val LONGEST_PAST_TIME = 200
    }
}