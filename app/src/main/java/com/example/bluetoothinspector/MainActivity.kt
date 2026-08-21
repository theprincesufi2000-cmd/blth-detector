package com.example.bluetoothinspector

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothStatusCodes
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothGattService
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.bluetooth.le.BluetoothLeScanner
import android.bluetooth.le.ScanCallback
import android.bluetooth.le.ScanResult
import android.bluetooth.le.ScanSettings
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.os.ParcelUuid
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.nio.charset.Charset
import java.util.UUID
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var results: LinearLayout
    private lateinit var refreshButton: Button
    private lateinit var scanButton: Button

    private val btManager by lazy {
        getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
    }

    private val adapter: BluetoothAdapter?
        get() = btManager.adapter

    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var gatt: BluetoothGatt? = null

    // Command capture: only traffic that can represent a device command/report.
    private var commandCaptureEnabled = true
    private var commandCount = 0
    private var lastReportByUuid = mutableMapOf<UUID, ByteArray>()
    private val commandCharacteristics = mutableSetOf<UUID>()
    private val commandLog = mutableListOf<String>()

    private val pendingCommandNotifications =
        mutableListOf<Pair<BluetoothGattCharacteristic, Boolean>>()
    private var commandNotificationWriteInProgress = false

    private val HID_SERVICE_UUID =
        UUID.fromString("00001812-0000-1000-8000-00805F9B34FB")
    private val HID_INPUT_REPORT_UUID =
        UUID.fromString("00002A4D-0000-1000-8000-00805F9B34FB")
    private val HID_REPORT_MAP_UUID =
        UUID.fromString("00002A4B-0000-1000-8000-00805F9B34FB")
    private val BATTERY_LEVEL_UUID =
        UUID.fromString("00002A19-0000-1000-8000-00805F9B34FB")
    private val GENERIC_ATTRIBUTE_CHANGED_UUID =
        UUID.fromString("00002A05-0000-1000-8000-00805F9B34FB")

    private val seen = linkedMapOf<String, ScanResult>()
    private val handler = Handler(Looper.getMainLooper())

    private val permissionRequest = 9001

    private val BASE_UUID =
        "-0000-1000-8000-00805F9B34FB"

    private val CCCD_UUID =
        UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    // ---------------------------------------------------------
    // CLASSIC SDP
    // ---------------------------------------------------------

    private val uuidReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context?, intent: Intent?) {

            if (intent?.action != BluetoothDevice.ACTION_UUID) return

            val device =
                intent.getParcelableExtraCompat<BluetoothDevice>(
                    BluetoothDevice.EXTRA_DEVICE
                ) ?: return

            val uuids = try {
                device.uuids
                    ?.map { it.uuid }
                    ?.distinct()
                    ?: emptyList()
            } catch (_: SecurityException) {
                emptyList()
            }

            runOnUiThread {

                status.text =
                    "SDP discovery completed • ${safeName(device)} • ${uuids.size} UUIDs"

                renderSdpResult(device, uuids)
            }
        }
    }

    // ---------------------------------------------------------
    // BLE SCAN
    // ---------------------------------------------------------

    private val scanCallback = object : ScanCallback() {

        override fun onScanResult(
            callbackType: Int,
            result: ScanResult
        ) {
            runOnUiThread {

                seen[result.device.address] = result
                renderScanResults()
            }
        }

        override fun onBatchScanResults(
            results: MutableList<ScanResult>
        ) {
            runOnUiThread {

                results.forEach {
                    seen[it.device.address] = it
                }

                renderScanResults()
            }
        }

        override fun onScanFailed(errorCode: Int) {

            runOnUiThread {
                scanning = false
                status.text = "BLE scan failed • error=$errorCode"
            }
        }
    }

    // ---------------------------------------------------------
    // LIFECYCLE
    // ---------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = getColor(R.color.bg)
        window.navigationBarColor = getColor(R.color.bg)

        buildUi()

        registerReceiver(
            uuidReceiver,
            IntentFilter(BluetoothDevice.ACTION_UUID),
            RECEIVER_NOT_EXPORTED
        )

        requestBluetoothPermissions()

        refreshLocalInfo()
    }

    override fun onDestroy() {

        stopBleScan()

        try {
            unregisterReceiver(uuidReceiver)
        } catch (_: Exception) {
        }

        gatt?.close()
        gatt = null

        super.onDestroy()
    }

    // ---------------------------------------------------------
    // UI
    // ---------------------------------------------------------

    private fun buildUi() {

        root = LinearLayout(this).apply {

            orientation = LinearLayout.VERTICAL

            setBackgroundColor(
                getColor(R.color.bg)
            )

            setPadding(
                dp(16),
                dp(12),
                dp(16),
                dp(16)
            )
        }

        val title = TextView(this).apply {

            text = "Bluetooth Inspector Pro"

            textSize = 25f

            setTextColor(
                getColor(R.color.text)
            )

            typeface =
                android.graphics.Typeface.DEFAULT_BOLD
        }

        root.addView(
            title,
            lp(-1, -2)
        )

        val subtitle = TextView(this).apply {

            text =
                "Command Capture • HID Reports • Custom Bluetooth Commands"

            textSize = 13f

            setTextColor(
                getColor(R.color.muted)
            )

            setPadding(
                0,
                dp(4),
                0,
                dp(10)
            )
        }

        root.addView(
            subtitle,
            lp(-1, -2)
        )

        status = TextView(this).apply {

            textSize = 14f

            setTextColor(
                getColor(R.color.green)
            )

            setPadding(
                dp(12),
                dp(10),
                dp(12),
                dp(10)
            )

            setBackgroundColor(
                getColor(R.color.panel)
            )
        }

        root.addView(
            status,
            lp(-1, -2)
        )

        val buttons =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }

        refreshButton = button("Refresh")
        scanButton = button("Scan BLE")

        val stopButton = button("Stop")
        val pairedButton = button("Paired")

        buttons.addView(
            refreshButton,
            weightLp()
        )

        buttons.addView(
            scanButton,
            weightLp()
        )

        buttons.addView(
            stopButton,
            weightLp()
        )

        buttons.addView(
            pairedButton,
            weightLp()
        )

        root.addView(
            buttons,
            lp(-1, -2)
        )

        refreshButton.setOnClickListener {
            refreshLocalInfo()
        }

        scanButton.setOnClickListener {
            startBleScan()
        }

        stopButton.setOnClickListener {
            stopBleScan()
        }

        pairedButton.setOnClickListener {
            renderPaired()
        }

        // The inspector is intentionally command-focused after a GATT connection.
        // No GATT database is shown until the user asks for it from the connection.

        val scroll =
            ScrollView(this)

        results =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    0,
                    dp(12),
                    0,
                    dp(30)
                )
            }

        scroll.addView(results)

        root.addView(
            scroll,
            LinearLayout.LayoutParams(
                -1,
                0,
                1f
            )
        )

        setContentView(root)
    }

    // ---------------------------------------------------------
    // LOCAL ADAPTER
    // ---------------------------------------------------------

    private fun refreshLocalInfo() {

        results.removeAllViews()

        val a = adapter

        if (a == null) {

            status.text =
                "Bluetooth adapter not available"

            return
        }

        val enabled =
            try {
                a.isEnabled
            } catch (_: SecurityException) {
                false
            }

        status.text =
            "Bluetooth: ${if (enabled) "ON" else "OFF"} • Android ${Build.VERSION.RELEASE} • API ${Build.VERSION.SDK_INT}"

        addSection(
            "LOCAL ADAPTER",
            """
            Name: ${tryName(a)}
            Address: ${tryAddress(a)}
            Enabled: $enabled
            State: ${tryState(a)}
            Bluetooth supported: ${packageManager.hasSystemFeature("android.hardware.bluetooth")}
            BLE supported: ${packageManager.hasSystemFeature("android.hardware.bluetooth_le")}
            """.trimIndent()
        )

        renderPaired()
    }

    // ---------------------------------------------------------
    // PAIRED DEVICES
    // ---------------------------------------------------------

    private fun renderPaired() {

        val a = adapter ?: return

        addSection(
            "BONDED / PAIRED DEVICES",
            ""
        )

        val devices =
            try {
                a.bondedDevices.toList()
            } catch (_: SecurityException) {
                emptyList()
            }

        if (devices.isEmpty()) {

            addSection(
                "PAIRED",
                "No paired devices visible to this application."
            )

            return
        }

        devices
            .sortedBy {
                safeName(it)
            }
            .forEach {

                renderDeviceCard(
                    it,
                    null,
                    "PAIRED"
                )
            }
    }

    // ---------------------------------------------------------
    // CLASSIC DEVICE CARD
    // ---------------------------------------------------------

    private fun renderDeviceCard(
        device: BluetoothDevice,
        result: ScanResult?,
        tag: String
    ) {

        val body =
            buildString {

                append(
                    "Name: ${safeName(device)}\n"
                )

                append(
                    "Address: ${safeAddress(device)}\n"
                )

                append(
                    "Type: ${typeName(device.type)}\n"
                )

                append(
                    "Bond: ${bondName(device.bondState)}\n"
                )

                if (result != null) {

                    append(
                        "RSSI: ${result.rssi} dBm\n"
                    )
                }

                val clazz =
                    try {
                        device.bluetoothClass
                    } catch (_: SecurityException) {
                        null
                    }

                if (clazz != null) {

                    append(
                        "Class: ${clazz.deviceClass} (${clazz.majorDeviceClass})\n"
                    )

                    append(
                        "Class hex: 0x${clazz.hashCode().toString(16)}\n"
                    )
                }

                val uuids =
                    try {
                        device.uuids
                            ?.joinToString("\n") {
                                it.uuid.toString()
                            }
                    } catch (_: SecurityException) {
                        null
                    }

                append(
                    "Cached UUIDs:\n${uuids ?: "none"}"
                )
            }

        val card =
            createSection(
                "$tag • ${safeName(device)}"
            )

        addText(
            card,
            body
        )

        val row =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }

        val sdpButton =
            button("Inspect SDP")

        val fetchButton =
            button("Fetch UUIDs")

        row.addView(
            sdpButton,
            weightLp()
        )

        row.addView(
            fetchButton,
            weightLp()
        )

        sdpButton.setOnClickListener {
            inspectSdp(device)
        }

        fetchButton.setOnClickListener {
            inspectSdp(device)
        }

        card.addView(
            row,
            lp(-1, -2)
        )
    }

    // ---------------------------------------------------------
    // SDP
    // ---------------------------------------------------------

    private fun inspectSdp(
        device: BluetoothDevice
    ) {

        if (!hasConnectPermission()) {

            requestBluetoothPermissions()

            return
        }

        try {

            status.text =
                "SDP UUID discovery requested for ${safeName(device)}"

            val requested =
                if (Build.VERSION.SDK_INT >= 33) {
                    device.fetchUuidsWithSdp()
                } else {
                    @Suppress("DEPRECATION")
                    device.fetchUuidsWithSdp()
                }

            if (!requested) {

                status.text =
                    "SDP request was not accepted by Android"
            }

        } catch (e: SecurityException) {

            status.text =
                "Bluetooth permission denied"

        } catch (e: Exception) {

            status.text =
                "SDP error: ${e.message}"
        }
    }

    private fun renderSdpResult(
        device: BluetoothDevice,
        uuids: List<UUID>
    ) {

        val card =
            createSection(
                "CLASSIC SDP / UUID DISCOVERY"
            )

        addText(
            card,
            """
            Device: ${safeName(device)}
            Address: ${safeAddress(device)}
            Type: ${typeName(device.type)}
            Bond: ${bondName(device.bondState)}
            UUIDs discovered: ${uuids.size}
            """.trimIndent()
        )

        if (uuids.isEmpty()) {

            addText(
                card,
                "No UUIDs returned by the Bluetooth stack."
            )

            return
        }

        uuids.forEach { uuid ->

            val service =
                LinearLayout(this).apply {

                    orientation =
                        LinearLayout.VERTICAL

                    setPadding(
                        dp(10),
                        dp(10),
                        dp(10),
                        dp(10)
                    )

                    setBackgroundColor(
                        getColor(R.color.bg)
                    )
                }

            val title =
                TextView(this).apply {

                    text =
                        profileName(uuid)

                    textSize = 15f

                    typeface =
                        android.graphics.Typeface.DEFAULT_BOLD

                    setTextColor(
                        getColor(R.color.text)
                    )
                }

            service.addView(
                title,
                lp(-1, -2)
            )

            val detail =
                TextView(this).apply {

                    text =
                        "UUID: ${uuid.toString().uppercase()}\n" +
                        "Category: ${uuidCategory(uuid)}"

                    textSize = 12f

                    setTextColor(
                        getColor(R.color.muted)
                    )

                    typeface =
                        android.graphics.Typeface.MONOSPACE
                }

            service.addView(
                detail,
                lp(-1, -2)
            )

            card.addView(
                service,
                lp(-1, -2)
            )
        }
    }

    private fun profileName(
        uuid: UUID
    ): String {

        return when (uuid.toString().lowercase()) {

            "00001101-0000-1000-8000-00805f9b34fb" ->
                "Serial Port Profile (SPP)"

            "00001108-0000-1000-8000-00805f9b34fb" ->
                "Headset Profile (HSP)"

            "0000110a-0000-1000-8000-00805f9b34fb" ->
                "A2DP Source"

            "0000110b-0000-1000-8000-00805f9b34fb" ->
                "A2DP Sink"

            "0000110c-0000-1000-8000-00805f9b34fb" ->
                "A/V Remote Control Target"

            "0000110d-0000-1000-8000-00805f9b34fb" ->
                "Advanced Audio Distribution"

            "0000110e-0000-1000-8000-00805f9b34fb" ->
                "A/V Remote Control / AVRCP"

            "00001112-0000-1000-8000-00805f9b34fb" ->
                "Headset Audio Gateway"

            "0000111e-0000-1000-8000-00805f9b34fb" ->
                "Hands-Free Profile (HFP)"

            "0000111f-0000-1000-8000-00805f9b34fb" ->
                "Hands-Free Audio Gateway"

            "00001115-0000-1000-8000-00805f9b34fb" ->
                "PAN User"

            "00001116-0000-1000-8000-00805f9b34fb" ->
                "PAN Network Access Point"

            "00001124-0000-1000-8000-00805f9b34fb" ->
                "Human Interface Device (HID)"

            "0000112f-0000-1000-8000-00805f9b34fb" ->
                "Phonebook Access Server"

            "00001132-0000-1000-8000-00805f9b34fb" ->
                "Message Access Server"

            "00001133-0000-1000-8000-00805f9b34fb" ->
                "Message Notification Server"

            "00001200-0000-1000-8000-00805f9b34fb" ->
                "PnP Information"

            else ->
                "Unknown / Custom Classic Service"
        }
    }

    private fun uuidCategory(
        uuid: UUID
    ): String {

        val value =
            uuid.toString().lowercase()

        return when {

            value.endsWith(
                "-0000-1000-8000-00805f9b34fb"
            ) ->
                "Bluetooth SIG 16-bit based UUID"

            else ->
                "Custom UUID"
        }
    }

    // ---------------------------------------------------------
    // BLE SCAN
    // ---------------------------------------------------------

    private fun startBleScan() {

        if (!hasScanPermission()) {

            requestBluetoothPermissions()

            return
        }

        val a = adapter

        if (a == null) {

            status.text =
                "Bluetooth adapter unavailable"

            return
        }

        val enabled =
            try {
                a.isEnabled
            } catch (_: SecurityException) {
                false
            }

        if (!enabled) {

            startActivity(
                Intent(
                    BluetoothAdapter.ACTION_REQUEST_ENABLE
                )
            )

            return
        }

        if (scanning) return

        seen.clear()

        scanner =
            try {
                a.bluetoothLeScanner
            } catch (_: SecurityException) {
                null
            }

        if (scanner == null) {

            status.text =
                "BLE scanner unavailable"

            return
        }

        scanning = true

        status.text =
            "BLE scan running…"

        renderScanResults()

        try {

            val settings =
                ScanSettings.Builder()
                    .setScanMode(
                        ScanSettings.SCAN_MODE_LOW_LATENCY
                    )
                    .build()

            scanner?.startScan(
                null,
                settings,
                scanCallback
            )

            handler.removeCallbacksAndMessages(null)

            handler.postDelayed(
                {
                    stopBleScan()
                },
                15000
            )

        } catch (e: SecurityException) {

            scanning = false

            status.text =
                "BLE permission denied"

        } catch (e: Exception) {

            scanning = false

            status.text =
                "BLE scan error: ${e.message}"
        }
    }

    private fun stopBleScan() {

        handler.removeCallbacksAndMessages(null)

        if (!scanning) return

        try {

            scanner?.stopScan(
                scanCallback
            )

        } catch (_: Exception) {
        }

        scanning = false

        status.text =
            "BLE scan stopped • ${seen.size} devices"
    }

    // ---------------------------------------------------------
    // BLE RESULTS
    // ---------------------------------------------------------

    private fun renderScanResults() {

        val card =
            findOrCreateSection(
                "LIVE BLE SCAN"
            )

        card.removeAllViews()

        addHeader(
            card,
            "LIVE BLE SCAN",
            "${seen.size} devices"
        )

        if (seen.isEmpty()) {

            addText(
                card,
                "No BLE advertisements captured yet."
            )

            return
        }

        val sorted =
            seen.values
                .sortedByDescending {
                    it.rssi
                }

        sorted.forEach { result ->

            val device =
                result.device

            val record =
                result.scanRecord

            val item =
                LinearLayout(this).apply {

                    orientation =
                        LinearLayout.VERTICAL

                    setPadding(
                        dp(12),
                        dp(12),
                        dp(12),
                        dp(12)
                    )

                    setBackgroundColor(
                        getColor(R.color.bg)
                    )
                }

            addText(
                item,
                buildString {

                    append(
                        "Name: ${safeName(device)}\n"
                    )

                    append(
                        "Address: ${safeAddress(device)}\n"
                    )

                    append(
                        "RSSI: ${result.rssi} dBm\n"
                    )

                    append(
                        "TX Power: ${
                            if (
                                result.txPower !=
                                ScanResult.TX_POWER_NOT_PRESENT
                            ) {
                                result.txPower
                            } else {
                                "N/A"
                            }
                        }\n"
                    )

                    append(
                        "Connectable: ${
                            if (Build.VERSION.SDK_INT >= 26) {
                                result.isConnectable
                            } else {
                                "unknown"
                            }
                        }\n"
                    )

                    append(
                        "Device Type: ${typeName(device.type)}\n"
                    )

                    if (record != null) {

                        append(
                            "Advertise Flags: ${record.advertiseFlags}\n"
                        )

                        append(
                            "Service UUIDs: ${
                                record.serviceUuids
                                    ?.joinToString {
                                        it.uuid.toString()
                                    }
                                    ?: "none"
                            }\n"
                        )

                        append(
                            "Service Data:\n${formatMap(record.serviceData)}\n"
                        )

                        append(
                            "Manufacturer Data:\n${
                                formatManufacturer(
                                    record.manufacturerSpecificData
                                )
                            }\n"
                        )

                        append(
                            "Raw Advertisement:\n${hex(record.bytes)}"
                        )
                    }
                }
            )

            val actions =
                LinearLayout(this).apply {
                    orientation =
                        LinearLayout.HORIZONTAL
                }

            val connect =
                button("Connect GATT")

            val details =
                button("Details")

            actions.addView(
                connect,
                weightLp()
            )

            actions.addView(
                details,
                weightLp()
            )

            connect.setOnClickListener {

                if (
                    device.type ==
                    BluetoothDevice.DEVICE_TYPE_CLASSIC
                ) {

                    status.text =
                        "This device is Classic Bluetooth, not BLE."

                } else {

                    connectGatt(device)
                }
            }

            details.setOnClickListener {

                showBleDetails(
                    result
                )
            }

            item.addView(
                actions,
                lp(-1, -2)
            )

            card.addView(
                item,
                lp(-1, -2)
            )
        }
    }

    private fun showBleDetails(
        result: ScanResult
    ) {

        val record =
            result.scanRecord

        val text =
            buildString {

                append(
                    "Name: ${safeName(result.device)}\n\n"
                )

                append(
                    "Address: ${safeAddress(result.device)}\n\n"
                )

                append(
                    "RSSI: ${result.rssi} dBm\n"
                )

                append(
                    "TX Power: ${result.txPower}\n"
                )

                if (record != null) {

                    append(
                        "\nService UUIDs:\n"
                    )

                    append(
                        record.serviceUuids
                            ?.joinToString("\n") {
                                it.uuid.toString()
                            }
                            ?: "none"
                    )

                    append(
                        "\n\nManufacturer Data:\n"
                    )

                    append(
                        formatManufacturer(
                            record.manufacturerSpecificData
                        )
                    )

                    append(
                        "\n\nService Data:\n"
                    )

                    append(
                        formatMap(
                            record.serviceData
                        )
                    )

                    append(
                        "\n\nRaw Advertisement:\n"
                    )

                    append(
                        hex(record.bytes)
                    )
                }
            }

        AlertDialog.Builder(this)
            .setTitle("BLE Advertisement")
            .setMessage(text)
            .setPositiveButton(
                "Close",
                null
            )
            .show()
    }

    // ---------------------------------------------------------
    // GATT CONNECTION
    // ---------------------------------------------------------

    private fun connectGatt(
        device: BluetoothDevice
    ) {

        if (!hasConnectPermission()) {

            requestBluetoothPermissions()

            return
        }

        try {
            gatt?.disconnect()
        } catch (_: Exception) {
        }

        try {
            gatt?.close()
        } catch (_: Exception) {
        }

        gatt = null

        status.text =
            "Connecting GATT: ${safeName(device)}"

        try {

            gatt =
                if (Build.VERSION.SDK_INT >= 26) {

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

        } catch (e: SecurityException) {

            status.text =
                "GATT permission denied"

        } catch (e: Exception) {

            status.text =
                "GATT connection error: ${e.message}"
        }
    }

    // ---------------------------------------------------------
    // GATT CALLBACK
    // ---------------------------------------------------------

    private val gattCallback =
        object : BluetoothGattCallback() {

            override fun onConnectionStateChange(
                g: BluetoothGatt,
                statusCode: Int,
                newState: Int
            ) {

                runOnUiThread {

                    when (newState) {

                        BluetoothProfile.STATE_CONNECTED -> {

                            status.text =
                                "GATT CONNECTED • ${safeName(g.device)}"

                            addSection(
                                "GATT CONNECTION",
                                """
                                Device: ${safeName(g.device)}
                                Address: ${safeAddress(g.device)}
                                State: CONNECTED
                                Status code: $statusCode
                                """.trimIndent()
                            )

                            try {

                                g.discoverServices()

                            } catch (e: Exception) {

                                status.text =
                                    "Service discovery error: ${e.message}"
                            }
                        }

                        BluetoothProfile.STATE_DISCONNECTED -> {

                            commandCharacteristics.clear()
                            lastReportByUuid.clear()

                            status.text =
                                "GATT DISCONNECTED • ${safeName(g.device)}"

                            addSection(
                                "GATT CONNECTION",
                                """
                                Device: ${safeName(g.device)}
                                Address: ${safeAddress(g.device)}
                                State: DISCONNECTED
                                Status code: $statusCode
                                """.trimIndent()
                            )
                        }

                        else -> {

                            status.text =
                                "GATT state=$newState status=$statusCode"
                        }
                    }
                }
            }

            override fun onServicesDiscovered(
                g: BluetoothGatt,
                statusCode: Int
            ) {

                runOnUiThread {

                    renderGatt(
                        g,
                        statusCode
                    )
                }
            }

            // Android 13+
            override fun onCharacteristicRead(
                g: BluetoothGatt,
                c: BluetoothGattCharacteristic,
                value: ByteArray,
                statusCode: Int
            ) {

                runOnUiThread {

                    renderGattData(
                        "GATT READ",
                        c.uuid,
                        value,
                        statusCode
                    )
                }
            }

            // Older Android
            @Deprecated("Deprecated in Android 13")
            override fun onCharacteristicRead(
                g: BluetoothGatt,
                c: BluetoothGattCharacteristic,
                statusCode: Int
            ) {

                if (Build.VERSION.SDK_INT < 33) {

                    val value =
                        c.value ?: ByteArray(0)

                    runOnUiThread {

                        renderGattData(
                            "GATT READ",
                            c.uuid,
                            value,
                            statusCode
                        )
                    }
                }
            }

            // Android 13+
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                c: BluetoothGattCharacteristic,
                value: ByteArray
            ) {

                runOnUiThread {

                    renderNotification(
                        c,
                        value
                    )
                }
            }

            // Older Android
            @Deprecated("Deprecated in Android 13")
            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                c: BluetoothGattCharacteristic
            ) {

                if (Build.VERSION.SDK_INT < 33) {

                    val value =
                        c.value ?: ByteArray(0)

                    runOnUiThread {

                        renderNotification(
                            c,
                            value
                        )
                    }
                }
            }

            override fun onCharacteristicWrite(
                g: BluetoothGatt,
                c: BluetoothGattCharacteristic,
                statusCode: Int
            ) {

                runOnUiThread {

                    addSection(
                        "GATT WRITE RESULT",
                        """
                        Characteristic: ${c.uuid}
                        Status: $statusCode
                        """.trimIndent()
                    )
                }
            }

            // Android 13+
            override fun onDescriptorRead(
                g: BluetoothGatt,
                d: BluetoothGattDescriptor,
                statusCode: Int,
                value: ByteArray
            ) {

                runOnUiThread {

                    addSection(
                        "DESCRIPTOR READ",
                        """
                        Descriptor: ${d.uuid}
                        Status: $statusCode
                        HEX: ${hex(value)}
                        TEXT: ${printable(value)}
                        """.trimIndent()
                    )
                }
            }

            // Older Android
            @Deprecated("Deprecated in Android 13")
            override fun onDescriptorRead(
                g: BluetoothGatt,
                d: BluetoothGattDescriptor,
                statusCode: Int
            ) {

                if (Build.VERSION.SDK_INT < 33) {

                    val value =
                        d.value ?: ByteArray(0)

                    runOnUiThread {

                        addSection(
                            "DESCRIPTOR READ",
                            """
                            Descriptor: ${d.uuid}
                            Status: $statusCode
                            HEX: ${hex(value)}
                            TEXT: ${printable(value)}
                            """.trimIndent()
                        )
                    }
                }
            }

            override fun onDescriptorWrite(
                g: BluetoothGatt,
                d: BluetoothGattDescriptor,
                statusCode: Int
            ) {

                if (d.uuid == CCCD_UUID && commandNotificationWriteInProgress) {
                    commandNotificationWriteInProgress = false
                    runOnUiThread {
                        enableNextCommandNotification(g)
                    }
                    return
                }

                runOnUiThread {

                    addSection(
                        "DESCRIPTOR WRITE",
                        """
                        Descriptor: ${d.uuid}
                        Status: $statusCode
                        """.trimIndent()
                    )
                }
            }
        }

    // ---------------------------------------------------------
    // GATT DATABASE
    // ---------------------------------------------------------

    private fun renderGatt(
        g: BluetoothGatt,
        statusCode: Int
    ) {

        val services =
            try {
                g.services
            } catch (_: Exception) {
                emptyList<BluetoothGattService>()
            }

        // Do not dump the whole GATT database. Build a focused command-capture screen.
        results.removeAllViews()
        commandCount = 0
        lastReportByUuid.clear()
        commandCharacteristics.clear()
        commandLog.clear()

        addSection(
            "COMMAND CAPTURE",
            """
            Device: ${safeName(g.device)}
            Address: ${safeAddress(g.device)}
            Status: ${if (statusCode == BluetoothGatt.GATT_SUCCESS) "READY" else "DISCOVERY ERROR $statusCode"}

            Press a physical button on the original Bluetooth device.
            The exact bytes received from the device will appear below.

            Capture: ${if (commandCaptureEnabled) "ON" else "PAUSED"}
            """.trimIndent()
        )

        val controls = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val clear = button("Clear")
        val pause = button(if (commandCaptureEnabled) "Pause" else "Resume")
        val info = button("What is this?")

        clear.setOnClickListener {
            commandCount = 0
            lastReportByUuid.clear()
            commandLog.clear()
            results.removeAllViews()
            addSection(
                "COMMAND CAPTURE",
                """
                Device: ${safeName(g.device)}
                Address: ${safeAddress(g.device)}
                Capture: ${if (commandCaptureEnabled) "ON" else "PAUSED"}

                Press a physical button on the original device.
                """.trimIndent()
            )
        }

        pause.setOnClickListener {
            commandCaptureEnabled = !commandCaptureEnabled
            pause.text = if (commandCaptureEnabled) "Pause" else "Resume"
            status.text = if (commandCaptureEnabled) {
                "Command capture resumed"
            } else {
                "Command capture paused"
            }
        }

        info.setOnClickListener {
            AlertDialog.Builder(this)
                .setTitle("Command Capture")
                .setMessage(
                    "This screen listens only to notification/indication traffic that can carry device commands or HID input reports.\n\n" +
                        "The HEX value is the exact payload received from the Bluetooth device. " +
                        "It is not modified or converted before display.\n\n" +
                        "For programming the replacement app, record the UUID + HEX for each physical button."
                )
                .setPositiveButton("OK", null)
                .show()
        }

        controls.addView(clear, weightLp())
        controls.addView(pause, weightLp())
        controls.addView(info, weightLp())

        results.addView(controls, lp(-1, -2))

        val candidates = mutableListOf<BluetoothGattCharacteristic>()

        services.forEach { service ->
            val serviceUuid = service.uuid
            val isHid = serviceUuid == HID_SERVICE_UUID

            service.characteristics.forEach { c ->
                val notify =
                    c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                val indicate =
                    c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0

                // STRICT BUTTON-CAPTURE MODE:
                // Do NOT subscribe to proprietary/custom notification streams here.
                // Your device exposes a custom NOTIFY characteristic (for example 00001002),
                // but that channel produces continuous traffic and is not reliable evidence
                // of a physical button press. The standard HID Input Report is the channel
                // we want for button events.
                val candidate =
                    isHid &&
                        c.uuid == HID_INPUT_REPORT_UUID &&
                        (notify || indicate)

                if (candidate) {
                    candidates += c
                    commandCharacteristics += c.uuid
                }
            }
        }

        if (candidates.isEmpty()) {
            addSection(
                "NO COMMAND CHANNEL",
                """
                No HID Input Report notification/indication was found.

                This capture mode intentionally ignores custom NOTIFY streams because they can
                contain continuous device telemetry rather than physical button events.
                """.trimIndent()
            )
            status.text = "Connected • waiting for HID button reports"
            return
        }

        addSection(
            "LISTENING FOR PHYSICAL BUTTONS",
            candidates.joinToString("\n") {
                "HID Input Report • ${it.uuid} • ${properties(it.properties)}"
            }
        )

        // Subscribe automatically. GATT descriptor writes must be serialized, otherwise
        // Android may return BUSY and one of the command channels can be missed.
        pendingCommandNotifications.clear()
        commandNotificationWriteInProgress = false

        candidates.forEach { c ->
            val indication =
                c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0 &&
                    c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY == 0

            pendingCommandNotifications.add(c to indication)
        }

        enableNextCommandNotification(g)

        // HID Report Map is useful for interpreting the raw HID report later, but it is
        // not a command stream, so it is intentionally not displayed as a result.
        status.text =
            "READY • waiting for a physical button press"
    }

    private fun renderGattService(
        g: BluetoothGatt,
        service: BluetoothGattService
    ) {

        val card =
            createSection(
                "SERVICE • ${service.uuid}"
            )

        addText(
            card,
            """
            Name: ${gattUuidName(service.uuid)}
            Type: ${
                if (
                    service.type ==
                    BluetoothGattService.SERVICE_TYPE_PRIMARY
                ) {
                    "PRIMARY"
                } else {
                    "SECONDARY"
                }
            }
            Instance ID: ${service.instanceId}
            Characteristics: ${service.characteristics.size}
            """.trimIndent()
        )

        service.characteristics.forEach { characteristic ->

            renderCharacteristic(
                g,
                card,
                characteristic
            )
        }
    }

    private fun renderCharacteristic(
        g: BluetoothGatt,
        parent: LinearLayout,
        characteristic: BluetoothGattCharacteristic
    ) {

        val box =
            LinearLayout(this).apply {

                orientation =
                    LinearLayout.VERTICAL

                setPadding(
                    dp(10),
                    dp(10),
                    dp(10),
                    dp(10)
                )

                setBackgroundColor(
                    getColor(R.color.bg)
                )
            }

        addText(
            box,
            """
            CHARACTERISTIC
            UUID: ${characteristic.uuid}
            Name: ${gattUuidName(characteristic.uuid)}
            Properties: ${properties(characteristic.properties)}
            Permissions: ${characteristicPermissions(characteristic.permissions)}
            Write Type: ${writeTypeName(characteristic.writeType)}
            Descriptors: ${characteristic.descriptors.size}
            """.trimIndent()
        )

        characteristic.descriptors.forEach { descriptor ->

            addText(
                box,
                """
                DESCRIPTOR
                UUID: ${descriptor.uuid}
                Name: ${gattUuidName(descriptor.uuid)}
                Permissions: ${descriptorPermissions(descriptor.permissions)}
                """.trimIndent()
            )
        }

        val controls =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }

        if (
            characteristic.properties and
            BluetoothGattCharacteristic.PROPERTY_READ != 0
        ) {

            val read =
                button("Read")

            read.setOnClickListener {

                try {

                    if (Build.VERSION.SDK_INT >= 33) {

                        g.readCharacteristic(
                            characteristic
                        )

                    } else {

                        @Suppress("DEPRECATION")
                        g.readCharacteristic(
                            characteristic
                        )
                    }

                } catch (e: Exception) {

                    status.text =
                        "Read error: ${e.message}"
                }
            }

            controls.addView(
                read,
                weightLp()
            )
        }

        if (
            characteristic.properties and
            (
                BluetoothGattCharacteristic.PROPERTY_WRITE or
                    BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                ) != 0
        ) {

            val write =
                button("Write")

            write.setOnClickListener {

                showWriteDialog(
                    g,
                    characteristic
                )
            }

            controls.addView(
                write,
                weightLp()
            )
        }

        if (
            characteristic.properties and
            BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        ) {

            val notify =
                button("Notify ON")

            notify.setOnClickListener {

                enableNotification(
                    g,
                    characteristic,
                    false
                )
            }

            controls.addView(
                notify,
                weightLp()
            )
        }

        if (
            characteristic.properties and
            BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        ) {

            val indicate =
                button("Indicate ON")

            indicate.setOnClickListener {

                enableNotification(
                    g,
                    characteristic,
                    true
                )
            }

            controls.addView(
                indicate,
                weightLp()
            )
        }

        if (
            characteristic.descriptors.isNotEmpty()
        ) {

            val descriptorButton =
                button("Descriptors")

            descriptorButton.setOnClickListener {

                showDescriptorDialog(
                    g,
                    characteristic
                )
            }

            controls.addView(
                descriptorButton,
                weightLp()
            )
        }

        if (controls.childCount > 0) {

            box.addView(
                controls,
                lp(-1, -2)
            )
        }

        parent.addView(
            box,
            lp(-1, -2)
        )
    }

    // ---------------------------------------------------------
    // NOTIFICATION / INDICATION
    // ---------------------------------------------------------

    private fun enableNextCommandNotification(
        g: BluetoothGatt
    ) {

        if (commandNotificationWriteInProgress) return

        if (pendingCommandNotifications.isEmpty()) {
            commandNotificationWriteInProgress = false
            status.text =
                "READY • press a button on ${safeName(g.device)}"
            return
        }

        val next = pendingCommandNotifications.removeAt(0)
        commandNotificationWriteInProgress = true

        val (characteristic, indication) = next

        if (!hasConnectPermission()) {
            commandNotificationWriteInProgress = false
            requestBluetoothPermissions()
            return
        }

        try {
            if (!g.setCharacteristicNotification(characteristic, true)) {
                commandNotificationWriteInProgress = false
                handler.post { enableNextCommandNotification(g) }
                return
            }

            val cccd = characteristic.descriptors.firstOrNull {
                it.uuid == CCCD_UUID
            }

            if (cccd == null) {
                commandNotificationWriteInProgress = false
                handler.post { enableNextCommandNotification(g) }
                return
            }

            val value =
                if (indication) {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                }

            if (Build.VERSION.SDK_INT >= 33) {
                val result = g.writeDescriptor(cccd, value)
                if (result != BluetoothStatusCodes.SUCCESS) {
                    commandNotificationWriteInProgress = false
                    handler.post { enableNextCommandNotification(g) }
                }
            } else {
                @Suppress("DEPRECATION")
                cccd.value = value

                @Suppress("DEPRECATION")
                val started = g.writeDescriptor(cccd)

                if (!started) {
                    commandNotificationWriteInProgress = false
                    handler.post { enableNextCommandNotification(g) }
                }
            }

        } catch (_: Exception) {
            commandNotificationWriteInProgress = false
            handler.post { enableNextCommandNotification(g) }
        }
    }

    private fun enableNotification(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic,
        indication: Boolean,
        silent: Boolean = false
    ) {

        if (!hasConnectPermission()) {

            requestBluetoothPermissions()

            return
        }

        try {

            val local =
                g.setCharacteristicNotification(
                    characteristic,
                    true
                )

            if (!local) {

                if (!silent) {
                    status.text = "Local notification registration failed"
                }

                return
            }

            val cccd =
                characteristic.descriptors.firstOrNull {
                    it.uuid == CCCD_UUID
                }

            if (cccd == null) {

                if (!silent) {
                    status.text = "Notification channel unavailable • ${characteristic.uuid}"
                }

                return
            }

            val value =
                if (indication) {

                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE

                } else {

                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                }

            val started =
                if (Build.VERSION.SDK_INT >= 33) {

                    g.writeDescriptor(
                        cccd,
                        value
                    ) == BluetoothStatusCodes.SUCCESS

                } else {

                    @Suppress("DEPRECATION")
                    cccd.value = value

                    @Suppress("DEPRECATION")
                    g.writeDescriptor(cccd)
                }

            if (!silent) {
                status.text =
                    if (started) {
                        if (indication) {
                            "Indication enabling requested"
                        } else {
                            "Notification enabling requested"
                        }
                    } else {
                        "CCCD write failed to start"
                    }
            }

        } catch (e: SecurityException) {

            if (!silent) {
                status.text = "Bluetooth permission denied"
            }

        } catch (e: Exception) {

            if (!silent) {
                status.text = "Notification error: ${e.message}"
            }
        }
    }

    // ---------------------------------------------------------
    // WRITE
    // ---------------------------------------------------------

    private fun showWriteDialog(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {

        val input =
            EditText(this).apply {

                hint =
                    "HEX: 01 FF 00"

                setSingleLine(true)
            }

        AlertDialog.Builder(this)
            .setTitle("Write Characteristic")
            .setMessage(
                "${characteristic.uuid}\n\n" +
                    "Properties: ${
                        properties(
                            characteristic.properties
                        )
                    }"
            )
            .setView(input)
            .setNegativeButton(
                "Cancel",
                null
            )
            .setPositiveButton(
                "Write"
            ) { _, _ ->

                val bytes =
                    parseHex(
                        input.text.toString()
                    )

                if (bytes.isEmpty()) {

                    status.text =
                        "No valid HEX bytes"

                    return@setPositiveButton
                }

                try {

                    val writeType =
                        if (
                            characteristic.properties and
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                        ) {

                            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

                        } else {

                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        }

                    if (Build.VERSION.SDK_INT >= 33) {

                        val result =
                            g.writeCharacteristic(
                                characteristic,
                                bytes,
                                writeType
                            )

                        status.text =
                            "Write requested • result=$result"

                    } else {

                        @Suppress("DEPRECATION")
                        characteristic.writeType =
                            writeType

                        @Suppress("DEPRECATION")
                        characteristic.value =
                            bytes

                        @Suppress("DEPRECATION")
                        val ok =
                            g.writeCharacteristic(
                                characteristic
                            )

                        status.text =
                            "Write requested • $ok"
                    }

                } catch (e: Exception) {

                    status.text =
                        "Write error: ${e.message}"
                }
            }
            .show()
    }

    // ---------------------------------------------------------
    // DESCRIPTORS
    // ---------------------------------------------------------

    private fun showDescriptorDialog(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {

        val names =
            characteristic.descriptors
                .map {
                    "${it.uuid}"
                }
                .toTypedArray()

        if (names.isEmpty()) return

        AlertDialog.Builder(this)
            .setTitle(
                "Descriptors"
            )
            .setItems(names) { _, which ->

                readDescriptor(
                    g,
                    characteristic.descriptors[which]
                )
            }
            .setNegativeButton(
                "Close",
                null
            )
            .show()
    }

    private fun readDescriptor(
        g: BluetoothGatt,
        descriptor: BluetoothGattDescriptor
    ) {

        try {

            if (Build.VERSION.SDK_INT >= 33) {

                val result =
                    g.readDescriptor(
                        descriptor
                    )

                status.text =
                    "Descriptor read requested • result=$result"

            } else {

                @Suppress("DEPRECATION")
                val result =
                    g.readDescriptor(
                        descriptor
                    )

                status.text =
                    "Descriptor read requested • $result"
            }

        } catch (e: Exception) {

            status.text =
                "Descriptor read error: ${e.message}"
        }
    }

    // ---------------------------------------------------------
    // GATT DATA
    // ---------------------------------------------------------

    private fun renderGattData(
        title: String,
        uuid: UUID,
        value: ByteArray,
        statusCode: Int
    ) {

        addSection(
            title,
            """
            UUID: $uuid
            Status: $statusCode

            HEX:
            ${hex(value)}

            TEXT:
            ${printable(value)}
            """.trimIndent()
        )
    }

    private fun renderNotification(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {

        if (!commandCaptureEnabled) return

        // Absolute filter: only the standard HID Input Report can reach the
        // button log. Custom notification streams are intentionally ignored.
        if (characteristic.uuid != HID_INPUT_REPORT_UUID) return
        if (characteristic.uuid !in commandCharacteristics) return

        val previous = lastReportByUuid[characteristic.uuid]

        // Many HID devices repeat the current state periodically. A repeated
        // identical report is not a new button event, so suppress it.
        if (previous != null && previous.contentEquals(value)) return

        val changed = changedBytes(previous, value)
        lastReportByUuid[characteristic.uuid] = value.copyOf()
        commandCount++

        val label =
            if (characteristic.uuid == HID_INPUT_REPORT_UUID) {
                "HID BUTTON REPORT"
            } else {
                "DEVICE COMMAND"
            }

        val body = buildString {
            append("Event #$commandCount\n")
            append("Channel: $label\n")
            append("UUID: ${characteristic.uuid}\n")
            append("Length: ${value.size} bytes\n")
            append("\nEXACT RAW HEX:\n")
            append(hex(value))
            append("\n\nChanged bytes:")
            append(
                if (changed.isEmpty()) {
                    " none"
                } else {
                    " " + changed.joinToString(", ") { it.toString() }
                }
            )

            if (characteristic.uuid == HID_INPUT_REPORT_UUID) {
                append("\n\nHID interpretation: ${decodeHidReport(value)}")
            }

            append("\n\nTEXT (diagnostic only):\n")
            append(printable(value))
        }

        val card = createSection(
            "#$commandCount • ${if (characteristic.uuid == HID_INPUT_REPORT_UUID) "BUTTON" else "COMMAND"}"
        )

        addText(card, body)

        // Newest command first: move the card to the top of the result list.
        results.removeView(card)
        results.addView(card, 2.coerceAtMost(results.childCount))

        status.text =
            "CAPTURED #$commandCount • ${hex(value)}"
    }

    private fun changedBytes(
        previous: ByteArray?,
        current: ByteArray
    ): List<Int> {

        if (previous == null) {
            return current.indices.toList()
        }

        val max = maxOf(previous.size, current.size)
        val changed = mutableListOf<Int>()

        for (i in 0 until max) {
            val old = if (i < previous.size) previous[i].toInt() and 0xFF else -1
            val now = if (i < current.size) current[i].toInt() and 0xFF else -1
            if (old != now) changed += i
        }

        return changed
    }

    private fun decodeHidReport(
        value: ByteArray
    ): String {

        if (value.isEmpty()) return "empty report"

        // Do not pretend to know a proprietary mapping. For standard HID reports,
        // expose useful byte values while keeping the exact raw report above.
        val bytes = value.map { it.toInt() and 0xFF }

        return buildString {
            append("bytes=")
            append(bytes.joinToString(" "))

            if (bytes.size >= 2) {
                append("; non-zero positions=")
                append(
                    bytes.mapIndexedNotNull { index, b ->
                        if (b != 0) "$index:$b" else null
                    }.joinToString(", ").ifEmpty { "none" }
                )
            }
        }
    }

    private fun isCustomService(
        uuid: UUID
    ): Boolean {

        return uuid.toString().lowercase().let {
            it != "00001800-0000-1000-8000-00805f9b34fb" &&
                it != "00001801-0000-1000-8000-00805f9b34fb" &&
                it != "0000180a-0000-1000-8000-00805f9b34fb" &&
                it != "0000180f-0000-1000-8000-00805f9b34fb" &&
                it != "00001812-0000-1000-8000-00805f9b34fb"
        }
    }

    // ---------------------------------------------------------
    // UUID NAMES
    // ---------------------------------------------------------

    private fun gattUuidName(
        uuid: UUID
    ): String {

        return when (
            uuid.toString().lowercase()
        ) {

            "00001800-0000-1000-8000-00805f9b34fb" ->
                "Generic Access"

            "00001801-0000-1000-8000-00805f9b34fb" ->
                "Generic Attribute"

            "0000180a-0000-1000-8000-00805f9b34fb" ->
                "Device Information Service"

            "0000180f-0000-1000-8000-00805f9b34fb" ->
                "Battery Service"

            "0000180d-0000-1000-8000-00805f9b34fb" ->
                "Heart Rate Service"

            "00001812-0000-1000-8000-00805f9b34fb" ->
                "Human Interface Device"

            "0000180e-0000-1000-8000-00805f9b34fb" ->
                "Phone Alert Status"

            "00001809-0000-1000-8000-00805f9b34fb" ->
                "Health Thermometer"

            "00002a00-0000-1000-8000-00805f9b34fb" ->
                "Device Name"

            "00002a01-0000-1000-8000-00805f9b34fb" ->
                "Appearance"

            "00002a19-0000-1000-8000-00805f9b34fb" ->
                "Battery Level"

            "00002a29-0000-1000-8000-00805f9b34fb" ->
                "Manufacturer Name String"

            "00002a24-0000-1000-8000-00805f9b34fb" ->
                "Model Number String"

            "00002a25-0000-1000-8000-00805f9b34fb" ->
                "Serial Number String"

            "00002a26-0000-1000-8000-00805f9b34fb" ->
                "Firmware Revision String"

            "00002a27-0000-1000-8000-00805f9b34fb" ->
                "Hardware Revision String"

            "00002a28-0000-1000-8000-00805f9b34fb" ->
                "Software Revision String"

            "00002a4b-0000-1000-8000-00805f9b34fb" ->
                "HID Report Map"

            "00002a4d-0000-1000-8000-00805f9b34fb" ->
                "HID Input Report"

            CCCD_UUID.toString().lowercase() ->
                "Client Characteristic Configuration"

            else ->
                "Unknown / Custom UUID"
        }
    }

    // ---------------------------------------------------------
    // CHARACTERISTIC HELPERS
    // ---------------------------------------------------------

    private fun properties(
        p: Int
    ): String {

        val list =
            mutableListOf<String>()

        if (
            p and
            BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0
        ) {
            list += "BROADCAST"
        }

        if (
            p and
            BluetoothGattCharacteristic.PROPERTY_READ != 0
        ) {
            list += "READ"
        }

        if (
            p and
            BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        ) {
            list += "WRITE"
        }

        if (
            p and
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        ) {
            list += "WRITE_NO_RESPONSE"
        }

        if (
            p and
            BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        ) {
            list += "NOTIFY"
        }

        if (
            p and
            BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        ) {
            list += "INDICATE"
        }

        if (
            p and
            BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0
        ) {
            list += "SIGNED_WRITE"
        }

        if (
            p and
            BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS != 0
        ) {
            list += "EXTENDED"
        }

        return list.joinToString(", ")
            .ifEmpty {
                "NONE"
            }
    }

    private fun characteristicPermissions(
        p: Int
    ): String {

        val list =
            mutableListOf<String>()

        if (
            p and
            BluetoothGattCharacteristic.PERMISSION_READ != 0
        ) {
            list += "READ"
        }

        if (
            p and
            BluetoothGattCharacteristic.PERMISSION_WRITE != 0
        ) {
            list += "WRITE"
        }

        if (
            p and
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED != 0
        ) {
            list += "READ_ENCRYPTED"
        }

        if (
            p and
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED != 0
        ) {
            list += "WRITE_ENCRYPTED"
        }

        if (
            p and
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM != 0
        ) {
            list += "READ_MITM"
        }

        if (
            p and
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM != 0
        ) {
            list += "WRITE_MITM"
        }

        return list.joinToString(", ")
            .ifEmpty {
                "NONE"
            }
    }

    private fun descriptorPermissions(
        p: Int
    ): String {

        val list =
            mutableListOf<String>()

        if (
            p and
            BluetoothGattDescriptor.PERMISSION_READ != 0
        ) {
            list += "READ"
        }

        if (
            p and
            BluetoothGattDescriptor.PERMISSION_WRITE != 0
        ) {
            list += "WRITE"
        }

        if (
            p and
            BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED != 0
        ) {
            list += "READ_ENCRYPTED"
        }

        if (
            p and
            BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED != 0
        ) {
            list += "WRITE_ENCRYPTED"
        }

        if (
            p and
            BluetoothGattDescriptor.PERMISSION_READ_ENCRYPTED_MITM != 0
        ) {
            list += "READ_MITM"
        }

        if (
            p and
            BluetoothGattDescriptor.PERMISSION_WRITE_ENCRYPTED_MITM != 0
        ) {
            list += "WRITE_MITM"
        }

        return list.joinToString(", ")
            .ifEmpty {
                "NONE"
            }
    }

    private fun writeTypeName(
        type: Int
    ): String {

        return when (type) {

            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT ->
                "WRITE"

            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE ->
                "WRITE_NO_RESPONSE"

            BluetoothGattCharacteristic.WRITE_TYPE_SIGNED ->
                "SIGNED_WRITE"

            else ->
                type.toString()
        }
    }

    // ---------------------------------------------------------
    // GENERAL HELPERS
    // ---------------------------------------------------------

    private fun addSection(
        title: String,
        body: String
    ) {

        val card =
            createSection(title)

        if (body.isNotBlank()) {
            addText(card, body)
        }
    }

    private fun createSection(
        title: String
    ): LinearLayout {

        val card =
            findOrCreateSection(title)

        card.removeAllViews()

        addHeader(
            card,
            title,
            ""
        )

        return card
    }

    private fun findOrCreateSection(
        title: String
    ): LinearLayout {

        for (
            i in 0 until results.childCount
        ) {

            val child =
                results.getChildAt(i)

            if (
                child is LinearLayout &&
                child.tag == title
            ) {
                return child
            }
        }

        return LinearLayout(this).apply {

            tag = title

            orientation =
                LinearLayout.VERTICAL

            setPadding(
                dp(14),
                dp(12),
                dp(14),
                dp(12)
            )

            setBackgroundColor(
                getColor(R.color.panel)
            )

            results.addView(
                this,
                lp(-1, -2)
            )
        }
    }

    private fun addHeader(
        parent: LinearLayout,
        title: String,
        trailing: String
    ) {

        val row =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.HORIZONTAL
            }

        val text =
            TextView(this).apply {

                this.text =
                    title

                textSize = 16f

                typeface =
                    android.graphics.Typeface.DEFAULT_BOLD

                setTextColor(
                    getColor(R.color.text)
                )
            }

        row.addView(
            text,
            LinearLayout.LayoutParams(
                0,
                -2,
                1f
            )
        )

        if (trailing.isNotEmpty()) {

            val tail =
                TextView(this).apply {

                    this.text =
                        trailing

                    textSize = 12f

                    setTextColor(
                        getColor(R.color.muted)
                    )
                }

            row.addView(
                tail
            )
        }

        parent.addView(
            row
        )
    }

    private fun addText(
        parent: LinearLayout,
        text: String
    ) {

        val view =
            TextView(this).apply {

                this.text =
                    text

                textSize = 13f

                setTextColor(
                    getColor(R.color.text)
                )

                setPadding(
                    0,
                    dp(8),
                    0,
                    0
                )

                typeface =
                    android.graphics.Typeface.MONOSPACE
            }

        parent.addView(
            view
        )
    }

    private fun button(
        label: String
    ): Button {

        return Button(this).apply {

            text =
                label

            textSize = 11f

            isAllCaps = false

            minHeight =
                dp(42)

            setPadding(
                dp(4),
                0,
                dp(4),
                0
            )
        }
    }

    private fun lp(
        width: Int,
        height: Int
    ) =
        LinearLayout.LayoutParams(
            width,
            height
        ).apply {

            setMargins(
                0,
                dp(5),
                0,
                dp(5)
            )
        }

    private fun weightLp() =
        LinearLayout.LayoutParams(
            0,
            dp(48),
            1f
        ).apply {

            setMargins(
                dp(2),
                dp(5),
                dp(2),
                dp(5)
            )
        }

    private fun dp(
        value: Int
    ): Int =
        (
            value *
                resources.displayMetrics.density
            ).roundToInt()

    // ---------------------------------------------------------
    // BLUETOOTH HELPERS
    // ---------------------------------------------------------

    private fun safeName(
        device: BluetoothDevice
    ): String {

        return try {
            device.name ?: "(unknown)"
        } catch (_: SecurityException) {
            "(permission)"
        }
    }

    private fun safeAddress(
        device: BluetoothDevice
    ): String {

        return try {
            device.address
        } catch (_: SecurityException) {
            "(permission)"
        }
    }

    private fun tryName(
        a: BluetoothAdapter
    ): String {

        return try {
            a.name ?: "(unknown)"
        } catch (_: SecurityException) {
            "(permission)"
        }
    }

    private fun tryAddress(
        a: BluetoothAdapter
    ): String {

        return try {
            a.address
        } catch (_: SecurityException) {
            "(restricted)"
        }
    }

    private fun tryState(
        a: BluetoothAdapter
    ): String {

        return try {
            a.state.toString()
        } catch (_: SecurityException) {
            "unknown"
        }
    }

    private fun bondName(
        state: Int
    ): String {

        return when (state) {

            BluetoothDevice.BOND_BONDED ->
                "BONDED"

            BluetoothDevice.BOND_BONDING ->
                "BONDING"

            BluetoothDevice.BOND_NONE ->
                "NONE"

            else ->
                state.toString()
        }
    }

    private fun typeName(
        type: Int
    ): String {

        return when (type) {

            BluetoothDevice.DEVICE_TYPE_CLASSIC ->
                "CLASSIC"

            BluetoothDevice.DEVICE_TYPE_LE ->
                "BLE"

            BluetoothDevice.DEVICE_TYPE_DUAL ->
                "DUAL"

            else ->
                "UNKNOWN"
        }
    }

    // ---------------------------------------------------------
    // DATA FORMATTERS
    // ---------------------------------------------------------

    private fun formatManufacturer(
        sparse: android.util.SparseArray<ByteArray>?
    ): String {

        if (
            sparse == null ||
            sparse.size() == 0
        ) {
            return "none"
        }

        return buildString {

            for (
                i in 0 until sparse.size()
            ) {

                append(
                    "Company ID=${sparse.keyAt(i)}: "
                )

                append(
                    hex(
                        sparse.valueAt(i)
                    )
                )

                append("\n")
            }
        }.trim()
    }

    private fun formatMap(
        map: Map<ParcelUuid, ByteArray>?
    ): String {

        if (map.isNullOrEmpty()) {
            return "none"
        }

        return map.entries.joinToString("\n") {

            "${it.key}: ${hex(it.value)}"
        }
    }

    private fun hex(
        bytes: ByteArray?
    ): String {

        return bytes?.joinToString(" ") {

            "%02X".format(
                it.toInt() and 0xFF
            )

        } ?: "null"
    }

    private fun printable(
        bytes: ByteArray?
    ): String {

        return try {

            bytes
                ?.toString(
                    Charset.forName("UTF-8")
                )
                ?.replace(
                    Regex("[^\\x20-\\x7E]"),
                    "."
                )
                ?: ""

        } catch (_: Exception) {

            ""
        }
    }

    private fun parseHex(
        input: String
    ): ByteArray {

        val cleaned =
            input
                .trim()
                .replace(
                    Regex("[,:-]"),
                    " "
                )

        if (cleaned.isBlank()) {
            return ByteArray(0)
        }

        return cleaned
            .split(
                Regex("\\s+")
            )
            .filter {
                it.matches(
                    Regex("[0-9A-Fa-f]{1,2}")
                )
            }
            .map {
                it.toInt(16).toByte()
            }
            .toByteArray()
    }

    // ---------------------------------------------------------
    // PERMISSIONS
    // ---------------------------------------------------------

    private fun hasScanPermission(): Boolean {

        return if (Build.VERSION.SDK_INT >= 31) {

            checkSelfPermission(
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED

        } else {

            true
        }
    }

    private fun hasConnectPermission(): Boolean {

        return if (Build.VERSION.SDK_INT >= 31) {

            checkSelfPermission(
                Manifest.permission.BLUETOOTH_CONNECT
            ) == PackageManager.PERMISSION_GRANTED

        } else {

            true
        }
    }

    private fun requestBluetoothPermissions() {

        if (Build.VERSION.SDK_INT >= 31) {

            requestPermissions(
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                ),
                permissionRequest
            )

        } else if (Build.VERSION.SDK_INT >= 23) {

            requestPermissions(
                arrayOf(
                    Manifest.permission.ACCESS_FINE_LOCATION
                ),
                permissionRequest
            )
        }
    }

    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray
    ) {

        super.onRequestPermissionsResult(
            requestCode,
            permissions,
            grantResults
        )

        if (
            requestCode ==
            permissionRequest
        ) {

            refreshLocalInfo()
        }
    }

    // ---------------------------------------------------------
    // PARCELABLE COMPATIBILITY
    // ---------------------------------------------------------

    private inline fun <reified T : Parcelable>
            Intent.getParcelableExtraCompat(
        key: String
    ): T? {

        return if (
            Build.VERSION.SDK_INT >= 33
        ) {

            getParcelableExtra(
                key,
                T::class.java
            )

        } else {

            @Suppress("DEPRECATION")
            getParcelableExtra(
                key
            )
        }
    }
}