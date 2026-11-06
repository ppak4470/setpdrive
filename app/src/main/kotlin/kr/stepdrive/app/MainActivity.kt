package kr.stepdrive.app

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.os.Build
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.core.content.ContextCompat
import kr.stepdrive.app.ui.MainViewModel
import kr.stepdrive.app.ui.StepDriveUi
import kr.stepdrive.app.ui.theme.StepDriveTheme

class MainActivity : ComponentActivity() {
    private val vm: MainViewModel by viewModels { MainViewModel.factory((application as StepDriveApp).container) }

    private val permissionLauncher = registerForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
        (application as StepDriveApp).container.onPermissionsChanged()
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        android.util.Log.i("StepDrive", "MainActivity created (savedState=${savedInstanceState != null}, pid=${android.os.Process.myPid()})")
        requestRuntimePermissions()
        setContent { StepDriveTheme { StepDriveUi(vm) } }
    }

    override fun onResume() {
        super.onResume()
        (application as StepDriveApp).container.onPermissionsChanged()
        vm.onResume()
    }

    /**
     * Runtime permissions: microphone (voice commands), CAR_SPEED (Android Automotive), and the Pleos
     * location/speech permissions — the Pleos emulator reports them as not granted until the user allows them.
     */
    private fun requestRuntimePermissions() {
        val wanted = (listOf(
            Manifest.permission.RECORD_AUDIO,
            "android.car.permission.CAR_SPEED",
            "pleos.car.permission.FUSED_LOCATION",
            "pleos.car.permission.TTS_SERVICE",
            "pleos.car.permission.STT_SERVICE",
        ) + if (Build.VERSION.SDK_INT >= 33) listOf(Manifest.permission.POST_NOTIFICATIONS) else emptyList())
            .filter { ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED }
        if (wanted.isNotEmpty()) permissionLauncher.launch(wanted.toTypedArray())
    }
}
