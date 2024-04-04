package org.pocketworkstation.pckeyboard

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Resources
import android.content.res.XmlResourceParser
import android.util.Log
import org.xmlpull.v1.XmlPullParserException
import java.io.IOException
import java.io.InputStream

class PluginManager internal constructor(private val mIME: LatinIME) : BroadcastReceiver() {
    internal interface DictPluginSpec {
        fun getDict(context: Context): BinaryDictionary?
    }

    private abstract class DictPluginSpecBase : DictPluginSpec {
        var mPackageName: String? = null
        fun getResources(context: Context): Resources? {
            val packageManager = context.packageManager
            var res: Resources? = null
            try {
                val appInfo = packageManager.getApplicationInfo(mPackageName!!, 0)
                res = packageManager.getResourcesForApplication(appInfo)
            } catch (e: PackageManager.NameNotFoundException) {
                Log.i(TAG, "couldn't get resources")
            }
            return res
        }

        abstract fun getStreams(res: Resources): Array<InputStream?>?
        override fun getDict(context: Context): BinaryDictionary? {
            val res = getResources(context) ?: return null
            val dicts = getStreams(res) ?: return null
            val dict = BinaryDictionary(
                context, dicts, Suggest.DIC_MAIN
            )
            return if (dict.size == 0) null else dict
            //Log.i(TAG, "dict size=" + dict.getSize());
        }
    }

    override fun onReceive(context: Context, intent: Intent) {
        Log.i(TAG, "Package information changed, updating dictionaries.")
        getPluginDictionaries(context)
        Log.i(TAG, "Finished updating dictionaries.")
        mIME.toggleLanguage(true, true)
    }

    private class DictPluginSpecHK(pkg: String?, ids: IntArray?) : DictPluginSpecBase() {
        var mRawIds: IntArray?

        init {
            mPackageName = pkg
            mRawIds = ids
        }

        override fun getStreams(res: Resources): Array<InputStream?>? {
            if (mRawIds == null || mRawIds!!.isEmpty()) return null
            val streams = arrayOfNulls<InputStream>(
                mRawIds!!.size
            )
            for (i in mRawIds!!.indices) {
                streams[i] = res.openRawResource(mRawIds!![i])
            }
            return streams
        }
    }

    private class DictPluginSpecSoftKeyboard(pkg: String?, asset: String?, resId: Int) :
        DictPluginSpecBase() {
        var mAssetName: String?
        var mResId: Int

        init {
            mPackageName = pkg
            mAssetName = asset
            mResId = resId
        }

        override fun getStreams(res: Resources): Array<InputStream?>? {
            return if (mAssetName == null) {
                if (mResId == 0) return null
                val a = res.obtainTypedArray(mResId)
                val resIds: IntArray
                try {
                    resIds = IntArray(a.length())
                    for (i in 0 until a.length()) {
                        resIds[i] = a.getResourceId(i, 0)
                    }
                } finally {
                    a.recycle()
                }
                val `in` = arrayOfNulls<InputStream>(resIds.size)
                for (i in resIds.indices) {
                    `in`[i] = res.openRawResource(resIds[i])
                }
                `in`
            } else {
                try {
                    val `in` = res.assets.open(mAssetName!!)
                    arrayOf(`in`)
                } catch (e: IOException) {
                    Log.e(TAG, "Dictionary asset loading failure")
                    null
                }
            }
        }
    }

