package com.brainrot.pigeon

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.lifecycle.lifecycleScope
import com.brainrot.pigeon.ui.AppTab
import com.brainrot.pigeon.ui.PigeonRoot
import com.brainrot.pigeon.ui.theme.PigeonTheme
import kotlinx.coroutines.launch

class MainActivity : ComponentActivity() {

    private var requestedTab by mutableStateOf(AppTab.PROFILE)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        requestedTab = tabFromIntent(intent) ?: AppTab.PROFILE
        val container = (application as PigeonApp).container

        // مع كل فتح للتطبيق: نسحب قائمة الريلز من Firebase ونعرضها.
        lifecycleScope.launch { container.saved.syncFromRemote() }

        setContent {
            PigeonTheme {
                PigeonRoot(
                    container = container,
                    requestedTab = requestedTab,
                )
            }
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        tabFromIntent(intent)?.let { requestedTab = it }
    }

    private fun tabFromIntent(intent: Intent?): AppTab? =
        when (intent?.getStringExtra(EXTRA_TAB)) {
            AppTab.PROFILE.name -> AppTab.PROFILE
            AppTab.SAVED.name -> AppTab.SAVED
            else -> null
        }

    companion object {
        const val EXTRA_TAB = "com.brainrot.pigeon.extra.TAB"
    }
}
