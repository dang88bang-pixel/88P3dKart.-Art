package com.example.agent

import android.util.Log
import androidx.multidex.MultiDexApplication

/** Process-wide application. MultiDex is required for the full sensor/pipeline graph. */
class ThreeAgentApp : MultiDexApplication() {
    override fun onCreate() {
        super.onCreate()
        Log.i(TAG, "3dxAgent $VERSION startet — alle Sensor-/BT-/UWB-Kanäle aktiv")
    }

    companion object {
        const val VERSION = "18.1.0"
        private const val TAG = "ThreeAgentApp"
    }
}
