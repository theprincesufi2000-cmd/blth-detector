package com.example.bluetoothinspector

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.UUID

class MainActivity : Activity() {

    private val bluetoothManager by lazy {
        getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
    }

    private val adapter: BluetoothAdapter?
        get() = bluetoothManager.adapter

    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private var connectedDevice: BluetoothDevice? = null

    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var deviceName: TextView
    private lateinit var receiveButton: Button
    private lateinit var log: TextView
    private lateinit var devicesContainer: LinearLayout

    // IMPORTANT:
    // Nothing is captured until the user presses "استقبال الأوامر".
    private var receiving = false
    private var eventNumber = 0
    private var lastPacket: ByteArray? = null
    private var idlePacket: ByteArray? = null

    private val handler = Handler(Looper.getMainLooper())
    private val permissionRequest = 1001

    private val hidServiceUuid =
        UUID.fromString("00001812-0000-1000-8000-00805F9B34FB")

    private val hidInputReportUuid =
        UUID.fromString("00002A4D-0000-1000-8000-00805F9B34FB")

    private val cccdUuid =
        UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    private val shownAddresses = mutableSetOf<String>()

    private val scanCallback = object : ScanCallback() {

        override fun onScanResult(
            callbackType: Int,
            result: ScanResult
        ) {
            runOnUiThread {
                addDeviceIfNeeded(result.device)
            }
        }

        override fun onBatchScanResults(
            results: MutableList<ScanResult>
        ) {
            runOnUiThread {
                results.forEach { addDeviceIfNeeded(it.device) }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {
                scanning = false
                status.text = "فشل البحث"
            }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            g: BluetoothGatt,
            statusCode: Int,
            newState: Int
        ) {
            runOnUiThread {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    connectedDevice = g.device
                    status.text = "متصل"
                    deviceName.text = safeName(g.device)

                    receiveButton.isEnabled = false
                    receiving = false
                    eventNumber = 0
                    lastPacket = null
                    idlePacket = null

                    log.text =
                        "اضغط «استقبال الأوامر» ثم اضغط أي زر في الجهاز الأصلي."

                    try {
                        g.discoverServices()
                    } catch (_: SecurityException) {
                        status.text = "صلاحية البلوتوث مرفوضة"
                    }

                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    receiving = false
                    receiveButton.isEnabled = false
                    receiveButton.text = "استقبال الأوامر"
                    status.text = "غير متصل"
                    log.text = "تم قطع الاتصال."

                    try {
                        g.close()
                    } catch (_: Exception) {
                    }

                    if (gatt === g) {
                        gatt = null
                    }
                }
            }
        }

        override fun onServicesDiscovered(
            g: BluetoothGatt,
            statusCode: Int
        ) {
            runOnUiThread {
                if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                    status.text = "فشل الاتصال بخدمات الجهاز"
                    log.text = "لم يتمكن Android من قراءة خدمات الجهاز."
                    return@runOnUiThread
                }

                val input = findHidInputReport(g)

                if (input == null) {
                    status.text = "متصل — لا توجد قناة HID للأزرار"
                    log.text =
                        "لم يتم العثور على HID Input Report (2A4D)."
                    receiveButton.isEnabled = false
                } else {
                    status.text = "متصل — جاهز"
                    log.text =
                        "الجهاز متصل.\n\nاضغط «استقبال الأوامر» للبدء."
                    receiveButton.isEnabled = true
                }
            }
        }

