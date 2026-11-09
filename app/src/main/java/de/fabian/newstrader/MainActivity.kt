package de.fabian.newstrader

import android.Manifest
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import de.fabian.newstrader.ui.AppRoot
import de.fabian.newstrader.ui.AppTheme

class MainActivity : ComponentActivity() {
    private val permLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        Store.init(this)
        Notify.channels(this)
        RefreshWorker.schedule(this)
        if (Build.VERSION.SDK_INT >= 33) permLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        setContent { AppTheme { AppRoot() } }
    }

    override fun onResume() {
        super.onResume()
        val st = Store.state.value
        if (System.currentTimeMillis() / 1000 - st.lastRun > 10 * 60) RefreshWorker.runNow(this)
    }
}
