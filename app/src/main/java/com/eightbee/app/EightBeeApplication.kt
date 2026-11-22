package com.eightbee.app

import android.app.Application
import com.eightbee.app.features.BackgroundOperationCoordinator

class EightBeeApplication : Application() {
    val backgroundOperations = BackgroundOperationCoordinator()
}
