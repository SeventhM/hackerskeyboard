/*
**
** Copyright 2009, The Android Open Source Project
**
** Licensed under the Apache License, Version 2.0 (the "License");
** you may not use this file except in compliance with the License.
** You may obtain a copy of the License at
**
**     http://www.apache.org/licenses/LICENSE-2.0
**
** Unless required by applicable law or agreed to in writing, software
** distributed under the License is distributed on an "AS IS" BASIS,
** WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
** See the License for the specific language governing permissions and
** limitations under the License.
*/

#include <stdio.h>
#include <assert.h>
#include <unistd.h>
#include <fcntl.h>

#include <jni.h>
#include "dictionary.h"
#include <android/log.h>

// ----------------------------------------------------------------------------

using namespace latinime;

//
// helper function to throw an exception
//
static void throwException(JNIEnv *env, const char* ex, const char* fmt, int data)
{
    if (jclass cls = env->FindClass(ex)) {
        char msg[1000];
        sprintf(msg, fmt, data);
        env->ThrowNew(cls, msg);
        env->DeleteLocalRef(cls);
    }
}

static jlong latinime_BinaryDictionary_open
    (JNIEnv *env, jobject object, jobject dictDirectBuffer,
        jint typedLetterMultiplier, jint fullWordMultiplier, jint size)
{
    void *dict = env->GetDirectBufferAddress(dictDirectBuffer);
    if (dict == NULL) {
        __android_log_print(ANDROID_LOG_ERROR, "NativeDict", "DICT: Dictionary buffer is null");
        //fprintf(stderr, "DICT: Dictionary buffer is null\n");
        return 0;
    }
    Dictionary *dictionary = new Dictionary(dict, typedLetterMultiplier, fullWordMultiplier, size);
    return (jlong) dictionary;
}

static int latinime_BinaryDictionary_getSuggestions(
    JNIEnv *env, jobject object, jlong dict, jintArray inputArray, jint arraySize,
    jcharArray outputArray, jintArray frequencyArray, jint maxWordLength, jint maxWords,
    jint maxAlternatives, jint skipPos, jintArray nextLettersArray, jint nextLettersSize)
{
    Dictionary *dictionary = (Dictionary*) dict;
    if (dictionary == NULL) return 0;

    int *frequencies = env->GetIntArrayElements(frequencyArray, NULL);
    int *inputCodes = env->GetIntArrayElements(inputArray, NULL);
    jchar *outputChars = env->GetCharArrayElements(outputArray, NULL);
    int *nextLetters = nextLettersArray != NULL ? env->GetIntArrayElements(nextLettersArray, NULL)
        : NULL;

    int count = dictionary->getSuggestions(inputCodes, arraySize, (unsigned short*) outputChars,
        frequencies, maxWordLength, maxWords, maxAlternatives, skipPos, nextLetters,
        nextLettersSize);

    env->ReleaseIntArrayElements(frequencyArray, frequencies, 0);
    env->ReleaseIntArrayElements(inputArray, inputCodes, JNI_ABORT);
    env->ReleaseCharArrayElements(outputArray, outputChars, 0);
    if (nextLetters) {
        env->ReleaseIntArrayElements(nextLettersArray, nextLetters, 0);
    }

    return count;
}

