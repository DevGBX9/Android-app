package com.brainrot.pigeon.share

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import com.brainrot.pigeon.MainActivity
import com.brainrot.pigeon.PigeonApp
import com.brainrot.pigeon.R
import com.brainrot.pigeon.ui.AppTab
import kotlinx.coroutines.launch

/**
 * شاشة شفافة بلا واجهة: تستقبل المشاركة، تحفظها، تفتح التطبيق على تبويب
 * المحفوظات، وتنتهي فوراً.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = (application as PigeonApp).container
        val incoming = intent

        lifecycleScope.launch {
            val count = ShareIntake(this@ShareReceiverActivity, container.saved).handle(incoming)

            val message = when {
                count <= 0 -> getString(R.string.share_nothing)
                count == 1 -> getString(R.string.share_saved_one)
                else -> getString(R.string.share_saved_many, count)
            }
            Toast.makeText(this@ShareReceiverActivity, message, Toast.LENGTH_SHORT).show()

            if (count > 0) {
                startActivity(
                    Intent(this@ShareReceiverActivity, MainActivity::class.java).apply {
                        putExtra(MainActivity.EXTRA_TAB, AppTab.SAVED.name)
                        addFlags(
                            Intent.FLAG_ACTIVITY_NEW_TASK or
                                Intent.FLAG_ACTIVITY_CLEAR_TOP or
                                Intent.FLAG_ACTIVITY_SINGLE_TOP
                        )
                    },
                )
            }

            finish()
        }
    }
}
