package com.phoebe.app

import android.app.Application
import com.phoebe.app.ui.preloadPhoebeIconSvgs
import kotlinx.coroutines.runBlocking

/** Robolectric application without playback/cast warm-up (avoids flaky Media3 binds in screenshot tests). */
class ScreenshotTestApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        AndroidContextHolder.application = this
        // Icons load async on first composition and Coil is invisible to compose-test idling;
        // warm the byte cache so every icon resolves synchronously from the first frame.
        runBlocking { preloadPhoebeIconSvgs() }
    }
}