    companion object {
        private const val TAG = "PCKeyboard"
        private const val HK_INTENT_DICT = "org.pocketworkstation.DICT"
        private const val SOFTKEYBOARD_INTENT_DICT = "com.menny.android.anysoftkeyboard.DICTIONARY"
        private const val SOFTKEYBOARD_DICT_RESOURCE_METADATA_NAME =
            "com.menny.android.anysoftkeyboard.dictionaries"

        // Apparently anysoftkeyboard doesn't use ISO 639-1 language codes for its locales?
        // Add exceptions as needed.
        private val SOFTKEYBOARD_LANG_MAP = HashMap<String?, String>()

        init {
            SOFTKEYBOARD_LANG_MAP["dk"] = "da"
        }

        private val mPluginDicts = HashMap<String, DictPluginSpec>()
        fun getSoftKeyboardDictionaries(packageManager: PackageManager) {
            val dictIntent = Intent(SOFTKEYBOARD_INTENT_DICT)
            val dictPacks = packageManager.queryBroadcastReceivers(
                dictIntent, PackageManager.GET_META_DATA
            )
            for (ri in dictPacks) {
                val appInfo = ri.activityInfo.applicationInfo
                val pkgName = appInfo.packageName
                var success = false
                try {
                    val res = packageManager.getResourcesForApplication(appInfo)
                    //Log.i(TAG, "Found dictionary plugin package: " + pkgName);
                    var dictId = res.getIdentifier("dictionaries", "xml", pkgName)
                    if (dictId == 0) {
                        try {
                            dictId = ri.activityInfo.metaData.getInt(
                                SOFTKEYBOARD_DICT_RESOURCE_METADATA_NAME
                            )
                        } catch (ignored: Exception) {
                        }
                    }
                    if (dictId == 0) continue
                    val xrp = res.getXml(dictId)
                    var assetName: String? = null
                    var resId = 0
                    var lang: String? = null
                    try {
                        var current = xrp.eventType
                        while (current != XmlResourceParser.END_DOCUMENT) {
                            if (current == XmlResourceParser.START_TAG) {
                                val tag = xrp.name
                                if (tag != null) {
                                    if (tag == "Dictionary") {
                                        lang = xrp.getAttributeValue(null, "locale")
                                        val convLang = SOFTKEYBOARD_LANG_MAP[lang]
                                        if (convLang != null) lang = convLang
                                        val type = xrp.getAttributeValue(null, "type")
                                        if (type == null || type == "raw" || type == "binary" || type == "binary_resource") {
                                            assetName = xrp.getAttributeValue(
                                                null,
                                                "dictionaryAssertName"
                                            ) // sic
                                            resId = xrp.getAttributeResourceValue(
                                                null,
                                                "dictionaryResourceId",
                                                0
                                            )
                                        } else {
                                            Log.w(
                                                TAG,
                                                "Unsupported AnySoftKeyboard dict type $type"
                                            )
                                        }
                                        //Log.i(TAG, "asset=" + assetName + " lang=" + lang);
                                    }
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
                    if (assetName == null && resId == 0 || lang == null) continue
                    val spec: DictPluginSpec = DictPluginSpecSoftKeyboard(pkgName, assetName, resId)
                    mPluginDicts[lang] = spec
                    Log.i(TAG, "Found plugin dictionary: lang=$lang, pkg=$pkgName")
                    success = true
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.i(TAG, "bad")
                } finally {
                    if (!success) {
                        Log.i(TAG, "failed to load plugin dictionary spec from $pkgName")
                    }
                }
            }
        }

        fun getHKDictionaries(packageManager: PackageManager) {
            val dictIntent = Intent(HK_INTENT_DICT)
            val dictPacks = packageManager.queryIntentActivities(dictIntent, 0)
            for (ri in dictPacks) {
                val appInfo = ri.activityInfo.applicationInfo
                val pkgName = appInfo.packageName
                var success = false
                try {
                    val res = packageManager.getResourcesForApplication(appInfo)
                    //Log.i(TAG, "Found dictionary plugin package: " + pkgName);
                    val langId = res.getIdentifier("dict_language", "string", pkgName)
                    if (langId == 0) continue
                    val lang = res.getString(langId)
                    var rawIds: IntArray

                    // Try single-file version first
                    val rawId = res.getIdentifier("main", "raw", pkgName)
                    if (rawId != 0) {
                        rawIds = intArrayOf(rawId)
                    } else {
                        // try multi-part version
                        var parts = 0
                        val ids: MutableList<Int> = ArrayList()
                        while (true) {
                            val id = res.getIdentifier("main$parts", "raw", pkgName)
                            if (id == 0) break
                            ids.add(id)
                            ++parts
                        }
                        if (parts == 0) continue  // no parts found
                        rawIds = IntArray(parts)
                        for (i in 0 until parts) rawIds[i] = ids[i]
                    }
                    val spec: DictPluginSpec = DictPluginSpecHK(pkgName, rawIds)
                    mPluginDicts[lang] = spec
                    Log.i(TAG, "Found plugin dictionary: lang=$lang, pkg=$pkgName")
                    success = true
                } catch (e: PackageManager.NameNotFoundException) {
                    Log.i(TAG, "bad")
                } finally {
                    if (!success) {
                        Log.i(TAG, "failed to load plugin dictionary spec from $pkgName")
                    }
                }
            }
        }

        fun getPluginDictionaries(context: Context) {
            mPluginDicts.clear()
            val packageManager = context.packageManager
            getSoftKeyboardDictionaries(packageManager)
            getHKDictionaries(packageManager)
        }

        fun getDictionary(context: Context, lang: String): BinaryDictionary? {
            //Log.i(TAG, "Looking for plugin dictionary for lang=" + lang);
            var spec = mPluginDicts[lang]
            if (spec == null) spec = mPluginDicts[lang.substring(0, 2)]
            if (spec == null) {
                //Log.i(TAG, "No plugin found.");
                return null
            }
            val dict = spec.getDict(context)
            Log.i(
                TAG,
                "Found plugin dictionary for " + lang + if (dict == null) " is null" else ", size=" + dict.size
            )
            return dict
        }
    }
}
