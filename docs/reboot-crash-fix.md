# Objective
Harden the OTG connection logic to prevent crashes during abrupt device disconnections (like reboots) and ensure the connection lifecycle is properly managed. This update also prepares the architecture for seamless ADB-to-Fastboot transitions.

# Key Files & Context
- `adblib/src/main/java/com/cgutman/adblib/AdbConnection.java`
- `adblib/src/main/java/com/cgutman/adblib/UsbChannel.java`
- `adblib/src/main/java/com/cgutman/adblib/AdbStream.java`
- `app/src/main/java/com/eightbee/app/connection/OtgConnectionManager.kt`

# Implementation Steps

1. **Modernize `AdbConnection.java` Thread Safety:**
   - Change `openStreams` from `HashMap` to `ConcurrentHashMap` to safely allow simultaneous cleanup and stream operations.
   - Ensure `connected` is set to `false` and `notifyAll()` is called in the `finally` block of the connection thread to prevent hanging waiters.

2. **Improve `UsbChannel.java` Cleanup:**
   - Ensure `mDeviceConnection.releaseInterface(mInterface)` and `mDeviceConnection.close()` are called reliably in the `close()` method.
   - Add error handling to `writex` to catch "Broken Pipe" or "Device not found" IOExceptions during reboots.

3. **Refine `OtgConnectionManager.kt` Lifecycle & Fastboot Readiness:**
   - Introduce a `ConnectionState` sealed class: `Disconnected`, `Connecting`, `AdbConnected`, `FastbootConnected`.
   - Use a `Job?` to track the connection process and cancel it in `disconnect()`.
   - Implement "Local Capture" pattern: `val currentConn = adbConnection` in `runShellCommand` to prevent NPEs during disconnects.
   - Update `connectDevice()` to detect the Fastboot protocol (Class 0xFF, Subclass 0x42, Protocol 0x03) and transition state accordingly.
   - Ensure `UsbInterface.release()` is called via the manager's disconnect logic to let the OS reclaim the port.

4. **Harden `AdbStream.java`:**
   - Add try-catch around `writex` in `close()` to ensure the stream state is always updated locally even if the physical link is gone.

# Verification & Testing
- Build and run the app.
- Issue a "reboot bootloader" command.
- Verify the app handles the ADB disconnection gracefully without crashing.
- Verify the app detects the new "Fastboot" device identity (if productId/descriptors change).
- Verify that the connection thread terminates (no memory leaks).
- Verify that reconnecting works immediately after the device reaches the bootloader.