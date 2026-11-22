package com.eightbee.app.features

import androidx.annotation.StringRes
import com.eightbee.app.R

/** An OEM-specific Doze/background exemption: the packages it whitelists and the strings that offer it. */
class BluetoothQuirk(
    val packages: List<String>,
    @StringRes val descriptionRes: Int,
    @StringRes val buttonRes: Int
)

/** Bluetooth repair profile. [repairPackages] are restarted or cleared after the stock Android Bluetooth stack. */
enum class BluetoothOem(val repairPackages: List<String>, val quirk: BluetoothQuirk? = null) {
    AOSP(emptyList()),
    SAMSUNG(listOf("com.samsung.android.bluetooth")),
    XIAOMI(
        listOf("com.miui.bluetooth", "com.xiaomi.bluetooth", "com.xiaomi.bluetooth.overlay"),
        BluetoothQuirk(
            listOf("com.android.bluetooth", "com.xiaomi.bluetooth", "com.miui.bluetooth"),
            R.string.bt_xiaomi_desc,
            R.string.bt_xiaomi_button
        )
    ),
    ONEPLUS(
        listOf("com.oplus.bluetooth", "com.coloros.bluetooth", "com.nearme.bluetooth"),
        BluetoothQuirk(
            listOf("com.android.bluetooth", "com.oplus.bluetooth", "com.coloros.bluetooth", "com.nearme.bluetooth"),
            R.string.bt_oneplus_desc,
            R.string.bt_oneplus_button
        )
    ),
    MOTOROLA(listOf("com.motorola.bluetooth", "com.motorola.bt"))
}
