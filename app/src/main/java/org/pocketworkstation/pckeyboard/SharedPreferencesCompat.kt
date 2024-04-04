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

import android.content.SharedPreferences
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method

/**
 * Reflection utils to call SharedPreferences$Editor.apply when possible,
 * falling back to commit when apply isn't available.
 */
object SharedPreferencesCompat {
    private val sApplyMethod = findApplyMethod()
    private fun findApplyMethod(): Method? {
        try {
            return SharedPreferences.Editor::class.java.getMethod("apply")
        } catch (unused: NoSuchMethodException) {
            // fall through
        }
        return null
    }

    fun apply(editor: SharedPreferences.Editor) {
        if (sApplyMethod != null) {
            try {
                sApplyMethod.invoke(editor)
                return
            } catch (unused: Throwable) {
                when (unused) {
                    is InvocationTargetException,
                    is IllegalAccessException -> {}
                    else -> throw unused
                }
            }
        }
        editor.commit()
    }
}
