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

internal class MiniKeyboardKeyDetector(slideAllowance: Float) : KeyDetector() {
    private val mSlideAllowanceSquare: Int
    private val mSlideAllowanceSquareTop: Int

    init {
        mSlideAllowanceSquare = (slideAllowance * slideAllowance).toInt()
        // Top slide allowance is slightly longer (sqrt(2) times) than other edges.
        mSlideAllowanceSquareTop = mSlideAllowanceSquare * 2
    }

    override fun getKeyIndexAndNearbyCodes(x: Int, y: Int, allKeys: IntArray?): Int {
        val keys: Array<Keyboard.Key> = keys
        val touchX = getTouchX(x)
        val touchY = getTouchY(y)
        var closestKeyIndex = LatinKeyboardBaseView.NOT_A_KEY
        var closestKeyDist = if (y < 0) mSlideAllowanceSquareTop else mSlideAllowanceSquare
        val keyCount = keys.size
        for (i in 0 until keyCount) {
            val key = keys[i]
            val dist = key.squaredDistanceFrom(touchX, touchY)
            if (dist < closestKeyDist) {
                closestKeyIndex = i
                closestKeyDist = dist
            }
        }
        if (allKeys != null && closestKeyIndex != LatinKeyboardBaseView.NOT_A_KEY)
            allKeys[0] = keys[closestKeyIndex].primaryCode
        return closestKeyIndex
    }

    override val maxNearbyKeys: Int
        get() = MAX_NEARBY_KEYS

    companion object {
        private const val MAX_NEARBY_KEYS = 1
    }
}
