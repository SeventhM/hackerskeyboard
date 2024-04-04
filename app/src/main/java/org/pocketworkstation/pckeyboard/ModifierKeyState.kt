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

internal class ModifierKeyState {
    private var mState = RELEASING
    fun onPress() {
        mState = PRESSING
    }

    fun onRelease() {
        mState = RELEASING
    }

    fun onOtherKeyPressed() {
        if (mState == PRESSING) mState = CHORDING
    }

    val isChording: Boolean
        get() = mState == CHORDING

    override fun toString(): String {
        return "ModifierKeyState:$mState"
    }

    companion object {
        private const val RELEASING = 0
        private const val PRESSING = 1
        private const val CHORDING = 2
    }
}
