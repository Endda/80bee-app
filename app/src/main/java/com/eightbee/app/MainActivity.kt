package com.eightbee.app

import android.app.PendingIntent
import android.util.Log
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.eightbee.app.connection.OtgConnectionManager
import androidx.compose.foundation.layout.Box
import com.eightbee.app.ui.DeviceDetectionScreen
import com.eightbee.app.ui.MainScaffold
import com.eightbee.app.ui.BootloaderWizardBottomSheet
import com.eightbee.app.ui.PixelMagiskPatcherScreen
import com.eightbee.app.ui.theme.EightBeeAppTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var usbManager: UsbManager

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device = intent.usbDeviceExtra()

            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                        device?.let { activateOtgDevice(it) }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    device?.let { handleUsbDevice(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    device?.let {
                        if (viewModel.otgManager.onDeviceDetached(it) &&
                            viewModel.activeConnection.value === viewModel.otgManager
                        ) {
                            viewModel.setActiveConnection(null)
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager

        registerUsbReceiver()
        checkInitialUsbDevices()

        setContent {
            EightBeeAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val activeConnection by viewModel.activeConnection.collectAsState()
                    val bootloaderWizardState by viewModel.bootloaderWizardController.state.collectAsState()
                    val patcherState by viewModel.magiskPatcherController.state.collectAsState()

                    Box(modifier = Modifier.fillMaxSize()) {
                        if (patcherState.isActive) {
                            PixelMagiskPatcherScreen(
                                controller = viewModel.magiskPatcherController,
                                onDismiss = {
                                    viewModel.magiskPatcherController.clearPatcherState()
                                }
                            )
                        } else if (activeConnection == null) {
                            DeviceDetectionScreen(
                                shizukuManager = viewModel.shizukuManager,
                                otgManager = viewModel.otgManager,
                                onRequestShizukuPermission = {
                                    viewModel.shizukuManager.requestPermission(SHIZUKU_REQUEST_CODE)
                                },
                                onConnect = { connection ->
                                    viewModel.setActiveConnection(connection)
                                }
                            )
                        } else {
                            MainScaffold(
                                viewModel = viewModel,
                                onDisconnect = {
                                    viewModel.setActiveConnection(null)
                                }
                            )
                        }

                        if (bootloaderWizardState !is BootloaderWizardState.Idle) {
                            BootloaderWizardBottomSheet(
                                controller = viewModel.bootloaderWizardController,
                                activeConnection = activeConnection,
                                onDismiss = {
                                    viewModel.bootloaderWizardController.clear()
                                }
                            )
                        }
                    }
                }
            }
        }
    }

    private fun registerUsbReceiver() {
        val filter = IntentFilter(ACTION_USB_PERMISSION).apply {
            addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
            addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
        }
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_NOT_EXPORTED else 0
        registerReceiver(usbReceiver, filter, flags)
    }

    private fun checkInitialUsbDevices() {
        val deviceList = usbManager.deviceList
        for (device in deviceList.values) {
            handleUsbDevice(device)
        }
    }

    private fun activateOtgDevice(device: UsbDevice) {
        val attached = viewModel.otgManager.setDevice(device)
        if (attached && viewModel.activeConnection.value === viewModel.otgManager) {
            viewModel.deviceTweaksController.refreshSettings()
        }
        if (viewModel.activeConnection.value !== viewModel.otgManager &&
            (viewModel.bootloaderWizardController.state.value !is BootloaderWizardState.Idle ||
                viewModel.magiskPatcherController.state.value.isActive)
        ) {
            viewModel.setActiveConnection(viewModel.otgManager)
        }
    }

    private fun handleUsbDevice(device: UsbDevice) {
        Log.d("MainActivity", "handleUsbDevice: Inspecting device: Vendor=${device.vendorId}, Product=${device.productId}")
        if (OtgConnectionManager.isAdbOrFastbootDevice(device)) {
            Log.d("MainActivity", "  Device matches ADB/Fastboot interface! Has permission: ${usbManager.hasPermission(device)}")
            if (usbManager.hasPermission(device)) {
                activateOtgDevice(device)
            } else {
                Log.d("MainActivity", "  Requesting USB permission...")
                val intent = Intent(ACTION_USB_PERMISSION).apply {
                    setPackage(packageName)
                }
                // UsbManager populates EXTRA_DEVICE and EXTRA_PERMISSION_GRANTED via fillInIntent,
                // which requires FLAG_MUTABLE on Android 12+; the explicit package keeps it safe on 14+.
                val pendingIntentFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val permissionIntent = PendingIntent.getBroadcast(
                    this, 0, intent, pendingIntentFlags
                )
                usbManager.requestPermission(device, permissionIntent)
            }
        } else {
            Log.d("MainActivity", "  Device does NOT match ADB/Fastboot interfaces.")
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (UsbManager.ACTION_USB_DEVICE_ATTACHED == intent.action) {
            intent.usbDeviceExtra()?.let { handleUsbDevice(it) }
        }
    }

    private fun Intent.usbDeviceExtra(): UsbDevice? =
        IntentCompat.getParcelableExtra(this, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)

    override fun onResume() {
        super.onResume()
        viewModel.shizukuManager.refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
    }

    companion object {
        private const val SHIZUKU_REQUEST_CODE = 1001
        private const val ACTION_USB_PERMISSION = "com.eightbee.app.USB_PERMISSION"
    }
}