        // Android 13+ callback.
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleInputReport(characteristic, value)
        }

        // Older Android callback.
        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleInputReport(
                characteristic,
                characteristic.value ?: ByteArray(0)
            )
        }

        override fun onDescriptorWrite(
            g: BluetoothGatt,
            descriptor: BluetoothGattDescriptor,
            statusCode: Int
        ) {
            if (descriptor.uuid != cccdUuid) return

            runOnUiThread {
                if (statusCode == BluetoothGatt.GATT_SUCCESS) {
                    receiving = true
                    status.text = "استقبال الأوامر: ON"
                    log.text =
                        "جاهز.\n\nاضغط أي زر في الجهاز الأصلي الآن.\n" +
                        "ستظهر البيانات الأصلية فقط."
                } else {
                    receiving = false
                    status.text = "تعذر تشغيل استقبال الأوامر"
                    log.text =
                        "لم يتمكن الجهاز من تفعيل استقبال HID."
                }

                receiveButton.text =
                    if (receiving) {
                        "إيقاف استقبال الأوامر"
                    } else {
                        "استقبال الأوامر"
                    }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        buildUi()
        requestBluetoothPermissions()
        showPairedDevices()
    }

    override fun onDestroy() {
        stopScan()

        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }

        gatt = null
        super.onDestroy()
    }

    private fun buildUi() {

        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF090D12.toInt())
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }

        val title = text(
            "Bluetooth Command Capture",
            25f,
            true
        )
        root.addView(title, lp(-1, -2))

        status = text(
            "اختر الجهاز المقترن",
            15f,
            false
        ).apply {
            setTextColor(0xFF6EE78A.toInt())
            setPadding(
                dp(14),
                dp(14),
                dp(14),
                dp(14)
            )
            setBackgroundColor(0xFF121A24.toInt())
        }

        root.addView(
            status,
            marginLp(-1, -2, 0, 10, 0, 10)
        )

        deviceName = text(
            "لا يوجد جهاز متصل",
            20f,
            true
        )

        root.addView(
            deviceName,
            marginLp(-1, -2, 0, 8, 0, 8)
        )

        val scan = Button(this).apply {
            text = "البحث عن الجهاز"
            setOnClickListener {
                startScan()
            }
        }

        root.addView(
            scan,
            marginLp(-1, -2, 0, 6, 0, 6)
        )

        receiveButton = Button(this).apply {
            text = "استقبال الأوامر"
            isEnabled = false

            setOnClickListener {
                if (receiving) {
                    stopReceiving()
                } else {
                    startReceiving()
                }
            }
        }

        root.addView(
            receiveButton,
            marginLp(-1, -2, 0, 6, 0, 12)
        )

        val deviceTitle = text(
            "الأجهزة المقترنة",
            17f,
            true
        )

        root.addView(
            deviceTitle,
            lp(-1, -2)
        )

        devicesContainer = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
        }

        val deviceScroll = ScrollView(this).apply {
            addView(devicesContainer)
        }

        root.addView(
            deviceScroll,
            LinearLayout.LayoutParams(-1, dp(150))
        )

        val commandTitle = text(
            "الأمر المستلم",
            17f,
            true
        )

        root.addView(
            commandTitle,
            marginLp(-1, -2, 0, 12, 0, 6)
        )

        log = text(
            "بعد الاتصال اضغط «استقبال الأوامر» ثم اضغط زرًا في الجهاز.",
            16f,
            false
        ).apply {
            typeface = android.graphics.Typeface.MONOSPACE
            setTextColor(0xFFE8EDF3.toInt())
            setPadding(
                dp(14),
                dp(14),
                dp(14),
                dp(14)
            )
            setBackgroundColor(0xFF111820.toInt())
        }

        val logScroll = ScrollView(this).apply {
            addView(log)
        }

        root.addView(
            logScroll,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        setContentView(root)
    }

    private fun showPairedDevices() {

        devicesContainer.removeAllViews()
        shownAddresses.clear()

        val a = adapter

        if (a == null) {
            status.text = "البلوتوث غير متوفر"
            return
        }

        val devices = try {
            a.bondedDevices.toList()
        } catch (_: SecurityException) {
            emptyList()
        }

        if (devices.isEmpty()) {
            devicesContainer.addView(
                text(
                    "لا يوجد جهاز مقترن. اقترن بالجهاز من إعدادات Bluetooth أولاً.",
                    14f,
                    false
                )
            )
            return
        }

        devices
            .sortedBy { safeName(it) }
            .forEach {
                addDeviceIfNeeded(it)
            }
    }

    private fun addDeviceIfNeeded(
        device: BluetoothDevice
    ) {
        val address = try {
            device.address
        } catch (_: SecurityException) {
            return
        }

        if (!shownAddresses.add(address)) return

        val button = Button(this).apply {
            text = safeName(device)
            gravity = Gravity.CENTER_VERTICAL

            setOnClickListener {
                connectToDevice(device)
            }
        }

        devicesContainer.addView(
            button,
            marginLp(-1, -2, 0, 4, 0, 4)
        )
    }

    private fun connectToDevice(
        device: BluetoothDevice
    ) {

        stopScan()

        if (!hasConnectPermission()) {
            requestBluetoothPermissions()
            return
        }

        try {
            gatt?.disconnect()
            gatt?.close()
            gatt = null

            receiving = false
            eventNumber = 0
            lastPacket = null
            idlePacket = null

            receiveButton.isEnabled = false
            receiveButton.text = "استقبال الأوامر"

            status.text = "جارٍ الاتصال…"
            deviceName.text = safeName(device)
            log.text = "جارٍ الاتصال بالجهاز…"

            gatt =
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    device.connectGatt(
                        this,
                        false,
                        gattCallback,
                        BluetoothDevice.TRANSPORT_LE
                    )
                } else {
                    @Suppress("DEPRECATION")
                    device.connectGatt(
                        this,
                        false,
                        gattCallback
                    )
                }

        } catch (_: Exception) {
            status.text = "فشل الاتصال"
            log.text = "تعذر الاتصال بالجهاز."
        }
    }

    private fun startReceiving() {

        if (!hasConnectPermission()) {
            requestBluetoothPermissions()
            return
        }

        val g = gatt ?: return
        val input = findHidInputReport(g)

        if (input == null) {
            status.text = "لا توجد قناة أزرار HID"
            return
        }

        try {
            // This is the ONLY notification we enable.
            // No custom notification channels are subscribed.
            val local =
                g.setCharacteristicNotification(
                    input,
                    true
                )

            if (!local) {
                status.text = "تعذر تفعيل استقبال الأوامر"
                return
            }

            val descriptor =
                input.getDescriptor(cccdUuid)

            if (descriptor == null) {
                status.text = "قناة HID لا تحتوي CCCD"
                return
            }

            descriptor.value =
                BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE

            val started =
                g.writeDescriptor(descriptor)

            if (!started) {
                status.text = "تعذر بدء استقبال الأوامر"
            }

        } catch (_: Exception) {
            status.text = "تعذر بدء استقبال الأوامر"
        }
    }

    private fun stopReceiving() {

        if (!hasConnectPermission()) {
            requestBluetoothPermissions()
            return
        }

        val g = gatt
        val input =
            g?.let {
                findHidInputReport(it)
            }

        if (g != null && input != null) {
            try {
                g.setCharacteristicNotification(
                    input,
                    false
                )

                input.getDescriptor(cccdUuid)?.let {
                    it.value =
                        BluetoothGattDescriptor
                            .DISABLE_NOTIFICATION_VALUE

                    g.writeDescriptor(it)
                }

            } catch (_: Exception) {
            }
        }

        receiving = false
        receiveButton.text = "استقبال الأوامر"
        status.text = "الاستقبال متوقف"
        log.text =
            "اضغط «استقبال الأوامر» عندما تريد التقاط زر جديد."
    }

    private fun findHidInputReport(
        g: BluetoothGatt
    ): BluetoothGattCharacteristic? {

        val hidService =
            g.getService(hidServiceUuid)
                ?: g.services.firstOrNull {
                    it.uuid == hidServiceUuid
                }

        return hidService
            ?.getCharacteristic(hidInputReportUuid)
            ?: g.services
                .flatMap {
                    it.characteristics
                }
                .firstOrNull {
                    it.uuid == hidInputReportUuid
                }
    }

    private fun handleInputReport(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {

        // Nothing is recorded before the button is pressed.
        if (!receiving) return

        // Absolute filter: only HID Input Report.
        if (characteristic.uuid != hidInputReportUuid) {
            return
        }

        if (value.isEmpty()) return

        runOnUiThread {

            // HID devices often send the same idle report continuously.
            // Do not show those repeats as button commands.
            if (lastPacket != null && lastPacket!!.contentEquals(value)) {
                return@runOnUiThread
            }

            if (idlePacket == null) {
                idlePacket = value.clone()
                lastPacket = value.clone()
                return@runOnUiThread
            }

            // Returning to the idle report is a release, not a new button command.
            if (idlePacket!!.contentEquals(value)) {
                lastPacket = value.clone()
                return@runOnUiThread
            }

            eventNumber++

            val hex =
                value.joinToString(" ") {
                    "%02X".format(
                        it.toInt() and 0xFF
                    )
                }

            val previous = lastPacket

            val changed =
                if (previous == null) {
                    "أول تقرير"
                } else {
                    value.indices
                        .filter { index ->
                            index >= previous.size ||
                                value[index] != previous[index]
                        }
                        .joinToString(", ") {
                            (it + 1).toString()
                        }
                        .ifEmpty {
                            "لا يوجد تغيير"
                        }
                }

            lastPacket = value.clone()

            status.text =
                "تم استقبال الأمر #$eventNumber"

            log.text =
                """
                الأمر #$eventNumber

                HEX الأصلي:
                $hex

                البايتات المتغيرة:
                $changed

                اضغط زرًا آخر لتسجيله.
                """.trimIndent()
        }
    }

    private fun startScan() {

        if (!hasScanPermission()) {
            requestBluetoothPermissions()
            return
        }

        val a = adapter ?: return

        val enabled =
            try {
                a.isEnabled
            } catch (_: SecurityException) {
                false
            }

        if (!enabled) {
            try {
                startActivity(
                    Intent(
                        BluetoothAdapter.ACTION_REQUEST_ENABLE
                    )
                )
            } catch (_: Exception) {
            }
            return
        }

        if (scanning) return

        shownAddresses.clear()
        devicesContainer.removeAllViews()
        status.text = "جارٍ البحث…"

        scanner =
            try {
                a.bluetoothLeScanner
            } catch (_: SecurityException) {
                null
            }

        if (scanner == null) {
            status.text = "ماسح BLE غير متوفر"
            return
        }

        scanning = true

        try {
            scanner?.startScan(
                null,
                ScanSettings.Builder()
                    .setScanMode(
                        ScanSettings.SCAN_MODE_LOW_LATENCY
                    )
                    .build(),
                scanCallback
            )

            handler.postDelayed(
                {
                    stopScan()
                },
                12000
            )

        } catch (_: Exception) {
            scanning = false
            status.text = "فشل البحث"
        }
    }

    private fun stopScan() {

        handler.removeCallbacksAndMessages(null)

        if (!scanning) return

        try {
            scanner?.stopScan(scanCallback)
        } catch (_: Exception) {
        }

        scanning = false

        status.text =
            if (connectedDevice != null) {
                "متصل"
            } else {
                "البحث متوقف"
            }
    }

    private fun requestBluetoothPermissions() {

        val permissions =
            mutableListOf<String>()

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            permissions +=
                Manifest.permission.BLUETOOTH_SCAN

            permissions +=
                Manifest.permission.BLUETOOTH_CONNECT
        } else {
            permissions +=
                Manifest.permission.ACCESS_FINE_LOCATION
        }

        val needed =
            permissions.filter {
                checkSelfPermission(it) !=
                    PackageManager.PERMISSION_GRANTED
            }

        if (needed.isNotEmpty()) {
            requestPermissions(
                needed.toTypedArray(),
                permissionRequest
            )
        }
    }

    private fun hasConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT <
            Build.VERSION_CODES.S ||
            checkSelfPermission(
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun hasScanPermission(): Boolean {
        return Build.VERSION.SDK_INT <
            Build.VERSION_CODES.S ||
            checkSelfPermission(
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED
    }

    private fun safeName(
        device: BluetoothDevice
    ): String {
        return try {
            device.name
                ?.takeIf { it.isNotBlank() }
                ?: "جهاز Bluetooth"
        } catch (_: SecurityException) {
            "جهاز Bluetooth"
        }
    }

    private fun text(
        value: String,
        size: Float,
        bold: Boolean
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(0xFFE8EDF3.toInt())

            if (bold) {
                typeface =
                    android.graphics.Typeface.DEFAULT_BOLD
            }
        }
    }

    private fun lp(
        width: Int,
        height: Int
    ): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            width,
            height
        )
    }

    private fun marginLp(
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ): LinearLayout.LayoutParams {
        return LinearLayout.LayoutParams(
            width,
            height
        ).apply {
            setMargins(
                dp(left),
                dp(top),
                dp(right),
                dp(bottom)
            )
        }
    }

    private fun dp(value: Int): Int {
        return (
            value *
                resources.displayMetrics.density
            ).toInt()
    }
}
