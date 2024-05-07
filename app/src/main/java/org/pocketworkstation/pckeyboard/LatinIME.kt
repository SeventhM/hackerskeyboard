/*
 * Copyright (C) 2008 The Android Open Source Project
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

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.DialogInterface
import android.content.Intent
import android.content.IntentFilter
import android.content.SharedPreferences
import android.content.SharedPreferences.OnSharedPreferenceChangeListener
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.inputmethodservice.InputMethodService
import android.media.AudioManager
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.Message
import android.os.SystemClock
import android.os.Vibrator
import android.text.TextUtils
import android.util.Log
import android.util.PrintWriterPrinter
import android.util.Printer
import android.view.HapticFeedbackConstants
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.view.inputmethod.CompletionInfo
import android.view.inputmethod.EditorInfo
import android.view.inputmethod.ExtractedText
import android.view.inputmethod.ExtractedTextRequest
import android.view.inputmethod.InputConnection
import android.view.inputmethod.InputMethodManager
import android.widget.LinearLayout
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.preference.PreferenceManager
import com.google.android.voiceime.VoiceRecognitionTrigger
import org.pocketworkstation.pckeyboard.DeprecatedExtensions.depLocale
import org.pocketworkstation.pckeyboard.EditingUtil.SelectedWord
import org.pocketworkstation.pckeyboard.KeyboardSwitcher.Companion.instance
import org.xmlpull.v1.XmlPullParserException
import java.io.FileDescriptor
import java.io.IOException
import java.io.PrintWriter
import java.util.Collections
import java.util.Locale
import java.util.regex.Pattern
import kotlin.math.min
import kotlin.math.pow

/**
 * Input method implementation for Qwerty'ish keyboard.
 */
