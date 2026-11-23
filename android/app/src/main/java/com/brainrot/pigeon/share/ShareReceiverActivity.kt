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
 * شاشة شفافة بلا واجهة: تستقبل المشاركة، تحفظها، تفتح التطبيق على شبكة
 * الريلز مع النافذة الطافية «تم اضافة الريل للقائمة»، وتنتهي فوراً.
 */
class ShareReceiverActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val container = (application as PigeonApp).container
        val incoming = intent

        lifecycleScope.launch {
            val added = ShareIntake(this@ShareReceiverActivity, container.saved).handle(incoming)

            if (added.isEmpty()) {
                Toast.makeText(
                    this@ShareReceiverActivity,
                    getString(R.string.share_nothing),
                    Toast.LENGTH_SHORT,
                ).show()
                finish()
                return@launch
            }

            val detail = if (added.size == 1) {
                added.first()
            } else {
                getString(R.string.reel_added_many, added.size)
            }

            startActivity(
                Intent(this@ShareReceiverActivity, MainActivity::class.java).apply {
                    putExtra(MainActivity.EXTRA_TAB, AppTab.SAVED.name)
                    putExtra(MainActivity.EXTRA_SHARED_REEL, detail)
                    addFlags(
                        Intent.FLAG_ACTIVITY_NEW_TASK or
                            Intent.FLAG_ACTIVITY_CLEAR_TOP or
                            Intent.FLAG_ACTIVITY_SINGLE_TOP
                    )
                },
            )

            finish()
        }
    }
}
