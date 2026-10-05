package com.fusion.melodyLinkNeo

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.mutableStateOf
import com.fusion.melodyLinkNeo.melody.bridge.MelodyHostUpdateTracker
import com.fusion.melodyLinkNeo.ui.shell.DeviceCenterApp

/**
 * Single activity hosting the Device Center shell (DEVICE_CENTER_UI_PLAN §6).
 *
 * Edge-to-edge is enabled here; the shell owns status/navigation bar insets and the Liquid Glass
 * bottom bar, so the activity itself stays thin.
 */
class MainActivity : ComponentActivity() {

    /** Set from the notification / explicit intent so a cold start lands on the Melody diagnostics. */
    private val requestedPage = mutableStateOf<String?>(null)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestedPage.value = intent?.getStringExtra(MelodyHostUpdateTracker.EXTRA_PAGE)
        setContent {
            DeviceCenterApp(
                initialRoute = requestedPage.value,
                onInitialRouteConsumed = { requestedPage.value = null },
            )
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        requestedPage.value = intent.getStringExtra(MelodyHostUpdateTracker.EXTRA_PAGE)
    }
}
