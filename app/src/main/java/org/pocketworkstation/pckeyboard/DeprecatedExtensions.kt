package org.pocketworkstation.pckeyboard

import android.content.res.Configuration
import android.os.Build
import java.util.Locale

object DeprecatedExtensions {
    var Configuration.depLocale: Locale?
        get() {
            return if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                this.locales.get(0)
            else this.locale
        }
        set(newLocale) {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N)
                this.setLocale(newLocale)
            else this.locale = newLocale
        }

    /*fun Context.depUpdateConfiguration(config: Configuration, metrics: DisplayMetrics?) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            this.createConfigurationContext(config);
        } else {
            this.resources.updateConfiguration(config, metrics);
        }
    }*/


}