class LatinIME : InputMethodService(), ComposeSequencing,
    LatinKeyboardBaseView.OnKeyboardActionListener, OnSharedPreferenceChangeListener {
    // private LatinKeyboardView mInputView;
    private var mCandidateViewContainer: LinearLayout? = null
    private var mCandidateView: CandidateView? = null
    private var mSuggest: Suggest? = null
    private var mCompletions: Array<CompletionInfo>? = null
    private var mOptionsDialog: AlertDialog? = null

    /* package */
    var mKeyboardSwitcher: KeyboardSwitcher? = null
    private var mUserDictionary: UserDictionary? = null
    private var mUserBigramDictionary: UserBigramDictionary? = null

    //private ContactsDictionary mContactsDictionary;
    private var mAutoDictionary: AutoDictionary? = null
    private var mResources: Resources? = null
    private var mInputLocale: String? = null
    private var mSystemLocale: String? = null
    private var mLanguageSwitcher: LanguageSwitcher? = null
    private val mComposing = StringBuilder()
    private var mWord = WordComposer()
    private var mCommittedLength = 0
    private var mPredicting = false
    private var mEnableVoiceButton = false
    private var mBestWord: CharSequence? = null
    private var mPredictionOnForMode = false
    private var mPredictionOnPref = false
    private var mCompletionOn = false
    private var mHasDictionary = false
    private var mAutoSpace = false
    private var mJustAddedAutoSpace = false
    private var mAutoCorrectEnabled = false
    private var mReCorrectionEnabled = false

    // Bigram Suggestion is disabled in this version.
    private val mBigramSuggestionEnabled = false
    private var mAutoCorrectOn = false

    // TODO move this state variable outside LatinIME
    private var mModCtrl = false
    private var mModAlt = false
    private var mModMeta = false
    private var mModFn = false

    // Saved shift state when leaving alphabet mode, or when applying multitouch shift
    private var mSavedShiftState = 0
    private var mPasswordText = false
    private var mVibrateOn = false
    private var mVibrateLen = 0
    private var mSoundOn = false
    private var mPopupOn = false
    private var mAutoCapPref = false
    private var mAutoCapActive = false
    private var mDeadKeysActive = false
    private var mQuickFixes = false
    private var mShowSuggestions = false
    private var mIsShowingHint = false
    private var mConnectbotTabHack = false
    private var mFullscreenOverride = false
    private var mForceKeyboardOn = false
    private var mKeyboardNotification = false
    private var mSuggestionsInLandscape = false
    private var mSuggestionForceOn = false
    private var mSuggestionForceOff = false
    private var mSwipeUpAction: String? = null
    private var mSwipeDownAction: String? = null
    private var mSwipeLeftAction: String? = null
    private var mSwipeRightAction: String? = null
    private var mVolUpAction: String? = null
    private var mVolDownAction: String? = null
    private var mHeightPortrait = 0
    private var mHeightLandscape = 0
    private var mNumKeyboardModes = 3
    private var mKeyboardModeOverridePortrait = 0
    private var mKeyboardModeOverrideLandscape = 0
    private var mCorrectionMode = 0
    private var mEnableVoice = true
    private var mVoiceOnPrimary = false
    private var mOrientation = 0
    private var mSuggestPuncList: ArrayList<CharSequence>? = null

    // Keep track of the last selection range to decide if we need to show word
    // alternatives
    private var mLastSelectionStart = 0
    private var mLastSelectionEnd = 0

    // Input type is such that we should not auto-correct
    private var mInputTypeNoAutoCorrect = false

    // Indicates whether the suggestion strip is to be on in landscape
    private var mJustAccepted = false
    private var mJustRevertedSeparator: CharSequence? = null
    private var mDeleteCount = 0
    private var mLastKeyTime: Long = 0

    // Modifier keys state
    private val mShiftKeyState = ModifierKeyState()
    private val mSymbolKeyState = ModifierKeyState()
    private val mCtrlKeyState = ModifierKeyState()
    private val mAltKeyState = ModifierKeyState()
    private val mMetaKeyState = ModifierKeyState()
    private val mFnKeyState = ModifierKeyState()

    // Compose sequence handling
    private var mComposeMode = false
    private val mComposeBuffer = ComposeSequence(this)
    private val mDeadAccentBuffer: ComposeSequence = DeadAccentSequence(this)
    private var mAudioManager: AudioManager? = null

    private var mSilentMode = false

    internal var mWordSeparators: String? = null
    /* package */

    private var mSentenceSeparators: String? = null
    private var mConfigurationChanging = false

    // Keeps track of most recently inserted text (multi-character key) for
    // reverting
    private var mEnteredText: CharSequence? = null
    private var mRefreshKeyboardRequired = false

    // For each word, a list of potential replacements, usually from voice.
    private val mWordToSuggestions = HashMap<String, List<CharSequence>>()
    private val mWordHistory = ArrayList<WordAlternatives>()
    private var mPluginManager: PluginManager? = null
    private var mNotificationReceiver: NotificationReceiver? = null
    private var mVoiceRecognitionTrigger: VoiceRecognitionTrigger? = null

    abstract class WordAlternatives {
        protected var mChosenWord: CharSequence? = null
        val chosenWord get() = mChosenWord

        constructor()
        constructor(chosenWord: CharSequence?) {
            mChosenWord = chosenWord
        }

        override fun hashCode(): Int {
            return mChosenWord.hashCode()
        }

        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is WordAlternatives) return false

            if (mChosenWord != other.mChosenWord) return false
            if (originalWord != other.originalWord) return false
            if (alternatives != other.alternatives) return false

            return true
        }

        abstract val originalWord: CharSequence?
        abstract val alternatives: List<CharSequence?>
    }

    inner class TypedWordAlternatives : WordAlternatives {
        var word: WordComposer? = null

        constructor()
        constructor(
            chosenWord: CharSequence?,
            wordComposer: WordComposer?
        ) : super(chosenWord) {
            word = wordComposer
        }

        override val originalWord: CharSequence?
            get() = word!!.typedWord
        override val alternatives: List<CharSequence?>
            get() = getTypedSuggestions(word!!)
    }

    /* package */
    var mHandler: Handler = object : Handler(Looper.getMainLooper()) {
        override fun handleMessage(msg: Message) {
            when (msg.what) {
                MSG_UPDATE_SUGGESTIONS -> updateSuggestions()
                MSG_UPDATE_OLD_SUGGESTIONS -> setOldSuggestions()
                MSG_UPDATE_SHIFT_STATE -> updateShiftKeyState(getCurrentInputEditorInfo())
            }
        }
    }

    override fun onCreate() {
        Log.i("PCKeyboard", "onCreate(), os.version=" + System.getProperty("os.version"))
        KeyboardSwitcher.init(this)
        super.onCreate()
        sInstance = this
        // setStatusIcon(R.drawable.ime_qwerty);
        mResources = resources
        val conf = mResources?.configuration
        mOrientation = conf!!.orientation
        val prefs = PreferenceManager.getDefaultSharedPreferences(this)
        mLanguageSwitcher = LanguageSwitcher(this)
        mLanguageSwitcher!!.loadLocales(prefs)
        mKeyboardSwitcher = instance
        mKeyboardSwitcher!!.setLanguageSwitcher(mLanguageSwitcher!!)
        mSystemLocale = conf.depLocale.toString()
        mLanguageSwitcher!!.systemLocale = conf.depLocale
        var inputLanguage = mLanguageSwitcher!!.inputLanguage
        if (inputLanguage == null) {
            inputLanguage = conf.depLocale.toString()
        }
        val res = resources
        mReCorrectionEnabled = prefs.getBoolean(
            PREF_RECORRECTION_ENABLED,
            res.getBoolean(R.bool.default_recorrection_enabled)
        )
        mConnectbotTabHack = prefs.getBoolean(
            PREF_CONNECTBOT_TAB_HACK,
            res.getBoolean(R.bool.default_connectbot_tab_hack)
        )
        mFullscreenOverride = prefs.getBoolean(
            PREF_FULLSCREEN_OVERRIDE,
            res.getBoolean(R.bool.default_fullscreen_override)
        )
        mForceKeyboardOn = prefs.getBoolean(
            PREF_FORCE_KEYBOARD_ON,
            res.getBoolean(R.bool.default_force_keyboard_on)
        )
        mKeyboardNotification = prefs.getBoolean(
            PREF_KEYBOARD_NOTIFICATION,
            res.getBoolean(R.bool.default_keyboard_notification)
        )
        mSuggestionsInLandscape = prefs.getBoolean(
            PREF_SUGGESTIONS_IN_LANDSCAPE,
            res.getBoolean(R.bool.default_suggestions_in_landscape)
        )
        mHeightPortrait =
            getHeight(prefs, PREF_HEIGHT_PORTRAIT, res.getString(R.string.default_height_portrait))
        mHeightLandscape = getHeight(
            prefs,
            PREF_HEIGHT_LANDSCAPE,
            res.getString(R.string.default_height_landscape)
        )
        sKeyboardSettings.hintMode =
            prefs.getString(PREF_HINT_MODE, res.getString(R.string.default_hint_mode))!!
                .toInt()
        sKeyboardSettings.longpressTimeout = getPrefInt(
            prefs,
            PREF_LONGPRESS_TIMEOUT,
            res.getString(R.string.default_long_press_duration)
        )
        sKeyboardSettings.renderMode =
            getPrefInt(prefs, PREF_RENDER_MODE, res.getString(R.string.default_render_mode))
        mSwipeUpAction = prefs.getString(PREF_SWIPE_UP, res.getString(R.string.default_swipe_up))
        mSwipeDownAction =
            prefs.getString(PREF_SWIPE_DOWN, res.getString(R.string.default_swipe_down))
        mSwipeLeftAction =
            prefs.getString(PREF_SWIPE_LEFT, res.getString(R.string.default_swipe_left))
        mSwipeRightAction =
            prefs.getString(PREF_SWIPE_RIGHT, res.getString(R.string.default_swipe_right))
        mVolUpAction = prefs.getString(PREF_VOL_UP, res.getString(R.string.default_vol_up))
        mVolDownAction = prefs.getString(PREF_VOL_DOWN, res.getString(R.string.default_vol_down))
        sKeyboardSettings.initPrefs(prefs, res)

        mVoiceRecognitionTrigger = VoiceRecognitionTrigger(this)

        updateKeyboardOptions()
        PluginManager.getPluginDictionaries(applicationContext)
        mPluginManager = PluginManager(this)
        val pFilter = IntentFilter()
        pFilter.addDataScheme("package")
        pFilter.addAction("android.intent.action.PACKAGE_ADDED")
        pFilter.addAction("android.intent.action.PACKAGE_REPLACED")
        pFilter.addAction("android.intent.action.PACKAGE_REMOVED")
        registerReceiver(mPluginManager, pFilter)

        LatinIMEUtil.GCUtils.instance.reset()
        var tryGC = true
        var i = 0
        while (i < LatinIMEUtil.GCUtils.GC_TRY_LOOP_MAX && tryGC) {
            tryGC = try {
                initSuggest(inputLanguage)
                false
            } catch (e: OutOfMemoryError) {
                LatinIMEUtil.GCUtils.instance.tryGCOrWait(
                    inputLanguage, e
                )
            }
            ++i
        }
        mOrientation = conf.orientation

        // register to receive ringer mode changes for silent mode
        val filter = IntentFilter(
            AudioManager.RINGER_MODE_CHANGED_ACTION
        )
        registerReceiver(mReceiver, filter)
        prefs.registerOnSharedPreferenceChangeListener(this)
        setNotification(mKeyboardNotification)
    }

    private fun getKeyboardModeNum(origMode: Int, override: Int): Int {
        var origMode = origMode
        if (mNumKeyboardModes == 2 && origMode == 2) origMode = 1 // skip "compact". FIXME!
        var num = (origMode + override) % mNumKeyboardModes
        if (mNumKeyboardModes == 2 && num == 1) num = 2 // skip "compact". FIXME!
        return num
    }

    private fun updateKeyboardOptions() {
        //Log.i(TAG, "setFullKeyboardOptions " + fullInPortrait + " " + heightPercentPortrait + " " + heightPercentLandscape);
        val isPortrait = isPortrait
        mNumKeyboardModes = if (sKeyboardSettings.compactModeEnabled) 3 else 2 // FIXME!
        val kbMode = if (isPortrait) {
            getKeyboardModeNum(
                sKeyboardSettings.keyboardModePortrait,
                mKeyboardModeOverridePortrait
            )
        } else {
            getKeyboardModeNum(
                sKeyboardSettings.keyboardModeLandscape,
                mKeyboardModeOverrideLandscape
            )
        }
        // Convert overall keyboard height to per-row percentage
        val screenHeightPercent = if (isPortrait) mHeightPortrait else mHeightLandscape
        sKeyboardSettings.keyboardMode = kbMode
        sKeyboardSettings.keyboardHeightPercent = screenHeightPercent.toFloat()
    }

    private fun createNotificationChannel() {
        // Create the NotificationChannel, but only on API 26+ because
        // the NotificationChannel class is new and not in the support library
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val name: CharSequence = getString(R.string.notification_channel_name)
            val description = getString(R.string.notification_channel_description)
            val importance = NotificationManager.IMPORTANCE_LOW
            val channel = NotificationChannel(NOTIFICATION_CHANNEL_ID, name, importance)
            channel.description = description
            // Register the channel with the system; you can't change the importance
            // or other notification behaviors after this
            val notificationManager = getSystemService(NotificationManager::class.java)
            notificationManager.createNotificationChannel(channel)
        }
    }

    private fun setNotification(visible: Boolean) {
        if ((ActivityCompat.checkSelfPermission(this,
                Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            && (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU)
        ) {
            val intentFilter = IntentFilter()
            intentFilter.addAction(NotificationPermissionReceiver.ACTION_NOTIFICATION_GRANTED)
            intentFilter.addAction(NotificationPermissionReceiver.ACTION_NOTIFICATION_DENIED)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(NotificationPermissionReceiver(), intentFilter, RECEIVER_EXPORTED)
            } else registerReceiver(NotificationPermissionReceiver(), intentFilter)
            val requestIntent = Intent(this, NotificationPermission::class.java)
            requestIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(requestIntent)


            // TODO: Consider calling
            //    ActivityCompat#requestPermissions
            // here to request the missing permissions, and then overriding
            //   public void onRequestPermissionsResult(int requestCode, String[] permissions,
            //                                          int[] grantResults)
            // to handle the case where the user grants the permission. See the documentation
            // for ActivityCompat#requestPermissions for more details.
            return
        }
        val ns = NOTIFICATION_SERVICE
        val mNotificationManager = getSystemService(ns) as NotificationManager

        if (visible && mNotificationReceiver == null) {
            createNotificationChannel()
            val icon = R.drawable.icon
            val text: CharSequence = "Keyboard notification enabled."
            val `when` = System.currentTimeMillis()

            // TODO: clean this up?
            mNotificationReceiver = NotificationReceiver(this)
            val pFilter = IntentFilter(NotificationReceiver.ACTION_SHOW)
            pFilter.addAction(NotificationReceiver.ACTION_SETTINGS)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                registerReceiver(mNotificationReceiver, pFilter, RECEIVER_EXPORTED)
            }
            else registerReceiver(mNotificationReceiver, pFilter)

            val notificationIntent = Intent(NotificationReceiver.ACTION_SHOW)
            notificationIntent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            val contentIntent = PendingIntent.getBroadcast(
                applicationContext, 1,
                notificationIntent, PendingIntent.FLAG_IMMUTABLE)
            //PendingIntent contentIntent = PendingIntent.getActivity(
                // getApplicationContext(), 1,
                // notificationIntent, PendingIntent.FLAG_IMMUTABLE)

            val configIntent = Intent(NotificationReceiver.ACTION_SETTINGS)
            val configPendingIntent = PendingIntent.getBroadcast(
                applicationContext, 2,
                configIntent, PendingIntent.FLAG_IMMUTABLE
            )
            val title = "Show Hacker's Keyboard"
            val body = "Select this to open the keyboard. Disable in settings."
            val mBuilder = NotificationCompat.Builder(this, NOTIFICATION_CHANNEL_ID)
                .setSmallIcon(R.drawable.icon_hk_notification)
                .setColor(0xff220044.toInt())
                .setAutoCancel(false) //Make this notification automatically dismissed when the user touches it -> false.
                .setTicker(text)
                .setContentTitle(title)
                .setContentText(body)
                .setContentIntent(contentIntent)
                .setOngoing(true)
                .addAction(
                    R.drawable.icon_hk_notification,
                    getString(R.string.notification_action_settings),
                    configPendingIntent
                )
                .setPriority(NotificationCompat.PRIORITY_DEFAULT)

            /*
            Notification notification = new Notification.Builder(getApplicationContext())
                    .setAutoCancel(false) //Make this notification automatically dismissed when the user touches it -> false.
                    .setTicker(text)
                    .setContentTitle(title)
                    .setContentText(body)
                    .setWhen(when)
                    .setSmallIcon(icon)
                    .setContentIntent(contentIntent)
                    .getNotification();
            notification.flags |= Notification.FLAG_ONGOING_EVENT | Notification.FLAG_NO_CLEAR;
            mNotificationManager.notify(ID, notification);
            */
            val notificationManager = NotificationManagerCompat.from(this)

            // notificationId is a unique int for each notification that you must define
            notificationManager.notify(NOTIFICATION_ONGOING_ID, mBuilder.build())
        } else if (mNotificationReceiver != null) {
            mNotificationManager.cancel(NOTIFICATION_ONGOING_ID)
            unregisterReceiver(mNotificationReceiver)
            mNotificationReceiver = null
        }
    }

    private val isPortrait get() = mOrientation == Configuration.ORIENTATION_PORTRAIT

    private fun suggestionsDisabled(): Boolean {
        if (mSuggestionForceOff) return true
        if (mSuggestionForceOn) return false
        return !(mSuggestionsInLandscape || isPortrait)
    }

    private fun initSuggest(locale: String?) {
        mInputLocale = locale
        val orig = resources
        val conf = orig.configuration
        val saveLocale = conf.depLocale
        conf.depLocale = Locale(locale!!)
        orig.updateConfiguration(conf, orig.displayMetrics)
        mSuggest?.close()
        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        mQuickFixes = sp.getBoolean(PREF_QUICK_FIXES,
            resources.getBoolean(R.bool.default_quick_fixes))

        val dictionaries = getDictionary(orig)
        mSuggest = Suggest(this, dictionaries)
        updateAutoTextEnabled(saveLocale!!)
        if (mUserDictionary != null) mUserDictionary!!.close()
        mUserDictionary = UserDictionary(this, mInputLocale!!)
        //if (mContactsDictionary == null) {
        //    mContactsDictionary = new ContactsDictionary(this,
        //            Suggest.DIC_CONTACTS);
        //}
        if (mAutoDictionary != null) {
            mAutoDictionary!!.close()
        }
        mAutoDictionary = AutoDictionary(
            this, this, mInputLocale, Suggest.DIC_AUTO)
        if (mUserBigramDictionary != null) {
            mUserBigramDictionary!!.close()
        }
        mUserBigramDictionary = UserBigramDictionary(
            this, this, mInputLocale, Suggest.DIC_USER)
        mSuggest!!.setUserBigramDictionary(mUserBigramDictionary)
        mSuggest!!.setUserDictionary(mUserDictionary)
        //mSuggest.setContactsDictionary(mContactsDictionary);
        mSuggest!!.setAutoDictionary(mAutoDictionary)
        updateCorrectionMode()
        mWordSeparators = mResources!!.getString(R.string.word_separators)
        mSentenceSeparators = mResources!!.getString(R.string.sentence_separators)
        initSuggestPuncList()

        conf.depLocale = saveLocale
        orig.updateConfiguration(conf, orig.displayMetrics)
    }

    override fun onDestroy() {
        if (mUserDictionary != null) {
            mUserDictionary!!.close()
        }
        //if (mContactsDictionary != null) {
        //    mContactsDictionary.close();
        //}
        unregisterReceiver(mReceiver)
        unregisterReceiver(mPluginManager)
        if (mNotificationReceiver != null) {
            unregisterReceiver(mNotificationReceiver)
            mNotificationReceiver = null
        }
        super.onDestroy()
    }

    override fun onConfigurationChanged(conf: Configuration) {
        Log.i("PCKeyboard", "onConfigurationChanged()")
        // If the system locale changes and is different from the saved
        // locale (mSystemLocale), then reload the input locale list from the
        // latin ime settings (shared prefs) and reset the input locale
        // to the first one.
        val systemLocale = conf.depLocale.toString()
        if (!TextUtils.equals(systemLocale, mSystemLocale)) {
            mSystemLocale = systemLocale
            if (mLanguageSwitcher != null) {
                mLanguageSwitcher!!.loadLocales(
                    PreferenceManager.getDefaultSharedPreferences(this))
                mLanguageSwitcher!!.systemLocale = conf.depLocale
                toggleLanguage(true, true)
            } else reloadKeyboards()
        }
        // If orientation changed while predicting, commit the change
        if (conf.orientation != mOrientation) {
            val ic = getCurrentInputConnection()
            commitTyped(ic, true)
            ic?.finishComposingText() // For voice input
            mOrientation = conf.orientation
            reloadKeyboards()
            removeCandidateViewContainer()
        }
        mConfigurationChanging = true
        super.onConfigurationChanged(conf)
        mConfigurationChanging = false
    }

    override fun onCreateInputView(): View {
        setCandidatesViewShown(false) // Workaround for "already has a parent" when reconfiguring
        mKeyboardSwitcher!!.recreateInputView()
        mKeyboardSwitcher!!.makeKeyboards(true)
        mKeyboardSwitcher!!.setKeyboardMode(
            KeyboardSwitcher.MODE_TEXT, 0,
            shouldShowVoiceButton(getCurrentInputEditorInfo())
        )
        return mKeyboardSwitcher!!.inputView!!
    }

    @Deprecated("Deprecated in Java", ReplaceWith("MyInputMethodImpl()"))
    override fun onCreateInputMethodInterface(): AbstractInputMethodImpl {
        return MyInputMethodImpl()
    }

    @JvmField
    var mToken: IBinder? = null

    inner class MyInputMethodImpl : InputMethodImpl() {
        override fun attachToken(token: IBinder) {
            super.attachToken(token)
            Log.i(TAG, "attachToken $token")
            if (mToken == null) {
                mToken = token
            }
        }
    }

    override fun onCreateCandidatesView(): View? {
        //Log.i(TAG, "onCreateCandidatesView(), mCandidateViewContainer=" + mCandidateViewContainer);
        //mKeyboardSwitcher.makeKeyboards(true);
        if (mCandidateViewContainer == null) {
            mCandidateViewContainer =
                layoutInflater.inflate(R.layout.candidates, null) as LinearLayout
            mCandidateView = mCandidateViewContainer!!.findViewById(R.id.candidates)
            mCandidateView!!.setPadding(0, 0, 0, 0)
            mCandidateView!!.setService(this)
            setCandidatesView(mCandidateViewContainer)
        }
        isExtractViewShown = onEvaluateFullscreenMode()
        return mCandidateViewContainer
    }

    private fun removeCandidateViewContainer() {
        //Log.i(TAG, "removeCandidateViewContainer(), mCandidateViewContainer=" + mCandidateViewContainer);
        if (mCandidateViewContainer != null) {
            mCandidateViewContainer!!.removeAllViews()
            val parent = mCandidateViewContainer!!.parent
            if (parent is ViewGroup) {
                parent.removeView(mCandidateViewContainer)
            }
            mCandidateViewContainer = null
            mCandidateView = null
        }
        resetPrediction()
    }

    private fun resetPrediction() {
        mComposing.setLength(0)
        mPredicting = false
        mDeleteCount = 0
        mJustAddedAutoSpace = false
    }

    override fun onStartInput(attribute: EditorInfo, restarting: Boolean) {
        super.onStartInput(attribute, restarting)
        Log.i(TAG, "onStartInput")
        setCandidatesViewShownInternal(true, false, false)
    }

    override fun onStartInputView(attribute: EditorInfo, restarting: Boolean) {
        sKeyboardSettings.editorPackageName = attribute.packageName
        sKeyboardSettings.editorFieldName = attribute.fieldName
        sKeyboardSettings.editorFieldId = attribute.fieldId
        sKeyboardSettings.editorInputType = attribute.inputType

        //Log.i("PCKeyboard", "onStartInputView " + attribute + ", inputType= "
        // + Integer.toHexString(attribute.inputType) + ", restarting=" + restarting);
        // In landscape mode, this method gets called without the input view
        // being created.
        val inputView = mKeyboardSwitcher!!.inputView ?: return

        if (mRefreshKeyboardRequired) {
            mRefreshKeyboardRequired = false
            toggleLanguage(true, true)
        }

        mKeyboardSwitcher!!.makeKeyboards(false)

        TextEntryState.newSession(this)

        // Most such things we decide below in the switch statement, but we need to know
        // now whether this is a password text field, because we need to know now (before
        // the switch statement) whether we want to enable the voice button.
        mPasswordText = false
        val variation = attribute.inputType and EditorInfo.TYPE_MASK_VARIATION
        if (variation == EditorInfo.TYPE_TEXT_VARIATION_PASSWORD ||
            variation == EditorInfo.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD ||
            variation == 0xe0 /* EditorInfo.TYPE_TEXT_VARIATION_WEB_PASSWORD */
            ) {
            if (attribute.inputType and EditorInfo.TYPE_MASK_CLASS == EditorInfo.TYPE_CLASS_TEXT) {
                mPasswordText = true
            }
        }
        mEnableVoiceButton = shouldShowVoiceButton(attribute)
        val enableVoiceButton = mEnableVoiceButton && mEnableVoice

        if (mVoiceRecognitionTrigger != null) {
            mVoiceRecognitionTrigger!!.onStartInputView()
        }

        mInputTypeNoAutoCorrect = false
        mPredictionOnForMode = false
        mCompletionOn = false
        mCompletions = null
        mModCtrl = false
        mModAlt = false
        mModMeta = false
        mModFn = false
        mEnteredText = null
        mSuggestionForceOn = false
        mSuggestionForceOff = false
        mKeyboardModeOverridePortrait = 0
        mKeyboardModeOverrideLandscape = 0
        sKeyboardSettings.useExtension = false

        when (attribute.inputType and EditorInfo.TYPE_MASK_CLASS) {
            EditorInfo.TYPE_CLASS_NUMBER, // NOTE: For now, we use the phone keyboard for NUMBER and DATETIME
            EditorInfo.TYPE_CLASS_DATETIME, // until we get a dedicated number entry keypad.
                // TODO: Use a dedicated number entry keypad here when we get one.
            EditorInfo.TYPE_CLASS_PHONE ->
                mKeyboardSwitcher!!.setKeyboardMode(
                    KeyboardSwitcher.MODE_PHONE, attribute.imeOptions, enableVoiceButton)

            EditorInfo.TYPE_CLASS_TEXT -> {
                mKeyboardSwitcher!!.setKeyboardMode(
                    KeyboardSwitcher.MODE_TEXT, attribute.imeOptions, enableVoiceButton)
                // startPrediction();
                // Make sure that passwords are not displayed in candidate view
                mPredictionOnForMode = !mPasswordText
                mAutoSpace =
                    variation != EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS &&
                    variation != EditorInfo.TYPE_TEXT_VARIATION_PERSON_NAME &&
                    mLanguageSwitcher!!.allowAutoSpace()
                if (variation == EditorInfo.TYPE_TEXT_VARIATION_EMAIL_ADDRESS) {
                    mPredictionOnForMode = false
                    mKeyboardSwitcher!!.setKeyboardMode(
                        KeyboardSwitcher.MODE_EMAIL,
                        attribute.imeOptions, enableVoiceButton
                    )
                } else if (variation == EditorInfo.TYPE_TEXT_VARIATION_URI) {
                    mPredictionOnForMode = false
                    mKeyboardSwitcher!!.setKeyboardMode(
                        KeyboardSwitcher.MODE_URL,
                        attribute.imeOptions, enableVoiceButton
                    )
                } else if (variation == EditorInfo.TYPE_TEXT_VARIATION_SHORT_MESSAGE) {
                    mKeyboardSwitcher!!.setKeyboardMode(
                        KeyboardSwitcher.MODE_IM,
                        attribute.imeOptions, enableVoiceButton
                    )
                } else if (variation == EditorInfo.TYPE_TEXT_VARIATION_FILTER) {
                    mPredictionOnForMode = false
                } else if (variation == EditorInfo.TYPE_TEXT_VARIATION_WEB_EDIT_TEXT) {
                    mKeyboardSwitcher!!.setKeyboardMode(
                        KeyboardSwitcher.MODE_WEB,
                        attribute.imeOptions, enableVoiceButton
                    )
                    // If it's a browser edit field and auto correct is not ON
                    // explicitly, then disable auto correction, but keep suggestions on.
                    if (attribute.inputType and EditorInfo.TYPE_TEXT_FLAG_AUTO_CORRECT == 0) {
                        mInputTypeNoAutoCorrect = true
                    }
                }

                // If NO_SUGGESTIONS is set, don't do prediction.
                if (attribute.inputType and EditorInfo.TYPE_TEXT_FLAG_NO_SUGGESTIONS != 0) {
                    mPredictionOnForMode = false
                    mInputTypeNoAutoCorrect = true
                }
                // If it's not multiline and the autoCorrect flag is not set, then
                // don't correct
                if (attribute.inputType and EditorInfo.TYPE_TEXT_FLAG_AUTO_CORRECT == 0
                    && attribute.inputType and EditorInfo.TYPE_TEXT_FLAG_MULTI_LINE == 0
                ) {
                    mInputTypeNoAutoCorrect = true
                }
                if (attribute.inputType and EditorInfo.TYPE_TEXT_FLAG_AUTO_COMPLETE != 0) {
                    mPredictionOnForMode = false
                    mCompletionOn = isFullscreenMode
                }
            }

            else -> mKeyboardSwitcher!!.setKeyboardMode(
                KeyboardSwitcher.MODE_TEXT,
                attribute.imeOptions, enableVoiceButton
            )
        }
        inputView.closing()
        resetPrediction()
        loadSettings()
        updateShiftKeyState(attribute)

        mPredictionOnPref = mCorrectionMode > 0 || mShowSuggestions
        setCandidatesViewShownInternal(
            isCandidateStripVisible || mCompletionOn, false)
        updateSuggestions()

        // If the dictionary is not big enough, don't auto correct
        mHasDictionary = mSuggest!!.hasMainDictionary()

        updateCorrectionMode()

        inputView.isPreviewEnabled = mPopupOn
        inputView.isProximityCorrectionEnabled = true
        // If we just entered a text field, maybe it has some old text that
        // requires correction
        checkReCorrectionOnStart()
    }

    private fun shouldShowVoiceButton(attribute: EditorInfo): Boolean {
        // TODO Auto-generated method stub
        return true
    }

    private fun checkReCorrectionOnStart() {
        if (mReCorrectionEnabled && isPredictionOn) {
            // First get the cursor position. This is required by
            // setOldSuggestions(), so that
            // it can pass the correct range to setComposingRegion(). At this
            // point, we don't
            // have valid values for mLastSelectionStart/Stop because
            // onUpdateSelection() has
            // not been called yet.
            val ic = getCurrentInputConnection() ?: return
            val etr = ExtractedTextRequest()
            etr.token = 0 // anything is fine here
            val et = ic.getExtractedText(etr, 0) ?: return

            mLastSelectionStart = et.startOffset + et.selectionStart
            mLastSelectionEnd = et.startOffset + et.selectionEnd

            // Then look for possible corrections in a delayed fashion
            if (!TextUtils.isEmpty(et.text) && isCursorTouchingWord) {
                postUpdateOldSuggestions()
            }
        }
    }

    override fun onFinishInput() {
        super.onFinishInput()

        onAutoCompletionStateChanged(false)

        if (mKeyboardSwitcher!!.inputView != null) {
            mKeyboardSwitcher!!.inputView!!.closing()
        }
        if (mAutoDictionary != null) mAutoDictionary!!.flushPendingWrites()
        if (mUserBigramDictionary != null) mUserBigramDictionary!!.flushPendingWrites()
    }

    override fun onFinishInputView(finishingInput: Boolean) {
        super.onFinishInputView(finishingInput)
        // Remove penging messages related to update suggestions
        mHandler.removeMessages(MSG_UPDATE_SUGGESTIONS)
        mHandler.removeMessages(MSG_UPDATE_OLD_SUGGESTIONS)
    }

    override fun onUpdateExtractedText(token: Int, text: ExtractedText) {
        super.onUpdateExtractedText(token, text)
        val ic = getCurrentInputConnection()
    }

    override fun onUpdateSelection(
        oldSelStart: Int, oldSelEnd: Int,
        newSelStart: Int, newSelEnd: Int, candidatesStart: Int,
        candidatesEnd: Int
    ) {
        super.onUpdateSelection(
            oldSelStart, oldSelEnd, newSelStart, newSelEnd,
            candidatesStart, candidatesEnd
        )

        // If the current selection in the text view changes, we should
        // clear whatever candidate text we have.
        if (mComposing.isNotEmpty() && mPredicting
            && (newSelStart != candidatesEnd || newSelEnd != candidatesEnd)
            && mLastSelectionStart != newSelStart
        ) {
            mComposing.setLength(0)
            mPredicting = false
            postUpdateSuggestions()
            TextEntryState.reset()
            val ic = getCurrentInputConnection()
            ic?.finishComposingText()
        } else if (!mPredicting && !mJustAccepted) {
            when (TextEntryState.state) {
                TextEntryState.State.ACCEPTED_DEFAULT -> {
                    TextEntryState.reset()
                    mJustAddedAutoSpace = false // The user moved the cursor.
                }

                TextEntryState.State.SPACE_AFTER_PICKED -> mJustAddedAutoSpace = false // The user moved the cursor.
                else -> { }
            }
        }
        mJustAccepted = false
        postUpdateShiftKeyState()

        // Make a note of the cursor position
        mLastSelectionStart = newSelStart
        mLastSelectionEnd = newSelEnd

        if (mReCorrectionEnabled) {
            // Don't look for corrections if the keyboard is not visible
            if (isKeyboardVisible) {
                // Check if we should go in or out of correction mode.
                if (
                    isPredictionOn
                    && mJustRevertedSeparator == null
                    && (
                            candidatesStart == candidatesEnd
                            || newSelStart != oldSelStart
                            || TextEntryState.isCorrecting
                            )
                    && (newSelStart < newSelEnd - 1 || !mPredicting)
                ) {
                    if (isCursorTouchingWord || mLastSelectionStart < mLastSelectionEnd
                    ) {
                        postUpdateOldSuggestions()
                    } else {
                        abortCorrection(false)
                        // Show the punctuation suggestions list if the current
                        // one is not
                        // and if not showing "Touch again to save".
                        if (mCandidateView != null
                            && mSuggestPuncList != mCandidateView!!.suggestions
                            && !mCandidateView!!.isShowingAddToDictionaryHint()
                        ) {
                            setNextSuggestions()
                        }
                    }
                }
            }
        }
    }

    /**
     * This is called when the user has clicked on the extracted text view, when
     * running in fullscreen mode. The default implementation hides the
     * candidates view when this happens, but only if the extracted text editor
     * has a vertical scroll bar because its text doesn't fit. Here we override
     * the behavior due to the possibility that a re-correction could cause the
     * candidate strip to disappear and re-appear.
     */
    override fun onExtractedTextClicked() {
        if (mReCorrectionEnabled && isPredictionOn) return
        super.onExtractedTextClicked()
    }

    /**
     * This is called when the user has performed a cursor movement in the
     * extracted text view, when it is running in fullscreen mode. The default
     * implementation hides the candidates view when a vertical movement
     * happens, but only if the extracted text editor has a vertical scroll bar
     * because its text doesn't fit. Here we override the behavior due to the
     * possibility that a re-correction could cause the candidate strip to
     * disappear and re-appear.
     */
    override fun onExtractedCursorMovement(dx: Int, dy: Int) {
        if (mReCorrectionEnabled && isPredictionOn) return
        super.onExtractedCursorMovement(dx, dy)
    }

    override fun hideWindow() {
        onAutoCompletionStateChanged(false)

        if (mOptionsDialog != null && mOptionsDialog!!.isShowing) {
            mOptionsDialog!!.dismiss()
            mOptionsDialog = null
        }
        mWordToSuggestions.clear()
        mWordHistory.clear()
        super.hideWindow()
        TextEntryState.endSession()
    }

    override fun onDisplayCompletions(completions: Array<CompletionInfo>) {
        if (mCompletionOn) {
            mCompletions = completions

            val stringList = ArrayList<CharSequence>()
            for (ci in completions) {
                stringList.add(ci.text)
            }
            // When in fullscreen mode, show completions generated by the
            // application
            setSuggestions(stringList, true, true, true)
            mBestWord = null
            setCandidatesViewShown(true)
        }
    }

    private fun setCandidatesViewShownInternal(
        shown: Boolean,
        needsInputViewShown: Boolean,
        predictionNeeded: Boolean = true
    ) {
        Log.i(TAG, "setCandidatesViewShownInternal(${shown}, ${needsInputViewShown})\n" +
                " inputViewIsNull=${mKeyboardSwitcher?.inputView == null}\n" +
                " isKeyboardVisible=$isKeyboardVisible\n" +
                " mCompletionOn=$mCompletionOn\n" +
                " mPredictionOnForMode=$mPredictionOnForMode\n" +
                " isPredictionWanted=$isPredictionWanted\n" +
                " mPredictionOnPref=$mPredictionOnPref\n"+
                " mPredicting=$mPredicting\n" +
                " mShowSuggestions=$mShowSuggestions"
                )
        // TODO: Remove this if we support candidates with hard keyboard
        val visible = shown
                && onEvaluateInputViewShown()
                && (!predictionNeeded || isPredictionOn)
                && (!needsInputViewShown || isKeyboardVisible)
        if (visible) {
            if (mCandidateViewContainer == null) {
                onCreateCandidatesView()
                setNextSuggestions()
            }
        } else {
            if (mCandidateViewContainer != null) {
                removeCandidateViewContainer()
                commitTyped(getCurrentInputConnection(), true)
            }
        }
        super.setCandidatesViewShown(visible)
    }

    override fun onFinishCandidatesView(finishingInput: Boolean) {
        //Log.i(TAG, "onFinishCandidatesView(), mCandidateViewContainer=" + mCandidateViewContainer);
        super.onFinishCandidatesView(finishingInput)
        if (mCandidateViewContainer != null) {
            removeCandidateViewContainer()
        }
    }

    override fun onEvaluateInputViewShown(): Boolean {
        val parent = super.onEvaluateInputViewShown()
        val wanted = mForceKeyboardOn || parent
        //Log.i(TAG, "OnEvaluateInputViewShown, parent=" + parent + " + " wanted=" + wanted);
        return wanted
    }

    override fun setCandidatesViewShown(shown: Boolean) {
        setCandidatesViewShownInternal(shown, needsInputViewShown = true )
    }

    override fun onComputeInsets(outInsets: Insets) {
        super.onComputeInsets(outInsets)
        if (!isFullscreenMode) {
            outInsets.contentTopInsets = outInsets.visibleTopInsets
        }
    }

    override fun onEvaluateFullscreenMode(): Boolean {
        val dm = resources.displayMetrics
        val displayHeight = dm.heightPixels.toFloat()
        // If the display is more than X inches high, don't go to fullscreen
        // mode
        val dimen = resources.getDimension(R.dimen.max_height_for_fullscreen)
        return if (displayHeight > dimen || mFullscreenOverride || isConnectbot) {
            false
        } else {
            super.onEvaluateFullscreenMode()
        }
    }

    val isKeyboardVisible get() = mKeyboardSwitcher?.inputView?.isShown == true

    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        when (keyCode) {
            KeyEvent.KEYCODE_BACK ->
                if (event.repeatCount == 0 && mKeyboardSwitcher!!.inputView != null) {
                    if (mKeyboardSwitcher!!.inputView!!.handleBack()) return true
                }

            KeyEvent.KEYCODE_VOLUME_UP ->
                if (mVolUpAction != "none" && isKeyboardVisible) return true

            KeyEvent.KEYCODE_VOLUME_DOWN ->
                if (mVolDownAction != "none" && isKeyboardVisible) return true
        }
        return super.onKeyDown(keyCode, event)
    }

    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        var event = event
        when (keyCode) {
            KeyEvent.KEYCODE_DPAD_DOWN,
            KeyEvent.KEYCODE_DPAD_UP,
            KeyEvent.KEYCODE_DPAD_LEFT,
            KeyEvent.KEYCODE_DPAD_RIGHT -> {
                val inputView = mKeyboardSwitcher!!.inputView
                // Enable shift key and DPAD to do selections
                if (inputView?.isShown() == true
                    && inputView.shiftState == Keyboard.SHIFT_ON) {
                    event = KeyEvent(
                        event.downTime, event.eventTime,
                        event.action, event.keyCode,
                        event.repeatCount, event.deviceId, event.scanCode,
                        KeyEvent.META_SHIFT_LEFT_ON or KeyEvent.META_SHIFT_ON
                    )
                    val ic = getCurrentInputConnection()
                    ic?.sendKeyEvent(event)
                    return true
                }
            }

            KeyEvent.KEYCODE_VOLUME_UP ->
                if (mVolUpAction != "none" && isKeyboardVisible) {
                return doSwipeAction(mVolUpAction)
            }

            KeyEvent.KEYCODE_VOLUME_DOWN ->
                if (mVolDownAction != "none" && isKeyboardVisible) {
                return doSwipeAction(mVolDownAction)
            }
        }
        return super.onKeyUp(keyCode, event)
    }

    private fun reloadKeyboards() {
        mKeyboardSwitcher!!.setLanguageSwitcher(mLanguageSwitcher!!)
        if (mKeyboardSwitcher!!.inputView != null
            && mKeyboardSwitcher!!.keyboardMode != KeyboardSwitcher.MODE_NONE
        ) {
            mKeyboardSwitcher!!.setVoiceMode(
                mEnableVoice && mEnableVoiceButton,
                mVoiceOnPrimary
            )
        }
        updateKeyboardOptions()
        mKeyboardSwitcher!!.makeKeyboards(true)
    }

    private fun commitTyped(inputConnection: InputConnection?, manual: Boolean) {
        if (mPredicting) {
            mPredicting = false
            if (mComposing.isNotEmpty()) {
                inputConnection?.commitText(mComposing, 1)
                mCommittedLength = mComposing.length
                if (manual) {
                    TextEntryState.manualTyped(mComposing)
                } else {
                    TextEntryState.acceptedTyped(mComposing)
                }
                addToDictionaries(
                    mComposing, AutoDictionary.FREQUENCY_FOR_TYPED
                )
            }
            updateSuggestions()
        }
    }

    private fun postUpdateShiftKeyState() {
        // TODO(klausw): disabling, I have no idea what this is supposed to accomplish.
//        //updateShiftKeyState(getCurrentInputEditorInfo());
//
//        // FIXME: why the delay?
//        mHandler.removeMessages(MSG_UPDATE_SHIFT_STATE);
//        // TODO: Should remove this 300ms delay?
//        mHandler.sendMessageDelayed(mHandler
//                .obtainMessage(MSG_UPDATE_SHIFT_STATE), 300);
    }

    override fun updateShiftKeyState(attr: EditorInfo) {
        val ic = getCurrentInputConnection()
        if (ic != null && mKeyboardSwitcher!!.isAlphabetMode) {
            val oldState = shiftState
            val isShifted = mShiftKeyState.isChording
            val isCapsLock = oldState == Keyboard.SHIFT_CAPS_LOCKED || oldState == Keyboard.SHIFT_LOCKED
            val isCaps = isCapsLock || getCursorCapsMode(ic, attr) != 0
            //Log.i(TAG, "updateShiftKeyState isShifted=" + isShifted + " isCaps=" + isCaps + " isMomentary=" + mShiftKeyState.isMomentary() + " cursorCaps=" + getCursorCapsMode(ic, attr));
            val newState = shiftKeyState(isShifted, isCaps, isCapsLock)
            //Log.i(TAG, "updateShiftKeyState " + oldState + " -> " + newState);
            mKeyboardSwitcher!!.setShiftState(newState)
        }
        if (ic != null) {
            // Clear modifiers other than shift, to avoid them getting stuck
            val states = (KeyEvent.META_FUNCTION_ON
                    or KeyEvent.META_ALT_MASK
                    or KeyEvent.META_CTRL_MASK
                    or KeyEvent.META_META_MASK
                    or KeyEvent.META_SYM_ON)
            ic.clearMetaKeyStates(states)
        }
    }

    private fun shiftKeyState(isShifted: Boolean, isCaps: Boolean, isCapsLock: Boolean): Int {
        var newState = Keyboard.SHIFT_OFF
        if (isShifted) {
            newState =
                if (mSavedShiftState == Keyboard.SHIFT_LOCKED) Keyboard.SHIFT_CAPS
                else Keyboard.SHIFT_ON
        } else if (isCaps) {
            newState =
                if (isCapsLock) capsOrShiftLockState
                else Keyboard.SHIFT_CAPS
        }
        return newState
    }

    private val shiftState: Int
        get() {
            return  mKeyboardSwitcher?.inputView?.shiftState
                ?: Keyboard.SHIFT_OFF
        }

    private val isShiftCapsMode: Boolean
        get() {
            return mKeyboardSwitcher?.inputView?.isShiftCaps
                ?: false
        }

    private fun getCursorCapsMode(ic: InputConnection, attr: EditorInfo): Int {
        var caps = 0
        val ei = getCurrentInputEditorInfo()
        if (mAutoCapActive && ei.inputType != EditorInfo.TYPE_NULL) {
            caps = ic.getCursorCapsMode(attr.inputType)
        }
        return caps
    }

    private fun swapPunctuationAndSpace() {
        val ic = getCurrentInputConnection() ?: return
        val lastTwo = ic.getTextBeforeCursor(2, 0)
        if (lastTwo != null && lastTwo.length == 2
            && lastTwo[0].code == ASCII_SPACE
            && isSentenceSeparator(lastTwo[1].code)
        ) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(2, 0)
            ic.commitText(lastTwo[1].toString() + " ", 1)
            ic.endBatchEdit()
            updateShiftKeyState(getCurrentInputEditorInfo())
            mJustAddedAutoSpace = true
        }
    }

    private fun reswapPeriodAndSpace() {
        val ic = getCurrentInputConnection() ?: return
        val lastThree = ic.getTextBeforeCursor(3, 0)
        if (lastThree != null && lastThree.length == 3
            && lastThree[0].code == ASCII_PERIOD
            && lastThree[1].code == ASCII_SPACE
            && lastThree[2].code == ASCII_PERIOD
            ) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(3, 0)
            ic.commitText(" ..", 1)
            ic.endBatchEdit()
            updateShiftKeyState(getCurrentInputEditorInfo())
        }
    }

    private fun doubleSpace() {
        // if (!mAutoPunctuate) return;
        if (mCorrectionMode == Suggest.CORRECTION_NONE) return
        val ic = getCurrentInputConnection() ?: return
        val lastThree = ic.getTextBeforeCursor(3, 0)
        if (lastThree != null && lastThree.length == 3
            && Character.isLetterOrDigit(lastThree[0])
            && lastThree[1].code == ASCII_SPACE
            && lastThree[2].code == ASCII_SPACE
            ) {
            ic.beginBatchEdit()
            ic.deleteSurroundingText(2, 0)
            ic.commitText(". ", 1)
            ic.endBatchEdit()
            updateShiftKeyState(getCurrentInputEditorInfo())
            mJustAddedAutoSpace = true
        }
    }

    private fun maybeRemovePreviousPeriod(text: CharSequence) {
        val ic = getCurrentInputConnection() ?: return
        if (text.isEmpty()) return

        // When the text's first character is '.', remove the previous period
        // if there is one.
        val lastOne = ic.getTextBeforeCursor(1, 0)
        if (lastOne != null && lastOne.length == 1
            && lastOne[0].code == ASCII_PERIOD
            && text[0].code == ASCII_PERIOD
            ) {
            ic.deleteSurroundingText(1, 0)
        }
    }

    private fun removeTrailingSpace() {
        val ic = getCurrentInputConnection() ?: return

        val lastOne = ic.getTextBeforeCursor(1, 0)
        if (lastOne != null && lastOne.length == 1
            && lastOne[0].code == ASCII_SPACE) {
            ic.deleteSurroundingText(1, 0)
        }
    }

    fun addWordToDictionary(word: String): Boolean {
        mUserDictionary!!.addWord(word, 128)
        // Suggestion strip should be updated after the operation of adding word
        // to the
        // user dictionary
        postUpdateSuggestions()
        return true
    }

    private fun isAlphabet(code: Int): Boolean {
        return Character.isLetter(code)
    }

    private fun showInputMethodPicker() {
        (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
            .showInputMethodPicker()
    }

    private fun onOptionKeyPressed() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            // Input method selector is available as a button in the soft key area, so just launch
            // HK settings directly. This also works around the alert dialog being clipped
            // in Android O.

            // TODO: Find a way to not use intent flag
            val intent = Intent()
            intent.setClass(this@LatinIME, LatinIMESettings::class.java)
            intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
            //startActivity(new Intent(this, LatinIMESettings.class));
        } else {
            // Show an options menu with choices to change input method or open HK settings.
            if (!isShowingOptionDialog) {
                showOptionsMenu()
            }
        }
    }

    private fun onOptionKeyLongPressed() {
        if (!isShowingOptionDialog) {
            showInputMethodPicker()
        }
    }

    private val isShowingOptionDialog: Boolean
        get() = mOptionsDialog?.isShowing == true

    private val isConnectbot: Boolean
        get() {
            val ei = getCurrentInputEditorInfo()
            val pkg = ei.packageName ?: return false
            return (pkg.equals("org.connectbot", ignoreCase = true)
                    || pkg.equals("org.woltage.irssiconnectbot", ignoreCase = true)
                    || pkg.equals("com.pslib.connectbot", ignoreCase = true)
                    || pkg.equals("sk.vx.connectbot", ignoreCase = true)
                    ) && ei.inputType == 0 // FIXME
        }

    private fun getMetaState(shifted: Boolean): Int {
        var meta = 0
        if (shifted) meta = meta or (KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON)
        if (mModCtrl) meta = meta or (KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON)
        if (mModAlt) meta = meta or (KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON)
        if (mModMeta) meta = meta or (KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON)
        return meta
    }

    private fun sendKeyDown(ic: InputConnection?, key: Int, meta: Int) {
        val now = System.currentTimeMillis()
        ic?.sendKeyEvent(
            KeyEvent(now, now, KeyEvent.ACTION_DOWN, key, 0, meta))
    }

    private fun sendKeyUp(ic: InputConnection?, key: Int, meta: Int) {
        val now = System.currentTimeMillis()
        ic?.sendKeyEvent(
            KeyEvent(now, now, KeyEvent.ACTION_UP, key, 0, meta))
    }

    private fun sendModifiedKeyDownUp(key: Int, shifted: Boolean = isShiftMod) {
        val ic = getCurrentInputConnection()
        val meta = getMetaState(shifted)
        sendModifierKeysDown(shifted)
        sendKeyDown(ic, key, meta)
        sendKeyUp(ic, key, meta)
        sendModifierKeysUp(shifted)
    }

    private val isShiftMod: Boolean
        get() {
            if (mShiftKeyState.isChording) return true
            return mKeyboardSwitcher?.inputView?.isShiftAll
                ?: false
        }

    private fun delayChordingCtrlModifier(): Boolean {
        return sKeyboardSettings.chordingCtrlKey == 0
    }

    private fun delayChordingAltModifier(): Boolean {
        return sKeyboardSettings.chordingAltKey == 0
    }

    private fun delayChordingMetaModifier(): Boolean {
        return sKeyboardSettings.chordingMetaKey == 0
    }

    private fun sendShiftKey(ic: InputConnection, isDown: Boolean) {
        val key = KeyEvent.KEYCODE_SHIFT_LEFT
        val meta = KeyEvent.META_SHIFT_ON or KeyEvent.META_SHIFT_LEFT_ON
        if (isDown) {
            sendKeyDown(ic, key, meta)
        } else {
            sendKeyUp(ic, key, meta)
        }
    }

    private fun sendCtrlKey(ic: InputConnection, isDown: Boolean, chording: Boolean) {
        if (chording && delayChordingCtrlModifier()) return

        var key = sKeyboardSettings.chordingCtrlKey
        if (key == 0) key = KeyEvent.KEYCODE_CTRL_LEFT
        val meta = KeyEvent.META_CTRL_ON or KeyEvent.META_CTRL_LEFT_ON
        if (isDown) {
            sendKeyDown(ic, key, meta)
        } else {
            sendKeyUp(ic, key, meta)
        }
    }

    private fun sendAltKey(ic: InputConnection, isDown: Boolean, chording: Boolean) {
        if (chording && delayChordingAltModifier()) return

        var key = sKeyboardSettings.chordingAltKey
        if (key == 0) key = KeyEvent.KEYCODE_ALT_LEFT
        val meta = KeyEvent.META_ALT_ON or KeyEvent.META_ALT_LEFT_ON
        if (isDown) {
            sendKeyDown(ic, key, meta)
        } else {
            sendKeyUp(ic, key, meta)
        }
    }

    private fun sendMetaKey(ic: InputConnection, isDown: Boolean, chording: Boolean) {
        if (chording && delayChordingMetaModifier()) return

        var key = sKeyboardSettings.chordingMetaKey
        if (key == 0) key = KeyEvent.KEYCODE_META_LEFT
        val meta = KeyEvent.META_META_ON or KeyEvent.META_META_LEFT_ON
        if (isDown) {
            sendKeyDown(ic, key, meta)
        } else {
            sendKeyUp(ic, key, meta)
        }
    }

    private fun sendModifierKeysDown(shifted: Boolean) {
        val ic = getCurrentInputConnection()
        if (shifted) {
            //Log.i(TAG, "send SHIFT down");
            sendShiftKey(ic, true)
        }
        if (mModCtrl && (!mCtrlKeyState.isChording || delayChordingCtrlModifier())) {
            sendCtrlKey(ic, true, false)
        }
        if (mModAlt && (!mAltKeyState.isChording || delayChordingAltModifier())) {
            sendAltKey(ic, true, false)
        }
        if (mModMeta && (!mMetaKeyState.isChording || delayChordingMetaModifier())) {
            sendMetaKey(ic, true, false)
        }
    }

    private fun handleModifierKeysUp(shifted: Boolean, sendKey: Boolean) {
        val ic = getCurrentInputConnection()
        if (mModMeta && (!mMetaKeyState.isChording || delayChordingMetaModifier())) {
            if (sendKey) sendMetaKey(ic, false, false)
            if (!mMetaKeyState.isChording) setModMeta(false)
        }
        if (mModAlt && (!mAltKeyState.isChording || delayChordingAltModifier())) {
            if (sendKey) sendAltKey(ic, false, false)
            if (!mAltKeyState.isChording) setModAlt(false)
        }
        if (mModCtrl && (!mCtrlKeyState.isChording || delayChordingCtrlModifier())) {
            if (sendKey) sendCtrlKey(ic, false, false)
            if (!mCtrlKeyState.isChording) setModCtrl(false)
        }
        if (shifted) {
            //Log.i(TAG, "send SHIFT up");
            if (sendKey) sendShiftKey(ic, false)
            val shiftState = shiftState
            if (!(mShiftKeyState.isChording || shiftState == Keyboard.SHIFT_LOCKED)) {
                resetShift()
            }
        }
    }

    private fun sendModifierKeysUp(shifted: Boolean) {
        handleModifierKeysUp(shifted, true)
    }

    private fun sendSpecialKey(code: Int) {
        if (!isConnectbot) {
            commitTyped(getCurrentInputConnection(), true)
            sendModifiedKeyDownUp(code)
            return
        }

        // TODO(klausw): properly support xterm sequences for Ctrl/Alt modifiers?
        // See http://slackware.osuosl.org/slackware-12.0/source/l/ncurses/xterm.terminfo
        // and the output of "$ infocmp -1L". Support multiple sets, and optional
        // true numpad keys?
        if (ESC_SEQUENCES == null) {
            ESC_SEQUENCES = HashMap()
            CTRL_SEQUENCES = HashMap()

            // VT escape sequences without leading Escape
            ESC_SEQUENCES!![-LatinKeyboardView.KEYCODE_HOME] = "[1~"
            ESC_SEQUENCES!![-LatinKeyboardView.KEYCODE_END] = "[4~"
            ESC_SEQUENCES!![-LatinKeyboardView.KEYCODE_PAGE_UP] = "[5~"
            ESC_SEQUENCES!![-LatinKeyboardView.KEYCODE_PAGE_DOWN] = "[6~"
            ESC_SEQUENCES!![-LatinKeyboardView.KEYCODE_FKEY_F1] = "OP"
            ESC_SEQUENCES!![-LatinKeyboardView.KEYCODE_FKEY_F2] = "OQ"
            ESC_SEQUENCES!![-LatinKeyboardView.KEYCODE_FKEY_F3] = "OR"
            ESC_SEQUENCES!![-LatinKeyboardView.KEYCODE_FKEY_F4] = "OS"
            ESC_SEQUENCES!![-LatinKeyboardView.KEYCODE_FKEY_F5] = "[15~"
            ESC_SEQUENCES!![-LatinKeyboardView.KEYCODE_FKEY_F6] = "[17~"
            ESC_SEQUENCES!![-LatinKeyboardView.KEYCODE_FKEY_F7] = "[18~"
            ESC_SEQUENCES!![-LatinKeyboardView.KEYCODE_FKEY_F8] = "[19~"
            ESC_SEQUENCES!![-LatinKeyboardView.KEYCODE_FKEY_F9] = "[20~"
            ESC_SEQUENCES!![-LatinKeyboardView.KEYCODE_FKEY_F10] = "[21~"
            ESC_SEQUENCES!![-LatinKeyboardView.KEYCODE_FKEY_F11] = "[23~"
            ESC_SEQUENCES!![-LatinKeyboardView.KEYCODE_FKEY_F12] = "[24~"
            ESC_SEQUENCES!![-LatinKeyboardView.KEYCODE_FORWARD_DEL] = "[3~"
            ESC_SEQUENCES!![-LatinKeyboardView.KEYCODE_INSERT] = "[2~"

            // Special ConnectBot hack: Ctrl-1 to Ctrl-0 for F1-F10.
            CTRL_SEQUENCES!![-LatinKeyboardView.KEYCODE_FKEY_F1] =
                KeyEvent.KEYCODE_1
            CTRL_SEQUENCES!![-LatinKeyboardView.KEYCODE_FKEY_F2] =
                KeyEvent.KEYCODE_2
            CTRL_SEQUENCES!![-LatinKeyboardView.KEYCODE_FKEY_F3] = KeyEvent.KEYCODE_3
            CTRL_SEQUENCES!![-LatinKeyboardView.KEYCODE_FKEY_F4] = KeyEvent.KEYCODE_4
            CTRL_SEQUENCES!![-LatinKeyboardView.KEYCODE_FKEY_F5] =
                KeyEvent.KEYCODE_5
            CTRL_SEQUENCES!![-LatinKeyboardView.KEYCODE_FKEY_F6] = KeyEvent.KEYCODE_6
            CTRL_SEQUENCES!![-LatinKeyboardView.KEYCODE_FKEY_F7] =
                KeyEvent.KEYCODE_7
            CTRL_SEQUENCES!![-LatinKeyboardView.KEYCODE_FKEY_F8] = KeyEvent.KEYCODE_8
            CTRL_SEQUENCES!![-LatinKeyboardView.KEYCODE_FKEY_F9] =
                KeyEvent.KEYCODE_9
            CTRL_SEQUENCES!![-LatinKeyboardView.KEYCODE_FKEY_F10] = KeyEvent.KEYCODE_0

            // Natively supported by ConnectBot
            // ESC_SEQUENCES.put(-LatinKeyboardView.KEYCODE_DPAD_UP, "OA");
            // ESC_SEQUENCES.put(-LatinKeyboardView.KEYCODE_DPAD_DOWN, "OB");
            // ESC_SEQUENCES.put(-LatinKeyboardView.KEYCODE_DPAD_LEFT, "OD");
            // ESC_SEQUENCES.put(-LatinKeyboardView.KEYCODE_DPAD_RIGHT, "OC");

            // No VT equivalents?
            // ESC_SEQUENCES.put(-LatinKeyboardView.KEYCODE_DPAD_CENTER, "");
            // ESC_SEQUENCES.put(-LatinKeyboardView.KEYCODE_SYSRQ, "");
            // ESC_SEQUENCES.put(-LatinKeyboardView.KEYCODE_BREAK, "");
            // ESC_SEQUENCES.put(-LatinKeyboardView.KEYCODE_NUM_LOCK, "");
            // ESC_SEQUENCES.put(-LatinKeyboardView.KEYCODE_SCROLL_LOCK, "");
        }
        val ic = getCurrentInputConnection()
        var ctrlseq: Int? = null
        if (mConnectbotTabHack) {
            ctrlseq = CTRL_SEQUENCES!![code]
        }
        val seq = ESC_SEQUENCES!![code]
        if (ctrlseq != null) {
            if (mModAlt) {
                // send ESC prefix for "Alt"
                ic.commitText(27.toChar().toString(), 1)
            }
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, ctrlseq))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, ctrlseq))
        } else if (seq != null) {
            if (mModAlt) {
                // send ESC prefix for "Alt"
                ic.commitText(27.toChar().toString(), 1)
            }
            // send ESC prefix of escape sequence
            ic.commitText(27.toChar().toString(), 1)
            ic.commitText(seq, 1)
        } else {
            // send key code, let connectbot handle it
            sendDownUpKeyEvents(code)
        }
        handleModifierKeysUp(false, false)
    }

    fun sendModifiableKeyChar(ch: Char) {
        // Support modified key events
        val modShift = isShiftMod
        if ((modShift || mModCtrl || mModAlt || mModMeta) && ch.code > 0 && ch.code < 127) {
            val ic = getCurrentInputConnection()
            if (isConnectbot) {
                if (mModAlt) {
                    // send ESC prefix
                    ic.commitText(27.toChar().toString(), 1)
                }
                if (mModCtrl) {
                    val code = ch.code and 31
                    if (code == 9) {
                        sendTab()
                    } else {
                        ic.commitText(code.toChar().toString(), 1)
                    }
                } else {
                    ic.commitText(ch.toString(), 1)
                }
                handleModifierKeysUp(false, false)
                return
            }

            // Non-ConnectBot

            // Restrict Shift modifier to ENTER and SPACE, supporting Shift-Enter etc.
            // Note that most special keys such as DEL or cursor keys aren't handled
            // by this charcode-based method.
            val combinedCode = asciiToKeyCode[ch.code]
            if (combinedCode > 0) {
                val code = combinedCode and KF_MASK
                val shiftable = combinedCode and KF_SHIFTABLE > 0
                val upper = combinedCode and KF_UPPER > 0
                val letter = combinedCode and KF_LETTER > 0
                val shifted = modShift && (upper || shiftable)
                if (letter && !mModCtrl && !mModAlt && !mModMeta) {
                    // Try workaround for issue 179 where letters don't get upcased
                    ic.commitText(ch.toString(), 1)
                    handleModifierKeysUp(false, false)
                } else if ((ch == 'a' || ch == 'A') && mModCtrl) {
                    // Special case for Ctrl-A to work around accidental select-all-then-replace.
                    if (sKeyboardSettings.ctrlAOverride == 0) {
                        // Ignore Ctrl-A, treat Ctrl-Alt-A as Ctrl-A.
                        if (mModAlt) {
                            val isChordingAlt = mAltKeyState.isChording
                            setModAlt(false)
                            sendModifiedKeyDownUp(code, shifted)
                            if (isChordingAlt) setModAlt(true)
                        } else {
                            Toast.makeText(
                                applicationContext,
                                resources
                                    .getString(R.string.toast_ctrl_a_override_info),
                                Toast.LENGTH_LONG
                            ).show()
                            // Clear the Ctrl modifier (and others)
                            sendModifierKeysDown(shifted)
                            sendModifierKeysUp(shifted)
                            return  // ignore the key
                        }
                    } else if (sKeyboardSettings.ctrlAOverride == 1) {
                        // Clear the Ctrl modifier (and others)
                        sendModifierKeysDown(shifted)
                        sendModifierKeysUp(shifted)
                        return  // ignore the key
                    } else {
                        // Standard Ctrl-A behavior.
                        sendModifiedKeyDownUp(code, shifted)
                    }
                } else {
                    sendModifiedKeyDownUp(code, shifted)
                }
                return
            }
        }
        if (ch in '0'..'9') {
            //WIP
            val ic = getCurrentInputConnection()
            ic.clearMetaKeyStates(KeyEvent.META_SHIFT_ON or KeyEvent.META_ALT_ON or KeyEvent.META_SYM_ON)
            //EditorInfo ei = getCurrentInputEditorInfo();
            //Log.i(TAG, "capsmode=" + ic.getCursorCapsMode(ei.inputType));
            //sendModifiedKeyDownUp(KeyEvent.KEYCODE_0 + ch - '0');
            //return;
        }

        // Default handling for anything else, including unmodified ENTER and SPACE.
        sendKeyChar(ch)
    }

    private fun sendTab() {
        val ic = getCurrentInputConnection()
        val tabHack = isConnectbot && mConnectbotTabHack

        // FIXME: tab and ^I don't work in connectbot, hackish workaround
        if (tabHack) {
            if (mModAlt) {
                // send ESC prefix
                ic.commitText(27.toChar().toString(), 1)
            }
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_DPAD_CENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_DPAD_CENTER))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_I))
            ic.sendKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_I))
        } else {
            sendModifiedKeyDownUp(KeyEvent.KEYCODE_TAB)
        }
    }

    private fun sendEscape() {
        if (isConnectbot) {
            sendKeyChar(27.toChar())
        } else {
            sendModifiedKeyDownUp(111 /*KeyEvent.KEYCODE_ESCAPE */)
        }
    }

    private fun processMultiKey(primaryCode: Int): Boolean {
        if (mDeadAccentBuffer.composeBuffer.isNotEmpty()) {
            //Log.i(TAG, "processMultiKey: pending DeadAccent, length=" + mDeadAccentBuffer.composeBuffer.length());
            mDeadAccentBuffer.execute(primaryCode)
            mDeadAccentBuffer.clear()
            return true
        }
        if (mComposeMode) {
            mComposeMode = mComposeBuffer.execute(primaryCode)
            return true
        }
        return false
    }

    // Implementation of KeyboardViewListener
    override fun onKey(primaryCode: Int, keyCodes: IntArray?, x: Int, y: Int) {
        val `when` = SystemClock.uptimeMillis()
        if (primaryCode != Keyboard.KEYCODE_DELETE
            || `when` > mLastKeyTime + QUICK_PRESS
        ) {
            mDeleteCount = 0
        }
        mLastKeyTime = `when`
        val distinctMultiTouch = mKeyboardSwitcher?.hasDistinctMultitouch()
        when (primaryCode) {
            Keyboard.KEYCODE_DELETE -> {
                if (!processMultiKey(primaryCode)) {
                    handleBackspace()
                    mDeleteCount++
                }
            }

            Keyboard.KEYCODE_SHIFT ->
                // Shift key is handled in onPress() when device has distinct
                // multi-touch panel.
                if (!distinctMultiTouch!!) handleShift()

            Keyboard.KEYCODE_MODE_CHANGE ->
                // Symbol key is handled in onPress() when device has distinct
                // multi-touch panel.
                if (!distinctMultiTouch!!) changeKeyboardMode()

            LatinKeyboardView.KEYCODE_CTRL_LEFT ->
                // Ctrl key is handled in onPress() when device has distinct
                // multi-touch panel.
                if (!distinctMultiTouch!!) setModCtrl(!mModCtrl)

            LatinKeyboardView.KEYCODE_ALT_LEFT ->
                // Alt key is handled in onPress() when device has distinct
                // multi-touch panel.
                if (!distinctMultiTouch!!) setModAlt(!mModAlt)

            LatinKeyboardView.KEYCODE_META_LEFT ->
                // Meta key is handled in onPress() when device has distinct
                // multi-touch panel.
                if (!distinctMultiTouch!!) setModMeta(!mModMeta)

            LatinKeyboardView.KEYCODE_FN -> if (!distinctMultiTouch!!) setModFn(!mModFn)
            Keyboard.KEYCODE_CANCEL -> if (!isShowingOptionDialog) handleClose()

            LatinKeyboardView.KEYCODE_OPTIONS -> onOptionKeyPressed()
            LatinKeyboardView.KEYCODE_OPTIONS_LONGPRESS -> onOptionKeyLongPressed()
            LatinKeyboardView.KEYCODE_COMPOSE -> {
                mComposeMode = !mComposeMode
                mComposeBuffer.clear()
            }

            LatinKeyboardView.KEYCODE_NEXT_LANGUAGE -> toggleLanguage(false, true)
            LatinKeyboardView.KEYCODE_PREV_LANGUAGE -> toggleLanguage(false, false)
            LatinKeyboardView.KEYCODE_VOICE -> if (mVoiceRecognitionTrigger!!.isInstalled) {
                mVoiceRecognitionTrigger!!.startVoiceRecognition()
            }

            9 -> { // Tab
                if (!processMultiKey(primaryCode)) {
                    sendTab()
                }
            }

            LatinKeyboardView.KEYCODE_ESCAPE -> {
                if (!processMultiKey(primaryCode)) {
                    sendEscape()
                }
            }

            LatinKeyboardView.KEYCODE_DPAD_UP,
            LatinKeyboardView.KEYCODE_DPAD_DOWN,
            LatinKeyboardView.KEYCODE_DPAD_LEFT,
            LatinKeyboardView.KEYCODE_DPAD_RIGHT,
            LatinKeyboardView.KEYCODE_DPAD_CENTER,
            LatinKeyboardView.KEYCODE_HOME,
            LatinKeyboardView.KEYCODE_END,
            LatinKeyboardView.KEYCODE_PAGE_UP,
            LatinKeyboardView.KEYCODE_PAGE_DOWN,
            LatinKeyboardView.KEYCODE_FKEY_F1,
            LatinKeyboardView.KEYCODE_FKEY_F2,
            LatinKeyboardView.KEYCODE_FKEY_F3,
            LatinKeyboardView.KEYCODE_FKEY_F4,
            LatinKeyboardView.KEYCODE_FKEY_F5,
            LatinKeyboardView.KEYCODE_FKEY_F6,
            LatinKeyboardView.KEYCODE_FKEY_F7,
            LatinKeyboardView.KEYCODE_FKEY_F8,
            LatinKeyboardView.KEYCODE_FKEY_F9,
            LatinKeyboardView.KEYCODE_FKEY_F10,
            LatinKeyboardView.KEYCODE_FKEY_F11,
            LatinKeyboardView.KEYCODE_FKEY_F12,
            LatinKeyboardView.KEYCODE_FORWARD_DEL,
            LatinKeyboardView.KEYCODE_INSERT,
            LatinKeyboardView.KEYCODE_SYSRQ,
            LatinKeyboardView.KEYCODE_BREAK,
            LatinKeyboardView.KEYCODE_NUM_LOCK,
            LatinKeyboardView.KEYCODE_SCROLL_LOCK -> {
                if (!processMultiKey(primaryCode)) {
                    // send as plain keys, or as escape sequence if needed
                    sendSpecialKey(-primaryCode)
                }
            }

            else -> run {
                if (!mComposeMode && mDeadKeysActive
                    && Character.getType(primaryCode) == Character.NON_SPACING_MARK.toInt()) {
                    //Log.i(TAG, "possible dead character: " + primaryCode);
                    if (!mDeadAccentBuffer.execute(primaryCode)) {
                        //Log.i(TAG, "double dead key");
                        return@run // pressing a dead key twice produces spacing equivalent
                    }
                    updateShiftKeyState(getCurrentInputEditorInfo())
                    return@run
                }
                if (processMultiKey(primaryCode)) {
                    return@run
                }
                if (primaryCode != ASCII_ENTER) {
                    mJustAddedAutoSpace = false
                }
                LatinIMEUtil.RingCharBuffer.instance.push(primaryCode.toChar(), x, y)
                if (isWordSeparator(primaryCode)) {
                    handleSeparator(primaryCode)
                } else {
                    handleCharacter(primaryCode, keyCodes!!)
                }
                // Cancel the just reverted state
                mJustRevertedSeparator = null
            }
        }
        mKeyboardSwitcher!!.onKey(primaryCode)
        // Reset after any single keystroke
        mEnteredText = null
        //mDeadAccentBuffer.clear();  // FIXME
    }

    override fun onText(text: CharSequence?) {
        //mDeadAccentBuffer.clear();  // FIXME
        val ic = getCurrentInputConnection() ?: return
        if (mPredicting && text!!.length == 1) {
            // If adding a single letter, treat it as a regular keystroke so
            // that completion works as expected.
            val c = text[0].code
            if (!isWordSeparator(c)) {
                val codes = intArrayOf(c)
                handleCharacter(c, codes)
                return
            }
        }
        abortCorrection(false)
        ic.beginBatchEdit()
        if (mPredicting) {
            commitTyped(ic, true)
        }
        maybeRemovePreviousPeriod(text!!)
        ic.commitText(text, 1)
        ic.endBatchEdit()
        updateShiftKeyState(getCurrentInputEditorInfo())
        mKeyboardSwitcher!!.onKey(0) // dummy key code.
        mJustRevertedSeparator = null
        mJustAddedAutoSpace = false
        mEnteredText = text
    }

    override fun onCancel() {
        // User released a finger outside any key
        mKeyboardSwitcher!!.onCancelInput()
    }

    private fun handleBackspace() {
        var deleteChar = false
        val ic = getCurrentInputConnection() ?: return

        ic.beginBatchEdit()

        if (mPredicting) {
            val length = mComposing.length
            if (length > 0) {
                mComposing.delete(length - 1, length)
                mWord.deleteLast()
                ic.setComposingText(mComposing, 1)
                if (mComposing.isEmpty()) {
                    mPredicting = false
                }
                postUpdateSuggestions()
            } else {
                ic.deleteSurroundingText(1, 0)
            }
        } else {
            deleteChar = true
        }
        postUpdateShiftKeyState()
        TextEntryState.backspace()
        if (TextEntryState.state == TextEntryState.State.UNDO_COMMIT) {
            revertLastWord(deleteChar)
            ic.endBatchEdit()
            return
        } else if (mEnteredText != null
            && sameAsTextBeforeCursor(ic, mEnteredText!!)
        ) {
            ic.deleteSurroundingText(mEnteredText!!.length, 0)
        } else if (deleteChar) {
            if (mCandidateView?.dismissAddToDictionaryHint() == true) {
                // Go back to the suggestion mode if the user canceled the
                // "Touch again to save".
                // NOTE: In gerenal, we don't revert the word when backspacing
                // from a manual suggestion pick. We deliberately chose a
                // different behavior only in the case of picking the first
                // suggestion (typed word). It's intentional to have made this
                // inconsistent with backspacing after selecting other
                // suggestions.
                revertLastWord(true)
            } else {
                sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                if (mDeleteCount > DELETE_ACCELERATE_AT) {
                    sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
                }
            }
        }
        mJustRevertedSeparator = null
        ic.endBatchEdit()
    }

    private fun setModCtrl(`val`: Boolean) {
        // Log.i("LatinIME", "setModCtrl "+ mModCtrl + "->" + val + ", chording=" + mCtrlKeyState.isChording());
        mKeyboardSwitcher!!.setCtrlIndicator(`val`)
        mModCtrl = `val`
    }

    private fun setModAlt(`val`: Boolean) {
        //Log.i("LatinIME", "setModAlt "+ mModAlt + "->" + val + ", chording=" + mAltKeyState.isChording());
        mKeyboardSwitcher!!.setAltIndicator(`val`)
        mModAlt = `val`
    }

    private fun setModMeta(`val`: Boolean) {
        //Log.i("LatinIME", "setModMeta "+ mModMeta + "->" + val + ", chording=" + mMetaKeyState.isChording());
        mKeyboardSwitcher!!.setMetaIndicator(`val`)
        mModMeta = `val`
    }

    private fun setModFn(`val`: Boolean) {
        //Log.i("LatinIME", "setModFn " + mModFn + "->" + val + ", chording=" + mFnKeyState.isChording());
        mModFn = `val`
        mKeyboardSwitcher!!.setFn(`val`)
        mKeyboardSwitcher!!.setCtrlIndicator(mModCtrl)
        mKeyboardSwitcher!!.setAltIndicator(mModAlt)
        mKeyboardSwitcher!!.setMetaIndicator(mModMeta)
    }

    private fun startMultitouchShift() {
        var newState = Keyboard.SHIFT_ON
        if (mKeyboardSwitcher!!.isAlphabetMode) {
            mSavedShiftState = shiftState
            if (mSavedShiftState == Keyboard.SHIFT_LOCKED) newState = Keyboard.SHIFT_CAPS
        }
        handleShiftInternal(true, newState)
    }

    private fun commitMultitouchShift() {
        if (mKeyboardSwitcher!!.isAlphabetMode) {
            val newState = nextShiftState(mSavedShiftState, true)
            handleShiftInternal(true, newState)
        } else {
            // do nothing, keyboard is already flipped
        }
    }

    private fun resetMultitouchShift() {
        var newState = Keyboard.SHIFT_OFF
        if (mSavedShiftState == Keyboard.SHIFT_CAPS_LOCKED || mSavedShiftState == Keyboard.SHIFT_LOCKED) {
            newState = mSavedShiftState
        }
        handleShiftInternal(true, newState)
    }

    private fun resetShift() {
        handleShiftInternal(true, Keyboard.SHIFT_OFF)
    }

    private fun handleShift() {
        handleShiftInternal(false, -1)
    }

    private fun handleShiftInternal(forceState: Boolean, newState: Int) {
        //Log.i(TAG, "handleShiftInternal forceNormal=" + forceNormal);
        mHandler.removeMessages(MSG_UPDATE_SHIFT_STATE)
        val switcher = mKeyboardSwitcher
        if (switcher!!.isAlphabetMode) {
            if (forceState) {
                switcher.setShiftState(newState)
            } else {
                switcher.setShiftState(nextShiftState(shiftState, true))
            }
        } else {
            switcher.toggleShift()
        }
    }

    private fun abortCorrection(force: Boolean) {
        if (force || TextEntryState.isCorrecting) {
            getCurrentInputConnection().finishComposingText()
            clearSuggestions()
        }
    }

    private fun handleCharacter(primaryCode: Int, keyCodes: IntArray) {
        if (mLastSelectionStart == mLastSelectionEnd
            && TextEntryState.isCorrecting
        ) {
            abortCorrection(false)
        }
        if (isAlphabet(primaryCode) && isPredictionOn
            && !mModCtrl && !mModAlt && !mModMeta
            && !isCursorTouchingWord
        ) {
            if (!mPredicting) {
                mPredicting = true
                mComposing.setLength(0)
                saveWordInHistory(mBestWord)
                mWord.reset()
            }
        }
        if (mModCtrl || mModAlt || mModMeta) {
            commitTyped(getCurrentInputConnection(), true) // sets mPredicting=false
        }
        if (mPredicting) {
            if (isShiftCapsMode
                && mKeyboardSwitcher!!.isAlphabetMode && mComposing.isEmpty()
            ) {
                // Show suggestions with initial caps if starting out shifted,
                // could be either auto-caps or manual shift.
                mWord.isFirstCharCapitalized = true
            }
            mComposing.append(primaryCode.toChar())
            mWord.add(primaryCode, keyCodes)
            val ic = getCurrentInputConnection()
            if (ic != null) {
                // If it's the first letter, make note of auto-caps state
                if (mWord.size == 1) {
                    mWord.isAutoCapitalized = getCursorCapsMode(ic, getCurrentInputEditorInfo()) != 0
                }
                ic.setComposingText(mComposing, 1)
            }
            postUpdateSuggestions()
        } else {
            sendModifiableKeyChar(primaryCode.toChar())
        }
        updateShiftKeyState(getCurrentInputEditorInfo())
        TextEntryState.typedCharacter(
            primaryCode.toChar(), isWordSeparator(primaryCode)
        )
    }

    private fun handleSeparator(primaryCode: Int) {

        // Should dismiss the "Touch again to save" message when handling
        // separator
        if (mCandidateView?.dismissAddToDictionaryHint() == true) {
            postUpdateSuggestions()
        }
        var pickedDefault = false
        // Handle separator
        val ic = getCurrentInputConnection()
        if (ic != null) {
            ic.beginBatchEdit()
            abortCorrection(false)
        }
        if (mPredicting) {
            // In certain languages where single quote is a separator, it's
            // better
            // not to auto correct, but accept the typed word. For instance,
            // in Italian dov' should not be expanded to dove' because the
            // elision
            // requires the last vowel to be removed.
            if (mAutoCorrectOn
                && primaryCode != '\''.code
                && (mJustRevertedSeparator == null
                    || mJustRevertedSeparator!!.isEmpty()
                    || mJustRevertedSeparator!![0].code != primaryCode
                    )
                ) {
                pickedDefault = pickDefaultSuggestion()
                // Picked the suggestion by the space key. We consider this
                // as "added an auto space" in autocomplete mode, but as manually
                // typed space in "quick fixes" mode.
                if (primaryCode == ASCII_SPACE) {
                    if (mAutoCorrectEnabled) {
                        mJustAddedAutoSpace = true
                    } else {
                        TextEntryState.manualTyped("")
                    }
                }
            } else {
                commitTyped(ic, true)
            }
        }
        if (mJustAddedAutoSpace && primaryCode == ASCII_ENTER) {
            removeTrailingSpace()
            mJustAddedAutoSpace = false
        }
        sendModifiableKeyChar(primaryCode.toChar())

        // Handle the case of ". ." -> " .." with auto-space if necessary
        // before changing the TextEntryState.
        if (TextEntryState.state == TextEntryState.State.PUNCTUATION_AFTER_ACCEPTED
            && primaryCode == ASCII_PERIOD
        ) {
            reswapPeriodAndSpace()
        }

        TextEntryState.typedCharacter(primaryCode.toChar(), true)
        if (TextEntryState.state == TextEntryState.State.PUNCTUATION_AFTER_ACCEPTED
            && primaryCode != ASCII_ENTER
        ) {
            swapPunctuationAndSpace()
        } else if (isPredictionOn && primaryCode == ASCII_SPACE) {
            doubleSpace()
        }
        if (pickedDefault) {
            TextEntryState.backToAcceptedDefault(mWord.typedWord)
        }
        updateShiftKeyState(getCurrentInputEditorInfo())
        ic?.endBatchEdit()
    }

    private fun handleClose() {
        commitTyped(getCurrentInputConnection(), true)
        requestHideSelf(0)
        mKeyboardSwitcher?.inputView?.closing()
        TextEntryState.endSession()
    }

    private fun saveWordInHistory(result: CharSequence?) {
        if (mWord.size <= 1) {
            mWord.reset()
            return
        }
        // Skip if result is null. It happens in some edge case.
        if (TextUtils.isEmpty(result)) {
            return
        }

        // Make a copy of the CharSequence, since it is/could be a mutable
        // CharSequence
        val resultCopy = result.toString()
        val entry = TypedWordAlternatives(resultCopy, WordComposer(mWord))
        mWordHistory.add(entry)
    }

    private fun postUpdateSuggestions() {
        mHandler.removeMessages(MSG_UPDATE_SUGGESTIONS)
        mHandler.sendMessageDelayed(
            mHandler.obtainMessage(MSG_UPDATE_SUGGESTIONS), 100)
    }

    private fun postUpdateOldSuggestions() {
        mHandler.removeMessages(MSG_UPDATE_OLD_SUGGESTIONS)
        mHandler.sendMessageDelayed(
            mHandler.obtainMessage(MSG_UPDATE_OLD_SUGGESTIONS), 300)
    }

    private val isPredictionOn: Boolean
        get() = mPredictionOnForMode && isPredictionWanted
    private val isPredictionWanted: Boolean
        get() = (mShowSuggestions || mSuggestionForceOn) && !suggestionsDisabled()
    private val isCandidateStripVisible: Boolean
        get() = isPredictionOn

    private fun switchToKeyboardView() {
        mHandler.post {
            val view = mKeyboardSwitcher!!.inputView
            if (view != null) {
                val p = view.parent
                if (p is ViewGroup) {
                    p.removeView(view)
                }
                setInputView(mKeyboardSwitcher!!.inputView)
            }
            setCandidatesViewShown(true)
            updateInputViewShown()
            postUpdateSuggestions()
        }
    }

    private fun clearSuggestions() {
        setSuggestions(null, false, false, false)
    }

    private fun setSuggestions(
        suggestions: List<CharSequence?>?,
        completions: Boolean, typedWordValid: Boolean,
        haveMinimalSuggestion: Boolean
    ) {
        if (mIsShowingHint) {
            setCandidatesViewShown(true)
            mIsShowingHint = false
        }
        mCandidateView?.setSuggestions(
            suggestions, completions,
            typedWordValid, haveMinimalSuggestion
        )
    }

    private fun updateSuggestions() {
        val inputView = mKeyboardSwitcher!!.inputView
        (inputView!!.keyboard as LatinKeyboard).setPreferredLetters(null)

        // Check if we have a suggestion engine attached.
        if (mSuggest == null || !isPredictionOn) {
            return
        }
        if (!mPredicting) {
            setNextSuggestions()
            return
        }
        showSuggestions(mWord)
    }

    private fun getTypedSuggestions(word: WordComposer): List<CharSequence?> {
        val stringList = mSuggest!!.getSuggestions(
            mKeyboardSwitcher!!.inputView, word, false, null)
        return stringList
    }

    private fun showCorrections(alternatives: WordAlternatives?) {
        val stringList = alternatives!!.alternatives
        (mKeyboardSwitcher!!.inputView!!.keyboard as LatinKeyboard)
            .setPreferredLetters(null)
        showSuggestions(
            stringList, alternatives.originalWord, false, false
        )
    }

    private fun showSuggestions(word: WordComposer) {
        // long startTime = System.currentTimeMillis(); // TIME MEASUREMENT!
        // TODO Maybe need better way of retrieving previous word
        val prevWord = EditingUtil.getPreviousWord(
            getCurrentInputConnection(), mWordSeparators!!
        )
        val stringList = mSuggest!!.getSuggestions(
            mKeyboardSwitcher!!.inputView, word, false, prevWord)
        // long stopTime = System.currentTimeMillis(); // TIME MEASUREMENT!
        // Log.d("LatinIME","Suggest Total Time - " + (stopTime - startTime));

        val nextLettersFrequencies = mSuggest!!.nextLettersFrequencies

        (mKeyboardSwitcher!!.inputView!!.keyboard as LatinKeyboard)
            .setPreferredLetters(nextLettersFrequencies)

        var correctionAvailable = (!mInputTypeNoAutoCorrect
                && mSuggest!!.hasMinimalCorrection())
        // || mCorrectionMode == mSuggest.CORRECTION_FULL;
        val typedWord = word.typedWord
        // If we're in basic correct
        val typedWordValid =
            mSuggest!!.isValidWord(typedWord)
            || (preferCapitalization() && mSuggest!!.isValidWord(
                typedWord.toString().lowercase()))
        if (mCorrectionMode == Suggest.CORRECTION_FULL
            || mCorrectionMode == Suggest.CORRECTION_FULL_BIGRAM
        ) {
            correctionAvailable = correctionAvailable or typedWordValid
        }
        // Don't auto-correct words with multiple capital letter
        correctionAvailable = correctionAvailable and !word.isMostlyCaps
        correctionAvailable = correctionAvailable and !TextEntryState.isCorrecting

        showSuggestions(stringList, typedWord, typedWordValid, correctionAvailable)
    }

    private fun showSuggestions(
        stringList: List<CharSequence?>,
        typedWord: CharSequence?,
        typedWordValid: Boolean,
        correctionAvailable: Boolean
    ) {
        setSuggestions(stringList, false, typedWordValid, correctionAvailable)
        mBestWord = if (stringList.isNotEmpty()) {
            if (correctionAvailable && !typedWordValid && stringList.size > 1) {
                stringList[1]
            } else {
                typedWord
            }
        } else {
            null
        }
        setCandidatesViewShown(isCandidateStripVisible || mCompletionOn)
    }

    private fun pickDefaultSuggestion(): Boolean {
        // Complete any pending candidate query first
        if (mHandler.hasMessages(MSG_UPDATE_SUGGESTIONS)) {
            mHandler.removeMessages(MSG_UPDATE_SUGGESTIONS)
            updateSuggestions()
        }
        if (!mBestWord.isNullOrEmpty()) {
            TextEntryState.acceptedDefault(mWord.typedWord, mBestWord!!)
            mJustAccepted = true
            pickSuggestion(mBestWord!!, false)
            // Add the word to the auto dictionary if it's not a known word
            addToDictionaries(mBestWord!!, AutoDictionary.FREQUENCY_FOR_TYPED)
            return true
        }
        return false
    }

    fun pickSuggestionManually(index: Int, suggestion: CharSequence) {
        val suggestions = mCandidateView!!.suggestions

        val correcting = TextEntryState.isCorrecting
        val ic = getCurrentInputConnection()
        ic?.beginBatchEdit()
        if (
            mCompletionOn && mCompletions != null && index >= 0
            && index < mCompletions!!.size
            ) {
            val ci = mCompletions!![index]
            ic?.commitCompletion(ci)
            mCommittedLength = suggestion.length
            mCandidateView?.clear()
            updateShiftKeyState(getCurrentInputEditorInfo())
            ic?.endBatchEdit()
            return
        }

        // If this is a punctuation, apply it through the normal key press
        if (suggestion.length == 1
            && (isWordSeparator(suggestion[0].code) || isSuggestedPunctuation(suggestion[0].code))
        ) {
            val primaryCode = suggestion[0]
            onKey(
                primaryCode.code, intArrayOf(primaryCode.code),
                LatinKeyboardBaseView.NOT_A_TOUCH_COORDINATE,
                LatinKeyboardBaseView.NOT_A_TOUCH_COORDINATE
            )
            ic?.endBatchEdit()
            return
        }
        mJustAccepted = true
        pickSuggestion(suggestion, correcting)
        // Add the word to the auto dictionary if it's not a known word
        if (index == 0) {
            addToDictionaries(suggestion, AutoDictionary.FREQUENCY_FOR_PICKED)
        } else {
            addToBigramDictionary(suggestion, 1)
        }
        TextEntryState.acceptedSuggestion(mComposing.toString(), suggestion)
        // Follow it with a space
        if (mAutoSpace && !correcting) {
            sendSpace()
            mJustAddedAutoSpace = true
        }
        val showingAddToDictionaryHint = index == 0
                && mCorrectionMode > 0 && !mSuggest!!.isValidWord(suggestion)
                && !mSuggest!!.isValidWord(suggestion.toString().lowercase())

        if (!correcting) {
            // Fool the state watcher so that a subsequent backspace will not do
            // a revert, unless
            // we just did a correction, in which case we need to stay in
            // TextEntryState.State.PICKED_SUGGESTION state.
            TextEntryState.typedCharacter(ASCII_SPACE.toChar(), true)
            setNextSuggestions()
        } else if (!showingAddToDictionaryHint) {
            // If we're not showing the "Touch again to save", then show
            // corrections again.
            // In case the cursor position doesn't change, make sure we show the
            // suggestions again.
            clearSuggestions()
            postUpdateOldSuggestions()
        }
        if (showingAddToDictionaryHint) {
            mCandidateView!!.showAddToDictionaryHint(suggestion)
        }
        ic?.endBatchEdit()
    }

    private fun rememberReplacedWord(suggestion: CharSequence) {}

    /**
     * Commits the chosen word to the text field and saves it for later
     * retrieval.
     *
     * @param suggestion
     * the suggestion picked by the user to be committed to the text
     * field
     * @param correcting
     * whether this is due to a correction of an existing word.
     */
    private fun pickSuggestion(suggestion: CharSequence, correcting: Boolean) {
        var suggestion = suggestion
        val inputView = mKeyboardSwitcher!!.inputView
        val shiftState = shiftState
        if (shiftState == Keyboard.SHIFT_LOCKED || shiftState == Keyboard.SHIFT_CAPS_LOCKED) {
            suggestion = suggestion.toString().uppercase() // all UPPERCASE
        }
        val ic = getCurrentInputConnection()
        if (ic != null) {
            rememberReplacedWord(suggestion)
            ic.commitText(suggestion, 1)
        }
        saveWordInHistory(suggestion)
        mPredicting = false
        mCommittedLength = suggestion.length
        (inputView!!.keyboard as LatinKeyboard).setPreferredLetters(null)
        // If we just corrected a word, then don't show punctuations
        if (!correcting) {
            setNextSuggestions()
        }
        updateShiftKeyState(getCurrentInputEditorInfo())
    }

    /**
     * Tries to apply any typed alternatives for the word if we have any cached
     * alternatives, otherwise tries to find new corrections and completions for
     * the word.
     *
     * @param touching
     * The word that the cursor is touching, with position
     * information
     * @return true if an alternative was found, false otherwise.
     */
    private fun applyTypedAlternatives(touching: SelectedWord): Boolean {
        // If we didn't find a match, search for result in typed word history
        var foundWord: WordComposer? = null
        var alternatives: WordAlternatives? = null
        for (entry in mWordHistory) {
            if (TextUtils.equals(entry.chosenWord, touching.word)) {
                if (entry is TypedWordAlternatives) {
                    foundWord = entry.word
                }
                alternatives = entry
                break
            }
        }
        // If we didn't find a match, at least suggest completions
        if (foundWord == null
            && (mSuggest!!.isValidWord(touching.word)
                || mSuggest!!.isValidWord(touching.word.toString().lowercase()))
        ) {
            foundWord = WordComposer()
            for (i in 0 until touching.word!!.length) {
                foundWord.add(touching.word!![i].code, intArrayOf(touching.word!![i].code))
            }
            foundWord.isFirstCharCapitalized = Character.isUpperCase(touching.word!![0])
        }
        // Found a match, show suggestions
        if (foundWord != null || alternatives != null) {
            if (alternatives == null) {
                alternatives = TypedWordAlternatives(
                    touching.word, foundWord)
            }
            showCorrections(alternatives)
            if (foundWord != null) {
                mWord = WordComposer(foundWord)
            } else {
                mWord.reset()
            }
            return true
        }
        return false
    }

    private fun setOldSuggestions() {
        if (mCandidateView?.isShowingAddToDictionaryHint() == true)
            return
        val ic = getCurrentInputConnection() ?: return
        if (!mPredicting) {
            // Extract the selected or touching text
            val touching = EditingUtil.getWordAtCursorOrSelection(
                ic, mLastSelectionStart, mLastSelectionEnd, mWordSeparators!!)

            abortCorrection(true)
            setNextSuggestions() // Show the punctuation suggestions list
        } else {
            abortCorrection(true)
        }
    }

    private fun setNextSuggestions() {
        setSuggestions(mSuggestPuncList, false, false, false)
    }

    private fun addToDictionaries(suggestion: CharSequence, frequencyDelta: Int) {
        checkAddToDictionary(suggestion, frequencyDelta, false)
    }

    private fun addToBigramDictionary(suggestion: CharSequence, frequencyDelta: Int) {
        checkAddToDictionary(suggestion, frequencyDelta, true)
    }

    /**
     * Adds to the UserBigramDictionary and/or AutoDictionary
     *
     * @param addToBigramDictionary
     * true if it should be added to bigram dictionary if possible
     */
    private fun checkAddToDictionary(
        suggestion: CharSequence?,
        frequencyDelta: Int, addToBigramDictionary: Boolean
    ) {
        if (suggestion.isNullOrEmpty()) return
        // Only auto-add to dictionary if auto-correct is ON. Otherwise we'll be
        // adding words in situations where the user or application really
        // didn't want corrections enabled or learned.
        if (!(mCorrectionMode == Suggest.CORRECTION_FULL || mCorrectionMode == Suggest.CORRECTION_FULL_BIGRAM)) {
            return
        }
        if (!addToBigramDictionary
            && mAutoDictionary!!.isValidWord(suggestion)
            || (!mSuggest!!.isValidWord(suggestion.toString())
                    && !mSuggest!!.isValidWord(suggestion.toString().lowercase()))
        ) {
            mAutoDictionary!!.addWord(suggestion.toString(), frequencyDelta)
        }
        if (mUserBigramDictionary != null) {
            val prevWord = EditingUtil.getPreviousWord(
                getCurrentInputConnection(), mSentenceSeparators!!
            )
            if (!TextUtils.isEmpty(prevWord)) {
                mUserBigramDictionary!!.addBigrams(
                    prevWord.toString(), suggestion.toString())
            }
        }
    }

    private val isCursorTouchingWord: Boolean
        get() {
            val ic = getCurrentInputConnection() ?: return false
            val toLeft = ic.getTextBeforeCursor(1, 0)
            val toRight = ic.getTextAfterCursor(1, 0)
            return if (!TextUtils.isEmpty(toLeft) && !isWordSeparator(toLeft!![0].code)
                && !isSuggestedPunctuation(toLeft[0].code)
            ) {
                true
            } else (!TextUtils.isEmpty(toRight) && !isWordSeparator(toRight!![0].code)
                    && !isSuggestedPunctuation(toRight[0].code))
        }

    private fun sameAsTextBeforeCursor(ic: InputConnection, text: CharSequence): Boolean {
        val beforeText = ic.getTextBeforeCursor(text.length, 0)
        return TextUtils.equals(text, beforeText)
    }

    fun revertLastWord(deleteChar: Boolean) {
        val length = mComposing.length
        if (!mPredicting && length > 0) {
            val ic = getCurrentInputConnection()
            mPredicting = true
            mJustRevertedSeparator = ic.getTextBeforeCursor(1, 0)
            if (deleteChar) ic.deleteSurroundingText(1, 0)
            var toDelete = mCommittedLength
            val toTheLeft = ic.getTextBeforeCursor(mCommittedLength, 0)
            if (!toTheLeft.isNullOrEmpty() && isWordSeparator(toTheLeft[0].code)) {
                toDelete--
            }
            ic.deleteSurroundingText(toDelete, 0)
            ic.setComposingText(mComposing, 1)
            TextEntryState.backspace()
            postUpdateSuggestions()
        } else {
            sendDownUpKeyEvents(KeyEvent.KEYCODE_DEL)
            mJustRevertedSeparator = null
        }
    }

    protected val wordSeparators
        get() = mWordSeparators

    fun isWordSeparator(code: Int): Boolean {
        val separators = wordSeparators
        return separators!!.contains(code.toChar().toString())
    }

    private fun isSentenceSeparator(code: Int): Boolean {
        return mSentenceSeparators!!.contains(code.toChar().toString())
    }

    private fun sendSpace() {
        sendModifiableKeyChar(ASCII_SPACE.toChar())
        updateShiftKeyState(getCurrentInputEditorInfo())
        // onKey(KEY_SPACE[0], KEY_SPACE);
    }

    fun preferCapitalization(): Boolean {
        return mWord.isFirstCharCapitalized
    }

    fun toggleLanguage(reset: Boolean, next: Boolean) {
        if (reset) {
            mLanguageSwitcher!!.reset()
        } else {
            if (next) {
                mLanguageSwitcher!!.next()
            } else {
                mLanguageSwitcher!!.prev()
            }
        }
        val currentKeyboardMode = mKeyboardSwitcher!!.keyboardMode
        reloadKeyboards()
        mKeyboardSwitcher!!.makeKeyboards(true)
        mKeyboardSwitcher!!.setKeyboardMode(
            currentKeyboardMode, 0,
            mEnableVoiceButton && mEnableVoice
        )
        initSuggest(mLanguageSwitcher!!.inputLanguage)
        mLanguageSwitcher!!.persist()
        mAutoCapActive = mAutoCapPref && mLanguageSwitcher!!.allowAutoCap()
        mDeadKeysActive = mLanguageSwitcher!!.allowDeadKeys()
        updateShiftKeyState(getCurrentInputEditorInfo())
        setCandidatesViewShown(isPredictionOn)
    }

    override fun onSharedPreferenceChanged(
        sharedPreferences: SharedPreferences,
        key: String?
    ) {
        Log.i("PCKeyboard", "onSharedPreferenceChanged()")
        var needReload = false
        val res = resources

        // Apply globally handled shared prefs
        sKeyboardSettings.sharedPreferenceChanged(sharedPreferences, key!!)
        if (sKeyboardSettings.hasFlag(GlobalKeyboardSettings.FLAG_PREF_NEED_RELOAD)) {
            needReload = true
        }
        if (sKeyboardSettings.hasFlag(GlobalKeyboardSettings.FLAG_PREF_NEW_PUNC_LIST)) {
            initSuggestPuncList()
        }
        if (sKeyboardSettings.hasFlag(GlobalKeyboardSettings.FLAG_PREF_RECREATE_INPUT_VIEW)) {
            mKeyboardSwitcher!!.recreateInputView()
        }
        if (sKeyboardSettings.hasFlag(GlobalKeyboardSettings.FLAG_PREF_RESET_MODE_OVERRIDE)) {
            mKeyboardModeOverrideLandscape = 0
            mKeyboardModeOverridePortrait = 0
        }
        if (sKeyboardSettings.hasFlag(GlobalKeyboardSettings.FLAG_PREF_RESET_KEYBOARDS)) {
            toggleLanguage(true, true)
        }
        val unhandledFlags = sKeyboardSettings.unhandledFlags()
        if (unhandledFlags != GlobalKeyboardSettings.FLAG_PREF_NONE) {
            Log.w(TAG, "Not all flag settings handled, remaining=$unhandledFlags")
        }
        if (PREF_SELECTED_LANGUAGES == key) {
            mLanguageSwitcher!!.loadLocales(sharedPreferences)
            mRefreshKeyboardRequired = true
        } else if (PREF_RECORRECTION_ENABLED == key) {
            mReCorrectionEnabled = sharedPreferences.getBoolean(
                PREF_RECORRECTION_ENABLED, res.getBoolean(R.bool.default_recorrection_enabled)
            )
            if (mReCorrectionEnabled) {
                // It doesn't work right on pre-Gingerbread phones.
                Toast.makeText(
                    applicationContext,
                    res.getString(R.string.recorrect_warning), Toast.LENGTH_LONG
                ).show()
            }
        } else if (PREF_CONNECTBOT_TAB_HACK == key) {
            mConnectbotTabHack = sharedPreferences.getBoolean(
                PREF_CONNECTBOT_TAB_HACK, res.getBoolean(R.bool.default_connectbot_tab_hack)
            )
        } else if (PREF_FULLSCREEN_OVERRIDE == key) {
            mFullscreenOverride = sharedPreferences.getBoolean(
                PREF_FULLSCREEN_OVERRIDE, res.getBoolean(R.bool.default_fullscreen_override)
            )
            needReload = true
        } else if (PREF_FORCE_KEYBOARD_ON == key) {
            mForceKeyboardOn = sharedPreferences.getBoolean(
                PREF_FORCE_KEYBOARD_ON, res.getBoolean(R.bool.default_force_keyboard_on)
            )
            needReload = true
        } else if (PREF_KEYBOARD_NOTIFICATION == key) {
            mKeyboardNotification = sharedPreferences.getBoolean(
                PREF_KEYBOARD_NOTIFICATION, res.getBoolean(R.bool.default_keyboard_notification)
            )
            setNotification(mKeyboardNotification)
        } else if (PREF_SUGGESTIONS_IN_LANDSCAPE == key) {
            mSuggestionsInLandscape = sharedPreferences.getBoolean(
                PREF_SUGGESTIONS_IN_LANDSCAPE, res.getBoolean(R.bool.default_suggestions_in_landscape)
            )
            // Respect the suggestion settings in legacy Gingerbread mode,
            // in portrait mode, or if suggestions in landscape enabled.
            mSuggestionForceOff = false
            mSuggestionForceOn = false
            setCandidatesViewShown(isPredictionOn)
        } else if (PREF_SHOW_SUGGESTIONS == key) {
            mShowSuggestions = sharedPreferences.getBoolean(
                PREF_SHOW_SUGGESTIONS, res.getBoolean(R.bool.default_suggestions)
            )
            mSuggestionForceOff = false
            mSuggestionForceOn = false
            needReload = true
        } else if (PREF_HEIGHT_PORTRAIT == key) {
            mHeightPortrait = getHeight(
                sharedPreferences,
                PREF_HEIGHT_PORTRAIT, res.getString(R.string.default_height_portrait)
            )
            needReload = true
        } else if (PREF_HEIGHT_LANDSCAPE == key) {
            mHeightLandscape = getHeight(
                sharedPreferences,
                PREF_HEIGHT_LANDSCAPE, res.getString(R.string.default_height_landscape)
            )
            needReload = true
        } else if (PREF_HINT_MODE == key) {
            sKeyboardSettings.hintMode = sharedPreferences.getString(
                PREF_HINT_MODE,
                res.getString(R.string.default_hint_mode)
            )!!.toInt()
            needReload = true
        } else if (PREF_LONGPRESS_TIMEOUT == key) {
            sKeyboardSettings.longpressTimeout = getPrefInt(
                sharedPreferences, PREF_LONGPRESS_TIMEOUT,
                res.getString(R.string.default_long_press_duration)
            )
        } else if (PREF_RENDER_MODE == key) {
            sKeyboardSettings.renderMode = getPrefInt(
                sharedPreferences, PREF_RENDER_MODE,
                res.getString(R.string.default_render_mode)
            )
            needReload = true
        } else if (PREF_SWIPE_UP == key) {
            mSwipeUpAction =
                sharedPreferences.getString(PREF_SWIPE_UP, res.getString(R.string.default_swipe_up))
        } else if (PREF_SWIPE_DOWN == key) {
            mSwipeDownAction = sharedPreferences.getString(
                PREF_SWIPE_DOWN,
                res.getString(R.string.default_swipe_down)
            )
        } else if (PREF_SWIPE_LEFT == key) {
            mSwipeLeftAction = sharedPreferences.getString(
                PREF_SWIPE_LEFT,
                res.getString(R.string.default_swipe_left)
            )
        } else if (PREF_SWIPE_RIGHT == key) {
            mSwipeRightAction = sharedPreferences.getString(
                PREF_SWIPE_RIGHT,
                res.getString(R.string.default_swipe_right)
            )
        } else if (PREF_VOL_UP == key) {
            mVolUpAction =
                sharedPreferences.getString(PREF_VOL_UP, res.getString(R.string.default_vol_up))
        } else if (PREF_VOL_DOWN == key) {
            mVolDownAction =
                sharedPreferences.getString(PREF_VOL_DOWN, res.getString(R.string.default_vol_down))
        } else if (PREF_VIBRATE_LEN == key) {
            mVibrateLen = getPrefInt(
                sharedPreferences,
                PREF_VIBRATE_LEN,
                resources.getString(R.string.vibrate_duration_ms)
            )
        }

        updateKeyboardOptions()
        if (needReload) {
            mKeyboardSwitcher!!.makeKeyboards(true)
        }
    }

    private fun doSwipeAction(action: String?): Boolean {
        //Log.i(TAG, "doSwipeAction + " + action);
        if (action.isNullOrEmpty() || action == "none") {
            return false
        } else if (action == "close") {
            handleClose()
        } else if (action == "settings") {
            launchSettings()
        } else if (action == "suggestions") {
            if (mSuggestionForceOn) {
                mSuggestionForceOn = false
                mSuggestionForceOff = true
            } else if (mSuggestionForceOff) {
                mSuggestionForceOn = true
                mSuggestionForceOff = false
            } else if (isPredictionWanted) {
                mSuggestionForceOff = true
            } else {
                mSuggestionForceOn = true
            }
            setCandidatesViewShown(isPredictionOn)
        } else if (action == "lang_prev") {
            toggleLanguage(false, false)
        } else if (action == "lang_next") {
            toggleLanguage(false, true)
        } else if (action == "full_mode") {
            if (isPortrait) {
                mKeyboardModeOverridePortrait =
                    (mKeyboardModeOverridePortrait + 1) % mNumKeyboardModes
            } else {
                mKeyboardModeOverrideLandscape =
                    (mKeyboardModeOverrideLandscape + 1) % mNumKeyboardModes
            }
            toggleLanguage(true, true)
        } else if (action == "extension") {
            sKeyboardSettings.useExtension = !sKeyboardSettings.useExtension
            reloadKeyboards()
        } else if (action == "height_up") {
            if (isPortrait) {
                mHeightPortrait += 5
                if (mHeightPortrait > 70) mHeightPortrait = 70
            } else {
                mHeightLandscape += 5
                if (mHeightLandscape > 70) mHeightLandscape = 70
            }
            toggleLanguage(true, true)
        } else if (action == "height_down") {
            if (isPortrait) {
                mHeightPortrait -= 5
                if (mHeightPortrait < 15) mHeightPortrait = 15
            } else {
                mHeightLandscape -= 5
                if (mHeightLandscape < 15) mHeightLandscape = 15
            }
            toggleLanguage(true, true)
        } else {
            Log.i(TAG, "Unsupported swipe action config: $action")
        }
        return true
    }

    override fun swipeRight(): Boolean {
        return doSwipeAction(mSwipeRightAction)
    }

    override fun swipeLeft(): Boolean {
        return doSwipeAction(mSwipeLeftAction)
    }

    override fun swipeDown(): Boolean {
        return doSwipeAction(mSwipeDownAction)
    }

    override fun swipeUp(): Boolean {
        return doSwipeAction(mSwipeUpAction)
    }

    override fun onPress(primaryCode: Int) {
        val ic = getCurrentInputConnection()
        if (mKeyboardSwitcher!!.isVibrateAndSoundFeedbackRequired) {
            vibrate()
            playKeyClick(primaryCode)
        }
        val distinctMultiTouch = mKeyboardSwitcher!!.hasDistinctMultitouch()
        if (distinctMultiTouch && primaryCode == Keyboard.KEYCODE_SHIFT) {
            mShiftKeyState.onPress()
            startMultitouchShift()
        } else if (distinctMultiTouch
            && primaryCode == Keyboard.KEYCODE_MODE_CHANGE
        ) {
            changeKeyboardMode()
            mSymbolKeyState.onPress()
            mKeyboardSwitcher!!.setAutoModeSwitchStateMomentary()
        } else if (distinctMultiTouch
            && primaryCode == LatinKeyboardView.KEYCODE_CTRL_LEFT
        ) {
            setModCtrl(!mModCtrl)
            mCtrlKeyState.onPress()
            sendCtrlKey(ic, true, true)
        } else if (distinctMultiTouch
            && primaryCode == LatinKeyboardView.KEYCODE_ALT_LEFT
        ) {
            setModAlt(!mModAlt)
            mAltKeyState.onPress()
            sendAltKey(ic, true, true)
        } else if (distinctMultiTouch
            && primaryCode == LatinKeyboardView.KEYCODE_META_LEFT
        ) {
            setModMeta(!mModMeta)
            mMetaKeyState.onPress()
            sendMetaKey(ic, true, true)
        } else if (distinctMultiTouch
            && primaryCode == LatinKeyboardView.KEYCODE_FN
        ) {
            setModFn(!mModFn)
            mFnKeyState.onPress()
        } else {
            mShiftKeyState.onOtherKeyPressed()
            mSymbolKeyState.onOtherKeyPressed()
            mCtrlKeyState.onOtherKeyPressed()
            mAltKeyState.onOtherKeyPressed()
            mMetaKeyState.onOtherKeyPressed()
            mFnKeyState.onOtherKeyPressed()
        }
    }

    override fun onRelease(primaryCode: Int) {
        // Reset any drag flags in the keyboard
        (mKeyboardSwitcher!!.inputView!!.keyboard as LatinKeyboard)
            .keyReleased()
        // vibrate();
        val distinctMultiTouch = mKeyboardSwitcher!!.hasDistinctMultitouch()
        val ic = getCurrentInputConnection()
        if (distinctMultiTouch && primaryCode == Keyboard.KEYCODE_SHIFT) {
            if (mShiftKeyState.isChording) {
                resetMultitouchShift()
            } else {
                commitMultitouchShift()
            }
            mShiftKeyState.onRelease()
        } else if (distinctMultiTouch
            && primaryCode == Keyboard.KEYCODE_MODE_CHANGE
        ) {
            // Snap back to the previous keyboard mode if the user chords the
            // mode change key and
            // other key, then released the mode change key.
            if (mKeyboardSwitcher!!.isInChordingAutoModeSwitchState) changeKeyboardMode()
            mSymbolKeyState.onRelease()
        } else if (distinctMultiTouch
            && primaryCode == LatinKeyboardView.KEYCODE_CTRL_LEFT
        ) {
            if (mCtrlKeyState.isChording) {
                setModCtrl(false)
            }
            sendCtrlKey(ic, false, true)
            mCtrlKeyState.onRelease()
        } else if (distinctMultiTouch
            && primaryCode == LatinKeyboardView.KEYCODE_ALT_LEFT
        ) {
            if (mAltKeyState.isChording) {
                setModAlt(false)
            }
            sendAltKey(ic, false, true)
            mAltKeyState.onRelease()
        } else if (distinctMultiTouch
            && primaryCode == LatinKeyboardView.KEYCODE_META_LEFT
        ) {
            if (mMetaKeyState.isChording) {
                setModMeta(false)
            }
            sendMetaKey(ic, false, true)
            mMetaKeyState.onRelease()
        } else if (distinctMultiTouch
            && primaryCode == LatinKeyboardView.KEYCODE_FN
        ) {
            if (mFnKeyState.isChording) {
                setModFn(false)
            }
            mFnKeyState.onRelease()
        }
        // WARNING: Adding a chording modifier key? Make sure you also
        // edit PointerTracker.isModifierInternal(), otherwise it will
        // force a release event instead of chording.
    }

    // receive ringer mode changes to detect silent mode
    private val mReceiver: BroadcastReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            updateRingerMode()
        }
    }

    // update flags for silent mode
    private fun updateRingerMode() {
        if (mAudioManager == null) {
            mAudioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        }
        if (mAudioManager != null) {
            mSilentMode = mAudioManager!!.getRingerMode() != AudioManager.RINGER_MODE_NORMAL
        }
    }

    private val keyClickVolume: Float
        get() {
            if (mAudioManager == null) return 0.0f // shouldn't happen

            // The volume calculations are poorly documented, this is the closest I could
            // find for explaining volume conversions:
            // http://developer.android.com/reference/android/media/MediaPlayer.html#setAuxEffectSendLevel(float)
            //
            //   Note that the passed level value is a raw scalar. UI controls should be scaled logarithmically:
            //   the gain applied by audio framework ranges from -72dB to 0dB, so an appropriate conversion
            //   from linear UI input x to level is: x == 0 -> level = 0 0 < x <= R -> level = 10^(72*(x-R)/20/R)
            val method = sKeyboardSettings.keyClickMethod // See click_method_values in strings.xml
            if (method == 0) return FX_VOLUME

            var targetVol = sKeyboardSettings.keyClickVolume

            if (method > 1) {
                // TODO(klausw): on some devices the media volume controls the click volume?
                // If that's the case, try to set a relative target volume.
                val mediaMax = mAudioManager!!.getStreamMaxVolume(AudioManager.STREAM_MUSIC)
                val mediaVol = mAudioManager!!.getStreamVolume(AudioManager.STREAM_MUSIC)
                //Log.i(TAG, "getKeyClickVolume relative, media vol=" + mediaVol + "/" + mediaMax);
                val channelVol = mediaVol.toFloat() / mediaMax
                if (method == 2) {
                    targetVol *= channelVol
                } else if (method == 3) {
                    if (channelVol == 0f) return 0.0f // Channel is silent, won't get audio
                    targetVol = min(targetVol / channelVol, 1.0f)// Cap at 1.0
                }
            }
            // Set absolute volume, treating the percentage as a logarithmic control
            val vol = 10.0f.pow((FX_VOLUME_RANGE_DB * (targetVol - 1) / 20))
            //Log.i(TAG, "getKeyClickVolume absolute, target=" + targetVol + " amp=" + vol);
            return vol
        }

    private fun playKeyClick(primaryCode: Int) {
        // if mAudioManager is null, we don't have the ringer state yet
        // mAudioManager will be set by updateRingerMode
        if (mAudioManager == null) {
            if (mKeyboardSwitcher!!.inputView != null) {
                updateRingerMode()
            }
        }
        if (mSoundOn && !mSilentMode) {
            // FIXME: Volume and enable should come from UI settings
            // FIXME: These should be triggered after auto-repeat logic
            var sound = AudioManager.FX_KEYPRESS_STANDARD
            when (primaryCode) {
                Keyboard.KEYCODE_DELETE -> sound = AudioManager.FX_KEYPRESS_DELETE
                ASCII_ENTER -> sound = AudioManager.FX_KEYPRESS_RETURN
                ASCII_SPACE -> sound = AudioManager.FX_KEYPRESS_SPACEBAR
            }
            mAudioManager!!.playSoundEffect(sound, keyClickVolume)
        }
    }

    private fun vibrate() {
        if (!mVibrateOn) {
            return
        }
        vibrate(mVibrateLen)
    }

    fun vibrate(len: Int) {
        val v = getSystemService(VIBRATOR_SERVICE) as Vibrator?
        if (v != null) {
            v.vibrate(len.toLong())
            return
        }

        mKeyboardSwitcher?.inputView?.performHapticFeedback(
            HapticFeedbackConstants.KEYBOARD_TAP,
            HapticFeedbackConstants.FLAG_IGNORE_GLOBAL_SETTING
        )
    }

    /* package */
    fun promoteToUserDictionary(word: String, frequency: Int) {
        if (mUserDictionary!!.isValidWord(word)) return
        mUserDictionary!!.addWord(word, frequency)
    }

    val currentWord get() = mWord

    val popupOn get() = mPopupOn

    private fun updateCorrectionMode() {
        mHasDictionary = mSuggest?.hasMainDictionary() == true
        mAutoCorrectOn = ((mAutoCorrectEnabled || mQuickFixes)
                && !mInputTypeNoAutoCorrect && mHasDictionary)
        mCorrectionMode =
            if (mAutoCorrectOn && mAutoCorrectEnabled)
                Suggest.CORRECTION_FULL
            else if (mAutoCorrectOn)
                Suggest.CORRECTION_BASIC
            else Suggest.CORRECTION_NONE
        mCorrectionMode =
            if (mBigramSuggestionEnabled && mAutoCorrectOn && mAutoCorrectEnabled)
                Suggest.CORRECTION_FULL_BIGRAM
            else mCorrectionMode
        if (suggestionsDisabled()) {
            mAutoCorrectOn = false
            mCorrectionMode = Suggest.CORRECTION_NONE
        }
        if (mSuggest != null) {
            mSuggest!!.correctionMode = mCorrectionMode
        }
        Log.i(TAG, "updateCorrectionMode\n" +
                "mSuggestIsNull=${mSuggest == null}\n" +
                "mHasDictionary=$mHasDictionary\n" +
                "suggestionsDisabled=${suggestionsDisabled()}\n" +
                "mAutoCorrectEnabled=$mAutoCorrectEnabled\n" +
                "mCorrectionMode=$mCorrectionMode\n" +
                "mAutoCorrectOn=$mAutoCorrectOn")
    }

    private fun updateAutoTextEnabled(systemLocale: Locale) {
        if (mSuggest == null) return
        val different = !systemLocale.language.equals(
            mInputLocale!!.substring(0, 2), ignoreCase = true
        )
        mSuggest!!.setAutoTextEnabled(!different && mQuickFixes)
    }

    protected fun launchSettings(settingsClass: Class<out Activity?>? = LatinIMESettings::class.java) {
        handleClose()
        val intent = Intent()
        intent.setClass(this@LatinIME, settingsClass!!)
        intent.setFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        startActivity(intent)
    }

    private fun loadSettings() {
        // Get the settings preferences
        val sp = PreferenceManager.getDefaultSharedPreferences(this)
        mVibrateOn = sp.getBoolean(PREF_VIBRATE_ON, false)
        mVibrateLen = getPrefInt(
            sp, PREF_VIBRATE_LEN, resources.getString(R.string.vibrate_duration_ms))
        mSoundOn = sp.getBoolean(PREF_SOUND_ON, false)
        mPopupOn = sp.getBoolean(
            PREF_POPUP_ON, mResources!!.getBoolean(R.bool.default_popup_preview)
        )
        mAutoCapPref = sp.getBoolean(
            PREF_AUTO_CAP, resources.getBoolean(R.bool.default_auto_cap)
        )
        mQuickFixes = sp.getBoolean(PREF_QUICK_FIXES, true)
        mShowSuggestions = sp.getBoolean(
            PREF_SHOW_SUGGESTIONS, mResources!!.getBoolean(R.bool.default_suggestions)
        )
        val voiceMode = sp.getString(PREF_VOICE_MODE, getString(R.string.voice_mode_main))
        val enableVoice = (voiceMode != getString(R.string.voice_mode_off) && mEnableVoiceButton)
        val voiceOnPrimary = (voiceMode == getString(R.string.voice_mode_main))
        if (mKeyboardSwitcher != null
            && (enableVoice != mEnableVoice || voiceOnPrimary != mVoiceOnPrimary)
        ) {
            mKeyboardSwitcher!!.setVoiceMode(enableVoice, voiceOnPrimary)
        }
        mEnableVoice = enableVoice
        mVoiceOnPrimary = voiceOnPrimary

        mAutoCorrectEnabled = (sp.getBoolean(
            PREF_AUTO_COMPLETE, mResources!!.getBoolean(R.bool.enable_autocorrect)
        ) and mShowSuggestions)
        // mBigramSuggestionEnabled = sp.getBoolean(
        // PREF_BIGRAM_SUGGESTIONS, true) & mShowSuggestions;
        updateCorrectionMode()
        updateAutoTextEnabled(mResources!!.configuration.depLocale!!)
        mLanguageSwitcher!!.loadLocales(sp)
        mAutoCapActive = mAutoCapPref && mLanguageSwitcher!!.allowAutoCap()
        mDeadKeysActive = mLanguageSwitcher!!.allowDeadKeys()
    }

    private fun initSuggestPuncList() {
        mSuggestPuncList = ArrayList()
        var suggestPuncs = sKeyboardSettings.suggestedPunctuation
        val defaultPuncs = resources.getString(R.string.suggested_punctuations_default)
        if (suggestPuncs == defaultPuncs || suggestPuncs!!.isEmpty()) {
            // Not user-configured, load the language-specific default.
            suggestPuncs = resources.getString(R.string.suggested_punctuations)
        }
        for (i in suggestPuncs.indices) {
            mSuggestPuncList!!.add(suggestPuncs.subSequence(i, i + 1))
        }
        setNextSuggestions()
    }

    private fun isSuggestedPunctuation(code: Int): Boolean {
        return sKeyboardSettings.suggestedPunctuation!!.contains(
            code.toChar().toString()
        )
    }

    private fun showOptionsMenu() {
        val builder = AlertDialog.Builder(this)
        builder.setCancelable(true)
        builder.setIcon(R.drawable.ic_dialog_keyboard)
        builder.setNegativeButton(android.R.string.cancel, null)
        val itemSettings: CharSequence = getString(R.string.english_ime_settings)
        val itemInputMethod: CharSequence = getString(R.string.selectInputMethod)
        builder.setItems(
            arrayOf(itemInputMethod, itemSettings)
        ) { di: DialogInterface, position: Int ->
            di.dismiss()
            when (position) {
                POS_SETTINGS -> launchSettings()
                POS_METHOD -> (getSystemService(INPUT_METHOD_SERVICE) as InputMethodManager)
                    .showInputMethodPicker()
            }
        }
        builder.setTitle(
            mResources!!.getString(R.string.english_ime_input_options)
        )
        mOptionsDialog = builder.create()
        val window = mOptionsDialog!!.window
        val lp = window!!.attributes
        lp.token = mKeyboardSwitcher!!.inputView!!.windowToken
        lp.type = WindowManager.LayoutParams.TYPE_APPLICATION_ATTACHED_DIALOG
        window.setAttributes(lp)
        window.addFlags(WindowManager.LayoutParams.FLAG_ALT_FOCUSABLE_IM)
        mOptionsDialog!!.show()
    }

    fun changeKeyboardMode() {
        val switcher = mKeyboardSwitcher
        if (switcher!!.isAlphabetMode) {
            mSavedShiftState = shiftState
        }
        switcher.toggleSymbols()
        if (switcher.isAlphabetMode) {
            switcher.setShiftState(mSavedShiftState)
        }
        updateShiftKeyState(getCurrentInputEditorInfo())
    }

    override fun dump(fd: FileDescriptor, fout: PrintWriter, args: Array<String>) {
        super.dump(fd, fout, args)

        val p: Printer = PrintWriterPrinter(fout)
        p.println("LatinIME state :")
        p.println("  Keyboard mode = ${mKeyboardSwitcher!!.keyboardMode}")
        p.println("  mComposing=$mComposing")
        p.println("  mPredictionOnForMode=$mPredictionOnForMode")
        p.println("  mCorrectionMode=$mCorrectionMode")
        p.println("  mPredicting=$mPredicting")
        p.println("  mAutoCorrectOn=$mAutoCorrectOn")
        p.println("  mAutoSpace=$mAutoSpace")
        p.println("  mCompletionOn=$mCompletionOn")
        p.println("  TextEntryState.state=${TextEntryState.state}")
        p.println("  mSoundOn=$mSoundOn")
        p.println("  mVibrateOn=$mVibrateOn")
        p.println("  mPopupOn=$mPopupOn")
    }

    // Characters per second measurement
    private var mLastCpsTime: Long = 0
    private val mCpsIntervals = LongArray(CPS_BUFFER_SIZE)
    private var mCpsIndex = 0

    private fun measureCps() {
        val now = System.currentTimeMillis()
        if (mLastCpsTime == 0L) mLastCpsTime = now - 100 // Initial
        mCpsIntervals[mCpsIndex] = now - mLastCpsTime
        mLastCpsTime = now
        mCpsIndex = (mCpsIndex + 1) % CPS_BUFFER_SIZE
        var total: Long = 0
        for (i in 0 until CPS_BUFFER_SIZE) total += mCpsIntervals[i]
        println("CPS = " + CPS_BUFFER_SIZE * 1000f / total)
    }

    fun onAutoCompletionStateChanged(isAutoCompletion: Boolean) {
        mKeyboardSwitcher!!.onAutoCompletionStateChanged(isAutoCompletion)
    }

    companion object {
        private const val TAG = "PCKeyboardIME"
        private const val NOTIFICATION_CHANNEL_ID = "PCKeyboard"
        private const val NOTIFICATION_ONGOING_ID = 1001
        var ESC_SEQUENCES: HashMap<Int, String>? = null
        var CTRL_SEQUENCES: HashMap<Int, Int>? = null
        private const val PREF_VIBRATE_ON = "vibrate_on"
        const val PREF_VIBRATE_LEN = "vibrate_len"
        private const val PREF_SOUND_ON = "sound_on"
        private const val PREF_POPUP_ON = "popup_on"
        private const val PREF_AUTO_CAP = "auto_cap"
        private const val PREF_QUICK_FIXES = "quick_fixes"
        private const val PREF_SHOW_SUGGESTIONS = "show_suggestions"
        private const val PREF_AUTO_COMPLETE = "auto_complete"

        // private static final String PREF_BIGRAM_SUGGESTIONS =
        // "bigram_suggestion";
        private const val PREF_VOICE_MODE = "voice_mode"

        // The private IME option used to indicate that no microphone should be
        // shown for a
        // given text field. For instance this is specified by the search dialog
        // when the
        // dialog is already showing a voice search button.
        private const val IME_OPTION_NO_MICROPHONE = "nm"
        const val PREF_SELECTED_LANGUAGES = "selected_languages"
        const val PREF_INPUT_LANGUAGE = "input_language"
        private const val PREF_RECORRECTION_ENABLED = "recorrection_enabled"
        const val PREF_FULLSCREEN_OVERRIDE = "fullscreen_override"
        const val PREF_FORCE_KEYBOARD_ON = "force_keyboard_on"
        const val PREF_KEYBOARD_NOTIFICATION = "keyboard_notification"
        const val PREF_CONNECTBOT_TAB_HACK = "connectbot_tab_hack"
        const val PREF_FULL_KEYBOARD_IN_PORTRAIT = "full_keyboard_in_portrait"
        const val PREF_SUGGESTIONS_IN_LANDSCAPE = "suggestions_in_landscape"
        const val PREF_HEIGHT_PORTRAIT = "settings_height_portrait"
        const val PREF_HEIGHT_LANDSCAPE = "settings_height_landscape"
        const val PREF_HINT_MODE = "pref_hint_mode"
        const val PREF_LONGPRESS_TIMEOUT = "pref_long_press_duration"
        const val PREF_RENDER_MODE = "pref_render_mode"
        const val PREF_SWIPE_UP = "pref_swipe_up"
        const val PREF_SWIPE_DOWN = "pref_swipe_down"
        const val PREF_SWIPE_LEFT = "pref_swipe_left"
        const val PREF_SWIPE_RIGHT = "pref_swipe_right"
        const val PREF_VOL_UP = "pref_vol_up"
        const val PREF_VOL_DOWN = "pref_vol_down"
        private const val MSG_UPDATE_SUGGESTIONS = 0
        private const val MSG_START_TUTORIAL = 1
        private const val MSG_UPDATE_SHIFT_STATE = 2
        private const val MSG_VOICE_RESULTS = 3
        private const val MSG_UPDATE_OLD_SUGGESTIONS = 4

        // How many continuous deletes at which to start deleting at a higher speed.
        private const val DELETE_ACCELERATE_AT = 20

        // Key events coming any faster than this are long-presses.
        private const val QUICK_PRESS = 200
        const val ASCII_ENTER = '\n'.code
        const val ASCII_SPACE = ' '.code
        const val ASCII_PERIOD = '.'.code

        // Contextual menu positions
        private const val POS_METHOD = 0
        private const val POS_SETTINGS = 1
        @JvmField
        val sKeyboardSettings = GlobalKeyboardSettings()
        var sInstance: LatinIME? = null

        /**
         * Loads a dictionary or multiple separated dictionary
         *
         * @return returns array of dictionary resource ids
         */
        /* package */
        fun getDictionary(res: Resources): IntArray {
            val packageName = LatinIME::class.java.getPackage()?.name
            val xrp = res.getXml(R.xml.dictionary)
            val dictionaries = ArrayList<Int>()
            try {
                var current = xrp.eventType
                while (current != XmlResourceParser.END_DOCUMENT) {
                    if (current == XmlResourceParser.START_TAG) {
                        val tag = xrp.name
                        if (tag == "part") {
                            val dictFileName = xrp.getAttributeValue(null, "name")
                            dictionaries.add(
                                res.getIdentifier(dictFileName, "raw", packageName))
                        }
                    }
                    xrp.next()
                    current = xrp.eventType
                }
            } catch (e: XmlPullParserException) {
                Log.e(TAG, "Dictionary XML parsing failure")
            } catch (e: IOException) {
                Log.e(TAG, "Dictionary XML IOException")
            }
            val count = dictionaries.size
            val dict = IntArray(count)
            for (i in 0 until count) {
                dict[i] = dictionaries[i]
            }
            return dict
        }

        private val asciiToKeyCode = IntArray(127)
        private const val KF_MASK = 0xffff
        private const val KF_SHIFTABLE = 0x10000
        private const val KF_UPPER = 0x20000
        private const val KF_LETTER = 0x40000

        init {
            // Include RETURN in this set even though it's not printable.
            // Most other non-printable keys get handled elsewhere.
            asciiToKeyCode['\n'.code] = KeyEvent.KEYCODE_ENTER or KF_SHIFTABLE

            // Non-alphanumeric ASCII codes which have their own keys
            // (on some keyboards)
            asciiToKeyCode[' '.code] = KeyEvent.KEYCODE_SPACE or KF_SHIFTABLE
            //asciiToKeyCode['!'] = KeyEvent.KEYCODE_;
            //asciiToKeyCode['"'] = KeyEvent.KEYCODE_;
            asciiToKeyCode['#'.code] = KeyEvent.KEYCODE_POUND
            //asciiToKeyCode['$'] = KeyEvent.KEYCODE_;
            //asciiToKeyCode['%'] = KeyEvent.KEYCODE_;
            //asciiToKeyCode['&'] = KeyEvent.KEYCODE_;
            asciiToKeyCode['\''.code] = KeyEvent.KEYCODE_APOSTROPHE
            //asciiToKeyCode['('] = KeyEvent.KEYCODE_;
            //asciiToKeyCode[')'] = KeyEvent.KEYCODE_;
            asciiToKeyCode['*'.code] = KeyEvent.KEYCODE_STAR
            asciiToKeyCode['+'.code] = KeyEvent.KEYCODE_PLUS
            asciiToKeyCode[','.code] = KeyEvent.KEYCODE_COMMA
            asciiToKeyCode['-'.code] = KeyEvent.KEYCODE_MINUS
            asciiToKeyCode['.'.code] = KeyEvent.KEYCODE_PERIOD
            asciiToKeyCode['/'.code] = KeyEvent.KEYCODE_SLASH
            //asciiToKeyCode[':'] = KeyEvent.KEYCODE_;
            asciiToKeyCode[';'.code] = KeyEvent.KEYCODE_SEMICOLON
            //asciiToKeyCode['<'] = KeyEvent.KEYCODE_;
            asciiToKeyCode['='.code] = KeyEvent.KEYCODE_EQUALS
            //asciiToKeyCode['>'] = KeyEvent.KEYCODE_;
            //asciiToKeyCode['?'] = KeyEvent.KEYCODE_;
            asciiToKeyCode['@'.code] = KeyEvent.KEYCODE_AT
            asciiToKeyCode['['.code] = KeyEvent.KEYCODE_LEFT_BRACKET
            asciiToKeyCode['\\'.code] = KeyEvent.KEYCODE_BACKSLASH
            asciiToKeyCode[']'.code] = KeyEvent.KEYCODE_RIGHT_BRACKET
            //asciiToKeyCode['^'] = KeyEvent.KEYCODE_;
            //asciiToKeyCode['_'] = KeyEvent.KEYCODE_;
            asciiToKeyCode['`'.code] = KeyEvent.KEYCODE_GRAVE
            //asciiToKeyCode['{'] = KeyEvent.KEYCODE_;
            //asciiToKeyCode['|'] = KeyEvent.KEYCODE_;
            //asciiToKeyCode['}'] = KeyEvent.KEYCODE_;
            //asciiToKeyCode['~'] = KeyEvent.KEYCODE_;
            for (i in 0..25) {
                asciiToKeyCode['a'.code + i] = KeyEvent.KEYCODE_A + i or KF_LETTER
                asciiToKeyCode['A'.code + i] = KeyEvent.KEYCODE_A + i or KF_UPPER or KF_LETTER
            }
            for (i in 0..9) {
                asciiToKeyCode['0'.code + i] = KeyEvent.KEYCODE_0 + i
            }
        }

        private val capsOrShiftLockState: Int
            get() = if (sKeyboardSettings.capsLock) Keyboard.SHIFT_CAPS_LOCKED
            else Keyboard.SHIFT_LOCKED

        // Rotate through shift states by successively pressing and releasing the Shift key.
        private fun nextShiftState(prevState: Int, allowCapsLock: Boolean): Int {
            return if (allowCapsLock) {
                if (prevState == Keyboard.SHIFT_OFF) {
                    Keyboard.SHIFT_ON
                } else if (prevState == Keyboard.SHIFT_ON) {
                    capsOrShiftLockState
                } else {
                    Keyboard.SHIFT_OFF
                }
            } else {
                // currently unused, see toggleShift()
                if (prevState == Keyboard.SHIFT_OFF) {
                    Keyboard.SHIFT_ON
                } else {
                    Keyboard.SHIFT_OFF
                }
            }
        }

        fun <E> newArrayList(vararg elements: E): ArrayList<E> {
            val capacity = elements.size * 110 / 100 + 5
            val list = ArrayList<E>(capacity)
            Collections.addAll(list, *elements)
            return list
        }

        // Characters per second measurement
        private const val CPS_BUFFER_SIZE = 16

        private val NUMBER_RE = Pattern.compile("(\\d+).*")

        fun getIntFromString(`val`: String?, defVal: Int): Int {
            val num = NUMBER_RE.matcher(`val`.toString())
            return if (!num.matches()) defVal
            else num.group(1).toInt()
        }

        fun getPrefInt(prefs: SharedPreferences, prefName: String?, defVal: Int): Int {
            val prefVal = prefs.getString(prefName, defVal.toString())
            //Log.i("PCKeyboard", "getPrefInt " + prefName + " = " + prefVal + ", default " + defVal);
            return getIntFromString(prefVal, defVal)
        }

        fun getPrefInt(prefs: SharedPreferences, prefName: String?, defStr: String?): Int {
            val defVal = getIntFromString(defStr, 0)
            return getPrefInt(prefs, prefName, defVal)
        }

        fun getHeight(prefs: SharedPreferences, prefName: String?, defVal: String?): Int {
            var `val` = getPrefInt(prefs, prefName, defVal)
            if (`val` < 15) `val` = 15
            if (`val` > 75) `val` = 75
            return `val`
        }

        // Align sound effect volume on music volume
        private const val FX_VOLUME = -1.0f
        private const val FX_VOLUME_RANGE_DB = 72.0f
    }
}