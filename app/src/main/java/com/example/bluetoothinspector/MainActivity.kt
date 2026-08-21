package com.example.bluetoothinspector

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
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
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.Parcelable
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.nio.charset.Charset
import kotlin.math.roundToInt

class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var status: TextView
    private lateinit var results: LinearLayout
    private lateinit var scanButton: Button
    private lateinit var refreshButton: Button

    private val btManager by lazy {
        getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
    }

    private val adapter: BluetoothAdapter?
        get() = btManager.adapter

    private var leScanner: BluetoothLeScanner? = null
    private var scanning = false
    private var gatt: BluetoothGatt? = null

    private val seen = linkedMapOf<String, ScanResult>()

    private val handler = Handler(Looper.getMainLooper())

    private val permissionRequest = 9001

    /*
     * Classic Bluetooth SDP result receiver.
     *
     * fetchUuidsWithSdp() is asynchronous.
     * Android sends ACTION_UUID when discovery completes.
     */
    private val uuidReceiver = object : BroadcastReceiver() {

        override fun onReceive(context: Context?, intent: Intent?) {

            if (intent?.action != BluetoothDevice.ACTION_UUID) {
                return
            }

            try {

                val device =
                    intent.getParcelableExtraCompat<BluetoothDevice>(
                        BluetoothDevice.EXTRA_DEVICE
                    )

                val uuidArray: Array<android.os.ParcelUuid>? =
                    if (Build.VERSION.SDK_INT >= 33) {

                        intent.getParcelableArrayExtra(
                            BluetoothDevice.EXTRA_UUID,
                            android.os.ParcelUuid::class.java
                        )

                    } else {

                        @Suppress("DEPRECATION")
                        intent.getParcelableArrayExtra(
                            BluetoothDevice.EXTRA_UUID
                        )
                            ?.mapNotNull {
                                it as? android.os.ParcelUuid
                            }
                            ?.toTypedArray()
                    }

                val uuids = uuidArray
                    ?.map { it.uuid.toString() }
                    ?: emptyList()

                if (device == null) {

                    status.text =
                        "SDP completed, but device information was unavailable"

                    addSection(
                        "CLASSIC SDP / UUID DISCOVERY",
                        """
                        Device: Unknown

                        UUIDs discovered: ${uuids.size}

                        ${
                            if (uuids.isEmpty()) {
                                "No UUIDs discovered."
                            } else {
                                uuids.joinToString("\n")
                            }
                        }
                        """.trimIndent()
                    )

                    return

                }

                status.text =
                    "SDP discovery completed • ${safeName(device)} • ${uuids.size} UUIDs"

                addSection(
                    "CLASSIC SDP / UUID DISCOVERY",
                    """
                    Device: ${safeName(device)}
                    Address: ${safeAddress(device)}

                    UUIDs discovered: ${uuids.size}

                    ${
                        when {
                            uuidArray == null ->
                                "SDP timeout or no UUID result was returned."

                            uuids.isEmpty() ->
                                "SDP completed successfully, but no UUIDs were discovered."

                            else ->
                                uuids.joinToString("\n")
                        }
                    }
                    """.trimIndent()
                )

            } catch (e: SecurityException) {

                status.text = "Bluetooth permission denied"

            } catch (e: Exception) {

                status.text =
                    "UUID broadcast error: ${e.message ?: "Unknown error"}"
            }
        }
    }

    /*
     * BLE scanner callback.
     */
    private val leCallback = object : ScanCallback() {

        override fun onScanResult(
            callbackType: Int,
            result: ScanResult
        ) {
            runOnUiThread {

                try {
                    seen[result.device.address] = result
                    renderScanResults()
                } catch (e: Exception) {
                    status.text =
                        "BLE result error: ${e.message ?: "Unknown error"}"
                }
            }
        }

        override fun onBatchScanResults(
            results: MutableList<ScanResult>
        ) {
            runOnUiThread {

                try {

                    results.forEach {
                        seen[it.device.address] = it
                    }

                    renderScanResults()

                } catch (e: Exception) {

                    status.text =
                        "BLE batch error: ${e.message ?: "Unknown error"}"
                }
            }
        }

        override fun onScanFailed(errorCode: Int) {
            runOnUiThread {

                scanning = false

                status.text =
                    "BLE scan failed: $errorCode"
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        window.statusBarColor = getColor(R.color.bg)
        window.navigationBarColor = getColor(R.color.bg)

        buildUi()

        /*
         * ACTION_UUID comes from the Bluetooth system,
         * so the receiver must be able to receive external broadcasts.
         */
        try {

            registerReceiver(
                uuidReceiver,
                IntentFilter(BluetoothDevice.ACTION_UUID),
                RECEIVER_EXPORTED
            )

        } catch (e: Exception) {

            status.text =
                "UUID receiver registration failed: ${e.message ?: "Unknown error"}"
        }

        requestBluetoothPermissions()
        refreshLocalInfo()
    }

    override fun onDestroy() {

        stopBleScan()

        try {
            unregisterReceiver(uuidReceiver)
        } catch (_: Exception) {
        }

        try {
            gatt?.close()
        } catch (_: Exception) {
        }

        gatt = null

        super.onDestroy()
    }

    /*
     * ---------------------------------------------------------
     * UI
     * ---------------------------------------------------------
     */

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

        val sub = TextView(this).apply {

            text =
                "Classic Bluetooth + BLE • GATT • SDP • RSSI • Manufacturer Data"

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
            sub,
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

        val buttons = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        refreshButton = button("Refresh")
        scanButton = button("Scan BLE")

        val stop = button("Stop")
        val paired = button("Paired")

        buttons.addView(
            refreshButton,
            weightLp()
        )

        buttons.addView(
            scanButton,
            weightLp()
        )

        buttons.addView(
            stop,
            weightLp()
        )

        buttons.addView(
            paired,
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

        stop.setOnClickListener {
            stopBleScan()
        }

        paired.setOnClickListener {
            renderPaired()
        }

        val scroll = ScrollView(this)

        results = LinearLayout(this).apply {

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

    /*
     * ---------------------------------------------------------
     * LOCAL BLUETOOTH INFORMATION
     * ---------------------------------------------------------
     */

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
            "Bluetooth: ${if (enabled) "ON" else "OFF"} • Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})"

        addSection(
            "LOCAL ADAPTER",
            """
            Name: ${tryName(a)}
            Address: ${tryAddress(a)}
            Enabled: $enabled
            State: ${tryState(a)}
            Hardware supported: ${packageManager.hasSystemFeature("android.hardware.bluetooth")}
            BLE supported: ${packageManager.hasSystemFeature("android.hardware.bluetooth_le")}
            Multiple advertisement supported: ${packageManager.hasSystemFeature("android.hardware.bluetooth_le")}
            """.trimIndent()
        )

        renderPaired()
    }

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
                "No paired devices visible to this app."
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

    /*
     * ---------------------------------------------------------
     * BLE SCANNING
     * ---------------------------------------------------------
     */

    private fun renderScanResults() {

        val scan =
            seen.values
                .sortedByDescending {
                    it.rssi
                }

        val header =
            findOrCreateSection("LIVE BLE SCAN")

        header.removeAllViews()

        addHeader(
            header,
            "LIVE BLE SCAN",
            "${scan.size} unique addresses"
        )

        if (scan.isEmpty()) {

            addText(
                header,
                "No BLE advertisements captured yet."
            )

            return
        }

        scan.forEach { result ->

            val d = result.device

            val md = result.scanRecord

            val sb = StringBuilder()

            sb.append(
                "Name: ${safeName(d)}\n"
            )

            sb.append(
                "Address: ${safeAddress(d)}\n"
            )

            sb.append(
                "RSSI: ${result.rssi} dBm\n"
            )

            sb.append(
                "TX power: ${
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

            sb.append(
                "Connectable: ${
                    if (Build.VERSION.SDK_INT >= 26) {
                        result.isConnectable
                    } else {
                        "unknown"
                    }
                }\n"
            )

            sb.append(
                "Device type: ${typeName(d.type)}\n"
            )

            if (md != null) {

                sb.append(
                    "Advertise flags: ${md.advertiseFlags}\n"
                )

                sb.append(
                    "Service UUIDs: ${
                        md.serviceUuids
                            ?.joinToString {
                                it.uuid.toString()
                            }
                            ?: "none"
                    }\n"
                )

                sb.append(
                    "Service data: ${
                        formatMap(md.serviceData)
                    }\n"
                )

                sb.append(
                    "Manufacturer data: ${
                        formatManufacturer(
                            md.manufacturerSpecificData
                        )
                    }\n"
                )

                sb.append(
                    "Raw advertising bytes: ${
                        hex(md.bytes)
                    }\n"
                )
            }

            addSection(
                "BLE DEVICE • ${safeName(d)}",
                sb.toString()
            )

            addActionRow(
                d,
                true
            )
        }
    }

    private fun addActionRow(
        device: BluetoothDevice,
        ble: Boolean
    ) {

        val row = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
        }

        val connect =
            button(
                if (ble) {
                    "Connect GATT"
                } else {
                    "Inspect SDP"
                }
            )

        val uuids =
            button("Fetch UUIDs")

        row.addView(
            connect,
            weightLp()
        )

        row.addView(
            uuids,
            weightLp()
        )

        connect.setOnClickListener {

            if (ble) {

                connectGatt(device)

            } else {

                inspectSdp(device)
            }
        }

        uuids.setOnClickListener {

            inspectSdp(device)
        }

        results.addView(
            row,
            lp(-1, -2)
        )
    }

    private fun renderDeviceCard(
        device: BluetoothDevice,
        result: ScanResult?,
        tag: String
    ) {

        val sb = StringBuilder()

        sb.append(
            "Name: ${safeName(device)}\n"
        )

        sb.append(
            "Address: ${safeAddress(device)}\n"
        )

        sb.append(
            "Type: ${typeName(device.type)}\n"
        )

        sb.append(
            "Bond: ${bondName(device.bondState)}\n"
        )

        if (result != null) {

            sb.append(
                "RSSI: ${result.rssi} dBm\n"
            )
        }

        val cls =
            try {

                device.bluetoothClass

            } catch (_: SecurityException) {

                null
            }

        if (cls != null) {

            sb.append(
                "Class: ${cls.deviceClass} (${cls.majorDeviceClass})\n"
            )

            sb.append(
                "Class hex: 0x${cls.hashCode().toString(16)}\n"
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

        sb.append(
            "Cached UUIDs:\n${uuids ?: "none"}"
        )

        addSection(
            "$tag • ${safeName(device)}",
            sb.toString()
        )

        addActionRow(
            device,
            false
        )
    }

    /*
     * ---------------------------------------------------------
     * CLASSIC BLUETOOTH / SDP
     * ---------------------------------------------------------
     */

    private fun inspectSdp(
        device: BluetoothDevice
    ) {

        if (!hasConnectPermission()) {

            requestBluetoothPermissions()

            status.text =
                "Bluetooth Connect permission is required"

            return
        }

        try {

            @Suppress("DEPRECATION")
            val started =
                device.fetchUuidsWithSdp()

            status.text =
                if (started) {

                    "SDP discovery started for ${safeName(device)}…"

                } else {

                    "SDP discovery could not be started"
                }

        } catch (e: SecurityException) {

            status.text =
                "Bluetooth permission denied"

        } catch (e: Exception) {

            status.text =
                "SDP error: ${e.message ?: "Unknown error"}"
        }
    }

    /*
     * ---------------------------------------------------------
     * GATT
     * ---------------------------------------------------------
     */

    private fun connectGatt(
        device: BluetoothDevice
    ) {

        if (!hasConnectPermission()) {

            requestBluetoothPermissions()

            status.text =
                "Bluetooth Connect permission is required"

            return
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
                "Bluetooth permission denied"

        } catch (e: Exception) {

            status.text =
                "GATT connection error: ${
                    e.message ?: "Unknown error"
                }"
        }
    }

    private val gattCallback =
        object : BluetoothGattCallback() {

            override fun onConnectionStateChange(
                g: BluetoothGatt,
                statusCode: Int,
                newState: Int
            ) {

                runOnUiThread {

                    val stateText =
                        when (newState) {

                            BluetoothProfile.STATE_CONNECTED ->
                                "CONNECTED"

                            BluetoothProfile.STATE_CONNECTING ->
                                "CONNECTING"

                            BluetoothProfile.STATE_DISCONNECTING ->
                                "DISCONNECTING"

                            BluetoothProfile.STATE_DISCONNECTED ->
                                "DISCONNECTED"

                            else ->
                                "UNKNOWN"
                        }

                    status.text =
                        "GATT ${safeName(g.device)}: $stateText (status=$statusCode)"

                    if (
                        newState ==
                        BluetoothProfile.STATE_CONNECTED
                    ) {

                        try {

                            val started =
                                g.discoverServices()

                            if (!started) {

                                status.text =
                                    "GATT connected, service discovery could not start"
                            }

                        } catch (e: SecurityException) {

                            status.text =
                                "Bluetooth permission denied"

                        } catch (e: Exception) {

                            status.text =
                                "Service discovery error: ${
                                    e.message ?: "Unknown error"
                                }"
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

            override fun onCharacteristicRead(
                g: BluetoothGatt,
                c: BluetoothGattCharacteristic,
                value: ByteArray,
                statusCode: Int
            ) {

                runOnUiThread {

                    addSection(
                        "GATT READ",
                        """
                        Characteristic: ${c.uuid}
                        Status: $statusCode
                        Value HEX: ${hex(value)}
                        Value UTF-8: ${printable(value)}
                        """.trimIndent()
                    )
                }
            }

            override fun onCharacteristicChanged(
                g: BluetoothGatt,
                c: BluetoothGattCharacteristic,
                value: ByteArray
            ) {

                runOnUiThread {

                    addSection(
                        "GATT NOTIFICATION",
                        """
                        Characteristic: ${c.uuid}
                        Value HEX: ${hex(value)}
                        Value UTF-8: ${printable(value)}
                        """.trimIndent()
                    )
                }
            }

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
                        Value HEX: ${hex(value)}
                        """.trimIndent()
                    )
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
        }

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

        addSection(
            "GATT DATABASE",
            """
            Device: ${safeName(g.device)}
            Status: $statusCode
            Services: ${services.size}
            """.trimIndent()
        )

        services.forEach { s ->

            addSection(
                "SERVICE • ${s.uuid}",
                buildString {

                    append(
                        "Instance ID: ${s.instanceId}\n"
                    )

                    append(
                        "Type: ${
                            if (
                                s.type ==
                                BluetoothGattService.SERVICE_TYPE_PRIMARY
                            ) {
                                "PRIMARY"
                            } else {
                                "SECONDARY"
                            }
                        }\n"
                    )

                    append(
                        "Characteristics: ${s.characteristics.size}\n\n"
                    )

                    s.characteristics.forEach { c ->

                        append(
                            "CHARACTERISTIC ${c.uuid}\n"
                        )

                        append(
                            "  Properties: ${
                                properties(c.properties)
                            }\n"
                        )

                        append(
                            "  Permissions: ${
                                permissions(c.permissions)
                            }\n"
                        )

                        append(
                            "  Write type: ${
                                c.writeType
                            }\n"
                        )

                        append(
                            "  Descriptors: ${
                                c.descriptors.size
                            }\n"
                        )

                        c.descriptors.forEach { d ->

                            append(
                                "    DESC ${d.uuid} permissions=${
                                    permissions(
                                        d.permissions
                                    )
                                }\n"
                            )
                        }

                        append("\n")
                    }
                }
            )

            addGattControls(
                g,
                s
            )
        }
    }

    private fun addGattControls(
        g: BluetoothGatt,
        service: BluetoothGattService
    ) {

        service.characteristics.forEach { c ->

            val row =
                LinearLayout(this).apply {
                    orientation =
                        LinearLayout.HORIZONTAL
                }

            if (
                c.properties and
                BluetoothGattCharacteristic.PROPERTY_READ != 0
            ) {

                val read =
                    button(
                        "Read ${c.uuid.toString().take(8)}"
                    )

                read.setOnClickListener {

                    if (!hasConnectPermission()) {

                        requestBluetoothPermissions()

                        status.text =
                            "Bluetooth Connect permission is required"

                        return@setOnClickListener
                    }

                    try {

                        val started =
                            g.readCharacteristic(c)

                        status.text =
                            if (started) {
                                "Reading ${c.uuid}…"
                            } else {
                                "Read could not be started: ${c.uuid}"
                            }

                    } catch (e: SecurityException) {

                        status.text =
                            "Bluetooth permission denied"

                    } catch (e: Exception) {

                        status.text =
                            e.message ?: "Read error"
                    }
                }

                row.addView(
                    read,
                    weightLp()
                )
            }

            if (
                c.properties and
                (
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY or
                        BluetoothGattCharacteristic.PROPERTY_INDICATE
                    ) != 0
            ) {

                val notify =
                    button(
                        "Notify ${c.uuid.toString().take(8)}"
                    )

                notify.setOnClickListener {

                    enableNotifications(
                        g,
                        c
                    )
                }

                row.addView(
                    notify,
                    weightLp()
                )
            }

            if (
                c.properties and
                (
                    BluetoothGattCharacteristic.PROPERTY_WRITE or
                        BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE
                    ) != 0
            ) {

                val test =
                    button("Write UI")

                test.setOnClickListener {

                    showWriteDialog(
                        g,
                        c
                    )
                }

                row.addView(
                    test,
                    weightLp()
                )
            }

            if (row.childCount > 0) {

                results.addView(
                    row,
                    lp(-1, -2)
                )
            }
        }
    }

    /*
     * Enable notifications/indications and also write the
     * standard CCC descriptor when available.
     */
    private fun enableNotifications(
        g: BluetoothGatt,
        c: BluetoothGattCharacteristic
    ) {

        if (!hasConnectPermission()) {

            requestBluetoothPermissions()

            status.text =
                "Bluetooth Connect permission is required"

            return
        }

        try {

            val enabled =
                g.setCharacteristicNotification(
                    c,
                    true
                )

            if (!enabled) {

                status.text =
                    "Notifications failed locally: ${c.uuid}"

                return
            }

            val descriptor =
                c.getDescriptor(
                    CCC_DESCRIPTOR_UUID
                )

            if (descriptor != null) {

                val value =
                    if (
                        c.properties and
                        BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
                    ) {
                        BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                    } else {
                        BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                    }

                if (Build.VERSION.SDK_INT >= 33) {

                    val result =
                        g.writeDescriptor(
                            descriptor,
                            value
                        )

                    status.text =
                        "Notification setup requested: ${c.uuid} (result=$result)"

                } else {

                    @Suppress("DEPRECATION")
                    descriptor.value = value

                    @Suppress("DEPRECATION")
                    val started =
                        g.writeDescriptor(descriptor)

                    status.text =
                        if (started) {
                            "Notification setup requested: ${c.uuid}"
                        } else {
                            "Notification descriptor write failed: ${c.uuid}"
                        }
                }

            } else {

                status.text =
                    "Notifications enabled locally: ${c.uuid}"
            }

        } catch (e: SecurityException) {

            status.text =
                "Bluetooth permission denied"

        } catch (e: Exception) {

            status.text =
                "Notify error: ${e.message ?: "Unknown error"}"
        }
    }

    private fun showWriteDialog(
        g: BluetoothGatt,
        c: BluetoothGattCharacteristic
    ) {

        if (!hasConnectPermission()) {

            requestBluetoothPermissions()

            status.text =
                "Bluetooth Connect permission is required"

            return
        }

        val input =
            EditText(this).apply {

                hint =
                    "HEX bytes, e.g. 01 FF 00"

                setSingleLine(true)
            }

        AlertDialog.Builder(this)

            .setTitle(
                "Write characteristic"
            )

            .setMessage(
                c.uuid.toString()
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
                            c.properties and
                            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
                        ) {

                            BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE

                        } else {

                            BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
                        }

                    if (Build.VERSION.SDK_INT >= 33) {

                        val result =
                            g.writeCharacteristic(
                                c,
                                bytes,
                                writeType
                            )

                        status.text =
                            "Write requested: ${c.uuid} (result=$result)"

                    } else {

                        @Suppress("DEPRECATION")
                        c.value = bytes

                        @Suppress("DEPRECATION")
                        val started =
                            g.writeCharacteristic(c)

                        status.text =
                            if (started) {
                                "Write requested: ${c.uuid}"
                            } else {
                                "Write could not be started: ${c.uuid}"
                            }
                    }

                } catch (e: SecurityException) {

                    status.text =
                        "Bluetooth permission denied"

                } catch (e: Exception) {

                    status.text =
                        "Write error: ${
                            e.message ?: "Unknown error"
                        }"
                }
            }

            .show()
    }

    /*
     * ---------------------------------------------------------
     * BLE SCAN CONTROL
     * ---------------------------------------------------------
     */

    private fun startBleScan() {

        if (!hasScanPermission()) {

            requestBluetoothPermissions()

            return
        }

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

        if (!enabled) {

            try {

                startActivity(
                    Intent(
                        BluetoothAdapter.ACTION_REQUEST_ENABLE
                    )
                )

            } catch (e: Exception) {

                status.text =
                    "Unable to request Bluetooth enable: ${
                        e.message ?: "Unknown error"
                    }"
            }

            return
        }

        if (scanning) {
            return
        }

        seen.clear()

        leScanner =
            try {

                a.bluetoothLeScanner

            } catch (e: SecurityException) {

                null
            }

        if (leScanner == null) {

            status.text =
                "BLE scanner unavailable"

            return
        }

        scanning = true

        status.text =
            "BLE scan running…"

        try {

            val settings =
                ScanSettings.Builder()
                    .setScanMode(
                        ScanSettings.SCAN_MODE_LOW_LATENCY
                    )
                    .build()

            leScanner?.startScan(
                null,
                settings,
                leCallback
            )

            handler.removeCallbacksAndMessages(null)

            handler.postDelayed(
                {
                    stopBleScan()
                },
                15000L
            )

        } catch (e: SecurityException) {

            scanning = false

            status.text =
                "Bluetooth scan permission denied"

        } catch (e: Exception) {

            scanning = false

            status.text =
                "BLE scan error: ${
                    e.message ?: "Unknown error"
                }"
        }
    }

    private fun stopBleScan() {

        handler.removeCallbacksAndMessages(null)

        if (!scanning) {
            return
        }

        try {

            leScanner?.stopScan(
                leCallback
            )

        } catch (_: SecurityException) {
        } catch (_: Exception) {
        }

        scanning = false

        status.text =
            "BLE scan stopped • ${seen.size} devices"
    }

    /*
     * ---------------------------------------------------------
     * PERMISSIONS
     * ---------------------------------------------------------
     */

    private fun requestBluetoothPermissions() {

        if (Build.VERSION.SDK_INT >= 31) {

            val permissions =
                arrayOf(
                    Manifest.permission.BLUETOOTH_SCAN,
                    Manifest.permission.BLUETOOTH_CONNECT
                )

            val missing =
                permissions.filter {
                    checkSelfPermission(it) !=
                        PackageManager.PERMISSION_GRANTED
                }

            if (missing.isNotEmpty()) {

                requestPermissions(
                    missing.toTypedArray(),
                    permissionRequest
                )
            }

        } else if (Build.VERSION.SDK_INT >= 23) {

            if (
                checkSelfPermission(
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) != PackageManager.PERMISSION_GRANTED
            ) {

                requestPermissions(
                    arrayOf(
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ),
                    permissionRequest
                )
            }
        }
    }

    private fun hasScanPermission(): Boolean {

        return if (Build.VERSION.SDK_INT >= 31) {

            checkSelfPermission(
                Manifest.permission.BLUETOOTH_SCAN
            ) == PackageManager.PERMISSION_GRANTED

        } else {

            Build.VERSION.SDK_INT < 23 ||
                checkSelfPermission(
                    Manifest.permission.ACCESS_FINE_LOCATION
                ) == PackageManager.PERMISSION_GRANTED
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

    private fun hasBtPermission(): Boolean {

        return hasScanPermission() &&
            hasConnectPermission()
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

        if (requestCode == permissionRequest) {

            if (
                grantResults.isNotEmpty() &&
                grantResults.all {
                    it == PackageManager.PERMISSION_GRANTED
                }
            ) {

                status.text =
                    "Bluetooth permissions granted"

            } else {

                status.text =
                    "Bluetooth permissions were not granted"
            }

            refreshLocalInfo()
        }
    }

    /*
     * ---------------------------------------------------------
     * UI SECTIONS
     * ---------------------------------------------------------
     */

    private fun addSection(
        title: String,
        body: String
    ) {

        val card =
            findOrCreateSection(title)

        card.removeAllViews()

        addHeader(
            card,
            title,
            ""
        )

        if (body.isNotBlank()) {

            addText(
                card,
                body
            )
        }
    }

    private fun findOrCreateSection(
        title: String
    ): LinearLayout {

        val existing =
            (0 until results.childCount)
                .map {
                    results.getChildAt(it)
                }
                .filterIsInstance<LinearLayout>()
                .firstOrNull {
                    (it.tag as? String) == title
                }

        if (existing != null) {
            return existing
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

        val t =
            TextView(this).apply {

                text = title

                textSize = 16f

                typeface =
                    android.graphics.Typeface.DEFAULT_BOLD

                setTextColor(
                    getColor(R.color.text)
                )
            }

        row.addView(
            t,
            LinearLayout.LayoutParams(
                0,
                -2,
                1f
            )
        )

        val tr =
            TextView(this).apply {

                text = trailing

                textSize = 12f

                setTextColor(
                    getColor(R.color.muted)
                )
            }

        row.addView(tr)

        parent.addView(row)
    }

    private fun addText(
        parent: LinearLayout,
        text: String
    ) {

        val v =
            TextView(this).apply {

                this.text = text

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

        parent.addView(v)
    }

    private fun button(
        label: String
    ): Button {

        return Button(this).apply {

            text = label

            textSize = 11f

            isAllCaps = false

            minHeight = dp(42)

            setPadding(
                dp(4),
                0,
                dp(4),
                0
            )
        }
    }

    private fun lp(
        w: Int,
        h: Int
    ): LinearLayout.LayoutParams {

        return LinearLayout.LayoutParams(
            w,
            h
        ).apply {

            setMargins(
                0,
                dp(5),
                0,
                dp(5)
            )
        }
    }

    private fun weightLp():
        LinearLayout.LayoutParams {

        return LinearLayout.LayoutParams(
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
    }

    private fun dp(
        value: Int
    ): Int {

        return (
            value *
                resources.displayMetrics.density
            ).roundToInt()
    }

    /*
     * ---------------------------------------------------------
     * SAFE BLUETOOTH HELPERS
     * ---------------------------------------------------------
     */

    private fun safeName(
        d: BluetoothDevice
    ): String {

        return try {

            d.name ?: "(unknown)"

        } catch (_: SecurityException) {

            "(permission)"
        }
    }

    private fun safeAddress(
        d: BluetoothDevice
    ): String {

        return try {

            d.address

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

    /*
     * ---------------------------------------------------------
     * GATT PROPERTY / PERMISSION FORMATTERS
     * ---------------------------------------------------------
     */

    private fun properties(
        p: Int
    ): String {

        val out =
            mutableListOf<String>()

        if (
            p and
            BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0
        ) {
            out += "BROADCAST"
        }

        if (
            p and
            BluetoothGattCharacteristic.PROPERTY_READ != 0
        ) {
            out += "READ"
        }

        if (
            p and
            BluetoothGattCharacteristic.PROPERTY_WRITE != 0
        ) {
            out += "WRITE"
        }

        if (
            p and
            BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0
        ) {
            out += "WRITE_NO_RESPONSE"
        }

        if (
            p and
            BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0
        ) {
            out += "NOTIFY"
        }

        if (
            p and
            BluetoothGattCharacteristic.PROPERTY_INDICATE != 0
        ) {
            out += "INDICATE"
        }

        if (
            p and
            BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0
        ) {
            out += "SIGNED_WRITE"
        }

        if (
            p and
            BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS != 0
        ) {
            out += "EXTENDED"
        }

        return out
            .joinToString(", ")
            .ifEmpty {
                "none"
            }
    }

    private fun permissions(
        p: Int
    ): String {

        val out =
            mutableListOf<String>()

        if (
            p and
            BluetoothGattCharacteristic.PERMISSION_READ != 0
        ) {
            out += "READ"
        }

        if (
            p and
            BluetoothGattCharacteristic.PERMISSION_WRITE != 0
        ) {
            out += "WRITE"
        }

        if (
            p and
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED != 0
        ) {
            out += "READ_ENCRYPTED"
        }

        if (
            p and
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED != 0
        ) {
            out += "WRITE_ENCRYPTED"
        }

        if (
            p and
            BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM != 0
        ) {
            out += "READ_MITM"
        }

        if (
            p and
            BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM != 0
        ) {
            out += "WRITE_MITM"
        }

        /*
         * Correct Android constant:
         * PERMISSION_WRITE_SIGNED
         *
         * NOT:
         * PERMISSION_SIGNED_WRITE
         */
        if (
            p and
            BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED != 0
        ) {
            out += "SIGNED_WRITE"
        }

        if (
            p and
            BluetoothGattCharacteristic.PERMISSION_WRITE_SIGNED_MITM != 0
        ) {
            out += "SIGNED_WRITE_MITM"
        }

        return out
            .joinToString(", ")
            .ifEmpty {
                "none"
            }
    }

    /*
     * ---------------------------------------------------------
     * DATA FORMATTERS
     * ---------------------------------------------------------
     */

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
                    "ID=${sparse.keyAt(i)}: ${
                        hex(
                            sparse.valueAt(i)
                        )
                    }\n"
                )
            }

        }.trim()
    }

    /*
     * ScanRecord.serviceData uses ParcelUuid keys.
     *
     * Map<*, ByteArray> deliberately avoids forcing UUID here.
     */
    private fun formatMap(
        map: Map<*, ByteArray>?
    ): String {

        if (map.isNullOrEmpty()) {
            return "none"
        }

        return map.entries.joinToString(
            "\n"
        ) {

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
        s: String
    ): ByteArray {

        if (s.isBlank()) {
            return ByteArray(0)
        }

        return s
            .trim()
            .split(
                Regex("[\\s,:-]+")
            )
            .filter {

                it.isNotBlank() &&
                    it.matches(
                        Regex("[0-9A-Fa-f]{1,2}")
                    )
            }
            .map {
                it.toInt(16).toByte()
            }
            .toByteArray()
    }

    /*
     * ---------------------------------------------------------
     * PARCELABLE COMPATIBILITY
     * ---------------------------------------------------------
     */

    private inline fun <reified T : Parcelable>
        Intent.getParcelableExtraCompat(
            key: String
        ): T? {

        return if (Build.VERSION.SDK_INT >= 33) {

            getParcelableExtra(
                key,
                T::class.java
            )

        } else {

            @Suppress("DEPRECATION")
            getParcelableExtra(key)
        }
    }

    companion object {

        /*
         * Standard Client Characteristic Configuration Descriptor.
         */
        private val CCC_DESCRIPTOR_UUID =
            java.util.UUID.fromString(
                "00002902-0000-1000-8000-00805F9B34FB"
            )
    }
}
