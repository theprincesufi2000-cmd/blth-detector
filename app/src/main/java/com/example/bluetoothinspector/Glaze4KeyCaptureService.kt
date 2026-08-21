package com.example.bluetoothinspector

import android.accessibilityservice.AccessibilityService
import android.view.InputDevice
import android.view.KeyEvent
import android.view.accessibility.AccessibilityEvent

/**
 * System-wide HID key capture for glaze-4.
 *
 * Android exposes Bluetooth HID input to the normal input stack. An ordinary
 * Activity does not receive every system-consumed key (notably volume keys),
 * so this service requests key-event filtering. Returning false is important:
 * it lets Android continue handling the key normally (volume still changes).
 */
class Glaze4KeyCaptureService : AccessibilityService() {

    override fun onServiceConnected() {
        super.onServiceConnected()
        broadcastState(true)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // We only need the service for key events.
    }

    override fun onInterrupt() {
        broadcastState(false)
    }

    override fun onDestroy() {
        broadcastState(false)
        super.onDestroy()
    }

    override fun onKeyEvent(event: KeyEvent): Boolean {
        val prefs = getSharedPreferences(PREF_FILE, MODE_PRIVATE)
        if (!prefs.getBoolean(PREF_CAPTURE_ENABLED, false)) {
            return false
        }

        val deviceId = event.deviceId
        val input = try {
            if (deviceId >= 0) InputDevice.getDevice(deviceId) else null
        } catch (_: Exception) {
            null
        }

        val intent = android.content.Intent(ACTION_KEY_EVENT).apply {
            setPackage(packageName)
            putExtra(EXTRA_DEVICE_NAME, input?.name ?: "unknown")
            putExtra(EXTRA_DEVICE_ID, deviceId)
            putExtra(EXTRA_KEY_CODE, event.keyCode)
            putExtra(EXTRA_ACTION, actionName(event.action))
            putExtra(EXTRA_SOURCE, event.source)
            putExtra(EXTRA_SCAN_CODE, event.scanCode)
            putExtra(EXTRA_FLAGS, event.flags)
            putExtra(EXTRA_REPEAT, event.repeatCount)
            putExtra(EXTRA_EVENT_TIME, event.eventTime)
        }

        try {
            sendBroadcast(intent)
        } catch (_: Exception) {
            // Never interfere with the system input pipeline because logging failed.
        }

        // Do not consume the key. Android keeps normal HID behavior, including volume.
        return false
    }

    private fun actionName(action: Int): String = when (action) {
        KeyEvent.ACTION_DOWN -> "DOWN"
        KeyEvent.ACTION_UP -> "UP"
        KeyEvent.ACTION_MULTIPLE -> "MULTIPLE"
        else -> action.toString()
    }

    private fun broadcastState(connected: Boolean) {
        try {
            sendBroadcast(
                android.content.Intent(ACTION_SERVICE_STATE).apply {
                    setPackage(packageName)
                    putExtra(EXTRA_SERVICE_CONNECTED, connected)
                }
            )
        } catch (_: Exception) {
        }
    }

    companion object {
        const val ACTION_KEY_EVENT = "com.example.bluetoothinspector.HID_KEY_EVENT"
        const val ACTION_SERVICE_STATE = "com.example.bluetoothinspector.HID_SERVICE_STATE"
        const val PREF_FILE = "capture"
        const val PREF_CAPTURE_ENABLED = "capture_enabled"

        const val EXTRA_DEVICE_NAME = "device_name"
        const val EXTRA_DEVICE_ID = "device_id"
        const val EXTRA_KEY_CODE = "key_code"
        const val EXTRA_ACTION = "action"
        const val EXTRA_SOURCE = "source"
        const val EXTRA_SCAN_CODE = "scan_code"
        const val EXTRA_FLAGS = "flags"
        const val EXTRA_REPEAT = "repeat"
        const val EXTRA_EVENT_TIME = "event_time"
        const val EXTRA_SERVICE_CONNECTED = "service_connected"
    }
}
