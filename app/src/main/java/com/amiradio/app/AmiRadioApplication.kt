package com.amiradio.app

import android.app.Application
import android.util.Log
import com.google.android.gms.cast.framework.CastContext

class AmiRadioApplication : Application() {

    companion object {
        private const val TAG = "AmiRadioApplication"
    }

    override fun onCreate() {
        super.onCreate()
        // Eagerly initialise CastContext so it is ready before the service starts.
        // The call is a no-op on devices without Google Play Services.
        try {
            CastContext.getSharedInstance(this)
            Log.d(TAG, "CastContext initialised")
        } catch (e: Exception) {
            Log.w(TAG, "Cast not available on this device: ${e.message}")
        }
    }
}