static int latinime_BinaryDictionary_getBigrams
    (JNIEnv *env, jobject object, jlong dict, jcharArray prevWordArray, jint prevWordLength,
        jintArray inputArray, jint inputArraySize, jcharArray outputArray,
        jintArray frequencyArray, jint maxWordLength, jint maxBigrams, jint maxAlternatives)
{
    Dictionary *dictionary = (Dictionary*) dict;
    if (dictionary == NULL) return 0;

    jchar *prevWord = env->GetCharArrayElements(prevWordArray, NULL);
    int *inputCodes = env->GetIntArrayElements(inputArray, NULL);
    jchar *outputChars = env->GetCharArrayElements(outputArray, NULL);
    int *frequencies = env->GetIntArrayElements(frequencyArray, NULL);

    int count = dictionary->getBigrams((unsigned short*) prevWord, prevWordLength, inputCodes,
        inputArraySize, (unsigned short*) outputChars, frequencies, maxWordLength, maxBigrams,
        maxAlternatives);

    env->ReleaseCharArrayElements(prevWordArray, prevWord, JNI_ABORT);
    env->ReleaseIntArrayElements(inputArray, inputCodes, JNI_ABORT);
    env->ReleaseCharArrayElements(outputArray, outputChars, 0);
    env->ReleaseIntArrayElements(frequencyArray, frequencies, 0);

    return count;
}


static jboolean latinime_BinaryDictionary_isValidWord
    (JNIEnv *env, jobject object, jlong dict, jcharArray wordArray, jint wordLength)
{
    Dictionary *dictionary = (Dictionary*) dict;
    if (dictionary == NULL) return (jboolean) false;

    jchar *word = env->GetCharArrayElements(wordArray, NULL);
    jboolean result = dictionary->isValidWord((unsigned short*) word, wordLength);
    env->ReleaseCharArrayElements(wordArray, word, JNI_ABORT);

    return result;
}

static void latinime_BinaryDictionary_close
    (JNIEnv *env, jobject object, jlong dict)
{
    Dictionary *dictionary = (Dictionary*) dict;
    delete (Dictionary*) dict;
}

// ----------------------------------------------------------------------------

extern "C"
JNIEXPORT jint JNICALL
Java_org_pocketworkstation_pckeyboard_BinaryDictionary_getBigramsNative(JNIEnv *env, jobject thiz,
    jlong dict, jcharArray prev_word, jint prev_word_length, jintArray input_codes, jint input_codes_length,
    jcharArray output_chars, jintArray frequencies, jint max_word_length, jint max_bigrams, jint max_alternatives) {
    return latinime_BinaryDictionary_getBigrams (env, thiz, dict, prev_word, prev_word_length,
        input_codes,input_codes_length, output_chars, frequencies,
        max_word_length,max_bigrams, max_alternatives);
}

extern "C"
JNIEXPORT jboolean JNICALL
Java_org_pocketworkstation_pckeyboard_BinaryDictionary_isValidWordNative(JNIEnv *env, jobject thiz,
    jlong native_data, jcharArray word, jint word_length) {
    return latinime_BinaryDictionary_isValidWord(env, thiz,native_data, word, word_length);
}

extern "C"
JNIEXPORT jint JNICALL
Java_org_pocketworkstation_pckeyboard_BinaryDictionary_getSuggestionsNative(JNIEnv *env,
    jobject thiz, jlong dict, jintArray input_codes, jint codes_size, jcharArray output_chars,
    jintArray frequencies, jint max_word_length, jint max_words, jint max_alternatives,
    jint skip_pos, jintArray next_letters_frequencies, jint next_letters_size) {
    return latinime_BinaryDictionary_getSuggestions(env, thiz, dict, input_codes, codes_size, output_chars,
        frequencies, max_word_length, max_words, max_alternatives, skip_pos, next_letters_frequencies,
        next_letters_size);
}

extern "C"
JNIEXPORT void JNICALL
Java_org_pocketworkstation_pckeyboard_BinaryDictionary_closeNative(JNIEnv *env, jobject thiz, jlong dict) {
    return latinime_BinaryDictionary_close(env, thiz, dict);
}

extern "C"
JNIEXPORT jlong JNICALL
Java_org_pocketworkstation_pckeyboard_BinaryDictionary_openNative(JNIEnv *env, jobject thiz,
    jobject bb, jint typed_letter_multiplier, jint full_word_multiplier, jint dict_size) {
    return latinime_BinaryDictionary_open(env, thiz, bb,
        typed_letter_multiplier, full_word_multiplier, dict_size);
}