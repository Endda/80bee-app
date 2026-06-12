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
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.eightbee.app.connection.OtgConnectionManager
import com.eightbee.app.connection.ShizukuConnectionManager
import androidx.compose.foundation.layout.Box
import com.eightbee.app.ui.DeviceDetectionScreen
import com.eightbee.app.ui.MainScaffold
import com.eightbee.app.ui.BootloaderWizardBottomSheet
import com.eightbee.app.ui.theme.EightBeeAppTheme

class MainActivity : ComponentActivity() {

    private val viewModel: MainViewModel by viewModels()
    private lateinit var shizukuManager: ShizukuConnectionManager
    private lateinit var otgManager: OtgConnectionManager
    private lateinit var usbManager: UsbManager

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }

            when (intent.action) {
                ACTION_USB_PERMISSION -> {
                    synchronized(this) {
                        if (intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)) {
                            device?.let {
                                otgManager.setDevice(it)
                                if (viewModel.activeConnection.value is OtgConnectionManager) {
                                    viewModel.refreshSettings()
                                }
                                if (viewModel.bootloaderWizardState.value !is BootloaderWizardState.Idle) {
                                    viewModel.setActiveConnection(otgManager)
                                }
                            }
                        }
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    device?.let { handleUsbDevice(it) }
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    device?.let {
                        otgManager.setDevice(null)
                        if (viewModel.activeConnection.value is OtgConnectionManager) {
                            viewModel.setActiveConnection(null)
                        }
                    }
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        shizukuManager = ShizukuConnectionManager()
        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        otgManager = OtgConnectionManager(this, usbManager)

        registerUsbReceiver()
        checkInitialUsbDevices()

        setContent {
            EightBeeAppTheme {
                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = MaterialTheme.colorScheme.background
                ) {
                    val activeConnection by viewModel.activeConnection.collectAsState()
                    val bootloaderWizardState by viewModel.bootloaderWizardState.collectAsState()

                    Box(modifier = Modifier.fillMaxSize()) {
                        if (activeConnection == null) {
                            DeviceDetectionScreen(
                                shizukuManager = shizukuManager,
                                otgManager = otgManager,
                                onRequestShizukuPermission = {
                                    shizukuManager.requestPermission(SHIZUKU_REQUEST_CODE)
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
                                viewModel = viewModel,
                                onDismiss = {
                                    viewModel.clearBootloaderWizardState()
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
        val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) RECEIVER_EXPORTED else 0
        registerReceiver(usbReceiver, filter, flags)
    }

    private fun checkInitialUsbDevices() {
        val deviceList = usbManager.deviceList
        for (device in deviceList.values) {
            handleUsbDevice(device)
        }
    }

    private fun handleUsbDevice(device: UsbDevice) {
        Log.d("MainActivity", "handleUsbDevice: Inspecting device: Vendor=${device.vendorId}, Product=${device.productId}")
        // Quick check to see if it might be an ADB or Fastboot device
        var isAdbOrFastboot = false
        for (i in 0 until device.interfaceCount) {
            val intf = device.getInterface(i)
            Log.d("MainActivity", "  Interface $i: class=${intf.interfaceClass}, subclass=${intf.interfaceSubclass}, protocol=${intf.interfaceProtocol}")
            if (intf.interfaceClass == 255 && intf.interfaceSubclass == 66 && 
                (intf.interfaceProtocol == 1 || intf.interfaceProtocol == 3)) {
                isAdbOrFastboot = true
                break
            }
        }

        if (isAdbOrFastboot) {
            Log.d("MainActivity", "  Device matches ADB/Fastboot interface! Has permission: ${usbManager.hasPermission(device)}")
            if (usbManager.hasPermission(device)) {
                otgManager.setDevice(device)
                if (viewModel.activeConnection.value is OtgConnectionManager) {
                    viewModel.refreshSettings()
                }
                if (viewModel.bootloaderWizardState.value !is BootloaderWizardState.Idle) {
                    viewModel.setActiveConnection(otgManager)
                }
            } else {
                Log.d("MainActivity", "  Requesting USB permission...")
                val intent = Intent(ACTION_USB_PERMISSION).apply {
                    setPackage(packageName)
                }
                val permissionIntent = PendingIntent.getBroadcast(
                    this, 0, intent,
                    PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
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
            val device: UsbDevice? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            }
            device?.let { handleUsbDevice(it) }
        }
    }

    override fun onResume() {
        super.onResume()
        shizukuManager.refresh()
    }

    override fun onDestroy() {
        super.onDestroy()
        unregisterReceiver(usbReceiver)
        otgManager.disconnect()
    }

    companion object {
        private const val SHIZUKU_REQUEST_CODE = 1001
        private const val ACTION_USB_PERMISSION = "com.eightbee.app.USB_PERMISSION"
    }
}
