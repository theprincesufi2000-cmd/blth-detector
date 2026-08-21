package com.example.bluetoothinspector

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.*
import android.bluetooth.le.*
import android.content.*
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.os.*
import android.view.Gravity
import android.widget.*
import java.io.BufferedWriter
import java.io.OutputStreamWriter
import java.nio.charset.Charset
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import kotlin.math.roundToInt

/**
 * Bluetooth Inspector Pro
 *
 * Includes:
 *  - BLE scanning / GATT discovery
 *  - READ / WRITE / WRITE_NO_RESPONSE
 *  - NOTIFY / INDICATE subscription
 *  - Protocol Lab for precise HEX command composition
 *  - Controlled command probing with a hard probe limit and Stop button
 *  - Full timestamped protocol log + JSON/CSV export
 *
 * Important: a GATT characteristic tells us how to communicate, not what every
 * byte means. The Protocol Lab therefore records evidence rather than inventing
 * command meanings. Exact semantics must be established from observed behavior,
 * vendor documentation, or controlled experiments on the device.
 */
class MainActivity : Activity() {

    private lateinit var root: LinearLayout
    private lateinit var content: LinearLayout
    private lateinit var status: TextView
    private lateinit var deviceTitle: TextView
    private lateinit var tabRow: LinearLayout

    private val btManager by lazy { getSystemService(BLUETOOTH_SERVICE) as BluetoothManager }
    private val adapter: BluetoothAdapter? get() = btManager.adapter
    private var scanner: BluetoothLeScanner? = null
    private var scanning = false
    private var gatt: BluetoothGatt? = null
    private var connectedDevice: BluetoothDevice? = null
    private val seen = linkedMapOf<String, ScanResult>()
    private val handler = Handler(Looper.getMainLooper())
    private val permissionRequest = 9001

    private val services = mutableListOf<BluetoothGattService>()
    private val writable = mutableListOf<BluetoothGattCharacteristic>()
    private val notifyables = mutableListOf<BluetoothGattCharacteristic>()

    private val preferredTxUuid = UUID.fromString("00001001-0000-1000-8000-00805F9B34FB")
    private val preferredRxUuid = UUID.fromString("00001002-0000-1000-8000-00805F9B34FB")

    private lateinit var serviceSpinner: Spinner
    private lateinit var writeSpinner: Spinner
    private lateinit var notifySpinner: Spinner
    private lateinit var hexInput: EditText
    private lateinit var asciiInput: EditText
    private lateinit var probeSeed: EditText
    private lateinit var probeInfo: TextView
    private lateinit var logView: TextView
    private var probeRunning = false
    private var probeQueue = ArrayDeque<ByteArray>()
    private var probeIndex = 0
    private var probeTimer: Runnable? = null

    private data class LogEntry(
        val time: String,
        val direction: String,
        val uuid: String,
        val hex: String,
        val text: String,
        val status: String
    )

    private val protocolLog = mutableListOf<LogEntry>()

    private val uuidReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != BluetoothDevice.ACTION_UUID) return
            val d = intent.getParcelableExtraCompat<BluetoothDevice>(BluetoothDevice.EXTRA_DEVICE) ?: return
            val uuids = try { d.uuids?.map { it.uuid.toString() } ?: emptyList() } catch (_: Exception) { emptyList() }
            addLog("SDP", "${d.address}", uuids.joinToString(" "), "", "UUID discovery")
            showDashboard()
        }
    }

    private val scanCallback = object : ScanCallback() {
        override fun onScanResult(callbackType: Int, result: ScanResult) {
            runOnUiThread {
                seen[result.device.address] = result
                showDashboard()
            }
        }
        override fun onBatchScanResults(results: MutableList<ScanResult>) {
            runOnUiThread {
                results.forEach { seen[it.device.address] = it }
                showDashboard()
            }
        }
        override fun onScanFailed(errorCode: Int) {
            runOnUiThread { status.text = "BLE scan failed • error=$errorCode" }
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {
        override fun onConnectionStateChange(g: BluetoothGatt, statusCode: Int, newState: Int) {
            runOnUiThread {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    gatt = g
                    connectedDevice = g.device
                    status.text = "CONNECTED • ${safeName(g.device)}"
                    addLog("STATE", "", "", "", "GATT connected status=$statusCode")
                    try { g.discoverServices() } catch (e: Exception) { status.text = "Service discovery error: ${e.message}" }
                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    status.text = "DISCONNECTED • ${safeName(g.device)}"
                    addLog("STATE", "", "", "", "GATT disconnected status=$statusCode")
                    services.clear(); writable.clear(); notifyables.clear()
                    gatt = null
                    connectedDevice = null
                    showDashboard()
                }
            }
        }

        override fun onServicesDiscovered(g: BluetoothGatt, statusCode: Int) {
            runOnUiThread {
                services.clear(); writable.clear(); notifyables.clear()
                services.addAll(try { g.services } catch (_: Exception) { emptyList() })
                services.forEach { s ->
                    s.characteristics.forEach { c ->
                        if (c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) writable += c
                        if (c.properties and (BluetoothGattCharacteristic.PROPERTY_NOTIFY or BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0) notifyables += c
                    }
                }
                writable.sortWith(compareByDescending<BluetoothGattCharacteristic> { it.uuid == preferredTxUuid }.thenBy { it.uuid.toString() })
                notifyables.sortWith(compareByDescending<BluetoothGattCharacteristic> { it.uuid == preferredRxUuid }.thenBy { it.uuid.toString() })
                status.text = "GATT READY • ${services.size} services • ${writable.size} writable • ${notifyables.size} notify/indicate"
                addLog("STATE", "", "", "", "Services discovered status=$statusCode services=${services.size}")
                showGatt()
            }
        }

        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray, statusCode: Int) {
            runOnUiThread { handleIncoming("READ", c, value, statusCode) }
        }

        @Deprecated("Android 13 compatibility")
        override fun onCharacteristicRead(g: BluetoothGatt, c: BluetoothGattCharacteristic, statusCode: Int) {
            if (Build.VERSION.SDK_INT < 33) {
                val value = c.value ?: ByteArray(0)
                runOnUiThread { handleIncoming("READ", c, value, statusCode) }
            }
        }

        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic, value: ByteArray) {
            runOnUiThread { handleIncoming("NOTIFY", c, value, 0) }
        }

        @Deprecated("Android 13 compatibility")
        override fun onCharacteristicChanged(g: BluetoothGatt, c: BluetoothGattCharacteristic) {
            if (Build.VERSION.SDK_INT < 33) {
                val value = c.value ?: ByteArray(0)
                runOnUiThread { handleIncoming("NOTIFY", c, value, 0) }
            }
        }

        override fun onCharacteristicWrite(g: BluetoothGatt, c: BluetoothGattCharacteristic, statusCode: Int) {
            runOnUiThread {
                addLog("WRITE_RESULT", c.uuid.toString(), "", "", "status=$statusCode")
                status.text = if (statusCode == BluetoothGatt.GATT_SUCCESS) "WRITE OK • ${c.uuid}" else "WRITE FAILED • status=$statusCode"
                if (probeRunning) scheduleNextProbe()
            }
        }

        override fun onDescriptorWrite(g: BluetoothGatt, d: BluetoothGattDescriptor, statusCode: Int) {
            runOnUiThread { addLog("DESCRIPTOR", d.uuid.toString(), hex(d.value), printable(d.value), "write status=$statusCode") }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = getColor(R.color.bg)
        window.navigationBarColor = getColor(R.color.bg)
        registerReceiver(uuidReceiver, IntentFilter(BluetoothDevice.ACTION_UUID), RECEIVER_NOT_EXPORTED)
        buildUi()
        requestBluetoothPermissions()
        showDashboard()
    }

    override fun onDestroy() {
        stopBleScan()
        stopProbe()
        try { unregisterReceiver(uuidReceiver) } catch (_: Exception) {}
        try { gatt?.close() } catch (_: Exception) {}
        gatt = null
        super.onDestroy()
    }

    private fun buildUi() {
        root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.bg))
            setPadding(dp(14), dp(10), dp(14), dp(12))
        }

        deviceTitle = TextView(this).apply {
            text = "Bluetooth Inspector Pro"
            textSize = 25f
            setTextColor(getColor(R.color.text))
            typeface = Typeface.DEFAULT_BOLD
        }
        root.addView(deviceTitle, lp(-1, -2))

        val sub = TextView(this).apply {
            text = "BLE • GATT • Protocol Lab • HEX Console • Evidence Log"
            textSize = 12f
            setTextColor(getColor(R.color.muted))
            setPadding(0, dp(3), 0, dp(8))
        }
        root.addView(sub, lp(-1, -2))

        status = TextView(this).apply {
            textSize = 13f
            setTextColor(getColor(R.color.green))
            setPadding(dp(12), dp(10), dp(12), dp(10))
            setBackgroundColor(getColor(R.color.panel))
        }
        root.addView(status, lp(-1, -2))

        tabRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        addTab("Dashboard") { showDashboard() }
        addTab("GATT") { showGatt() }
        addTab("Protocol Lab") { showProtocolLab() }
        addTab("Log") { showLog() }
        root.addView(tabRow, lp(-1, -2))

        val scroll = ScrollView(this)
        content = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, dp(8), 0, dp(28))
        }
        scroll.addView(content)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
    }

    private fun addTab(label: String, action: () -> Unit) {
        val b = button(label)
        b.textSize = 10f
        b.setOnClickListener { action() }
        tabRow.addView(b, weightLp())
    }

    private fun showDashboard() {
        content.removeAllViews()
        val a = adapter
        val enabled = try { a?.isEnabled == true } catch (_: Exception) { false }
        status.text = "Bluetooth ${if (enabled) "ON" else "OFF"} • ${seen.size} BLE devices • ${if (gatt != null) "GATT connected" else "No GATT connection"}"
        addCard("DEVICE / ADAPTER", """
            Adapter: ${if (a == null) "unavailable" else tryName(a)}
            Adapter address: ${if (a == null) "n/a" else tryAddress(a)}
            Android: ${Build.VERSION.RELEASE} • API ${Build.VERSION.SDK_INT}
            BLE supported: ${packageManager.hasSystemFeature("android.hardware.bluetooth_le")}
            Connected: ${connectedDevice?.let { "${safeName(it)} • ${safeAddress(it)}" } ?: "none"}
        """.trimIndent())

        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val scan = button(if (scanning) "Stop Scan" else "Scan BLE")
        val paired = button("Paired")
        val refresh = button("Refresh")
        actions.addView(scan, weightLp()); actions.addView(paired, weightLp()); actions.addView(refresh, weightLp())
        scan.setOnClickListener { if (scanning) stopBleScan() else startBleScan() }
        paired.setOnClickListener { renderPairedInDashboard() }
        refresh.setOnClickListener { showDashboard() }
        content.addView(actions, lp(-1, -2))

        if (connectedDevice != null) {
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val g = button("Open GATT")
            val p = button("Open Protocol Lab")
            row.addView(g, weightLp()); row.addView(p, weightLp())
            g.setOnClickListener { showGatt() }; p.setOnClickListener { showProtocolLab() }
            content.addView(row, lp(-1, -2))
        }

        val scanCard = addCard("LIVE BLE DEVICES", "${seen.size} unique address(es)")
        if (seen.isEmpty()) addText(scanCard, "No BLE advertisements captured. Tap Scan BLE.")
        seen.values.sortedByDescending { it.rssi }.forEach { result ->
            val d = result.device
            val text = buildString {
                append("${safeName(d)}\n")
                append("${safeAddress(d)} • RSSI ${result.rssi} dBm\n")
                append("Connectable: ${if (Build.VERSION.SDK_INT >= 26) result.isConnectable else "unknown"}\n")
                append("Type: ${typeName(d.type)}\n")
                result.scanRecord?.let { rec ->
                    append("Service UUIDs: ${rec.serviceUuids?.joinToString { it.uuid.toString() } ?: "none"}\n")
                    append("Manufacturer: ${formatManufacturer(rec.manufacturerSpecificData)}\n")
                }
            }
            addText(scanCard, text)
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val c = button("Connect GATT"); val dBtn = button("Details")
            row.addView(c, weightLp()); row.addView(dBtn, weightLp())
            c.setOnClickListener { connectGatt(d) }
            dBtn.setOnClickListener { showBleDetails(result) }
            scanCard.addView(row, lp(-1, -2))
        }
    }

    private fun renderPairedInDashboard() {
        val card = addCard("BONDED / PAIRED DEVICES", "")
        val devices = try { adapter?.bondedDevices?.toList() ?: emptyList() } catch (_: Exception) { emptyList() }
        if (devices.isEmpty()) { addText(card, "No paired devices visible to this application."); return }
        devices.sortedBy { safeName(it) }.forEach { d ->
            addText(card, "${safeName(d)} • ${safeAddress(d)} • ${typeName(d.type)}")
            val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
            val c = button("Connect GATT"); val s = button("Inspect SDP")
            row.addView(c, weightLp()); row.addView(s, weightLp())
            c.setOnClickListener { connectGatt(d) }; s.setOnClickListener { inspectSdp(d) }
            card.addView(row, lp(-1, -2))
        }
    }

    private fun showGatt() {
        content.removeAllViews()
        status.text = if (gatt != null) "GATT CONNECTED • ${safeName(gatt!!.device)}" else "No GATT connection"
        if (gatt == null) {
            addCard("GATT", "Connect to a BLE device from Dashboard first.")
            return
        }
        val top = addCard("GATT DATABASE", "${services.size} services • ${writable.size} writable • ${notifyables.size} notify/indicate")
        val actions = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val refresh = button("Refresh Services"); val lab = button("Protocol Lab")
        actions.addView(refresh, weightLp()); actions.addView(lab, weightLp())
        refresh.setOnClickListener { try { gatt?.discoverServices() } catch (_: Exception) {} }
        lab.setOnClickListener { showProtocolLab() }
        top.addView(actions, lp(-1, -2))

        services.forEach { service ->
            val card = addCard("SERVICE • ${service.uuid}", serviceName(service.uuid))
            service.characteristics.forEach { c -> renderCharacteristic(card, c) }
        }
    }

    private fun renderCharacteristic(parent: LinearLayout, c: BluetoothGattCharacteristic) {
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(10), dp(10), dp(10), dp(10))
            setBackgroundColor(getColor(R.color.bg))
        }
        addText(box, "CHARACTERISTIC\nUUID: ${c.uuid}\nName: ${characteristicName(c.uuid)}\nProperties: ${properties(c.properties)}\nPermissions: ${permissions(c.permissions)}\nWrite type: ${writeTypeName(c.writeType)}\nDescriptors: ${c.descriptors.size}")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        if (c.properties and BluetoothGattCharacteristic.PROPERTY_READ != 0) {
            val b = button("Read"); b.setOnClickListener { readCharacteristic(c) }; row.addView(b, weightLp())
        }
        if (c.properties and (BluetoothGattCharacteristic.PROPERTY_WRITE or BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE) != 0) {
            val b = button("Write"); b.setOnClickListener { showWriteDialog(c) }; row.addView(b, weightLp())
        }
        if (c.properties and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) {
            val b = button("Notify ON"); b.setOnClickListener { enableSubscription(c, false) }; row.addView(b, weightLp())
        }
        if (c.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) {
            val b = button("Indicate ON"); b.setOnClickListener { enableSubscription(c, true) }; row.addView(b, weightLp())
        }
        box.addView(row, lp(-1, -2))
        if (c.descriptors.isNotEmpty()) addText(box, c.descriptors.joinToString("\n") { "Descriptor ${it.uuid}" })
        parent.addView(box, lp(-1, -2))
    }

    private fun showProtocolLab() {
        content.removeAllViews()
        status.text = if (gatt != null) "Protocol Lab • ${safeName(gatt!!.device)}" else "Protocol Lab • connect a BLE device first"

        val intro = addCard("PROTOCOL LAB", "This is the precision workspace for composing, sending, observing and documenting device-specific GATT commands.")
        addText(intro, "The app never assumes that a UUID reveals the command meaning. Every learned command is backed by a write result, read result, notification, or your saved observation.")

        if (gatt == null || writable.isEmpty()) {
            addText(intro, "No writable characteristic is currently available. Connect and discover GATT services first.")
            return
        }

        val selector = addCard("CHANNEL SELECTION", "Choose exactly where commands are written and where responses are observed.")
        serviceSpinner = Spinner(this)
        val names = writable.mapIndexed { i, c -> "WRITE[$i] ${c.uuid} • ${properties(c.properties)}" }
        serviceSpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, names)
        selector.addView(serviceSpinner, lp(-1, -2))
        writeSpinner = serviceSpinner

        notifySpinner = Spinner(this)
        val nNames = if (notifyables.isEmpty()) listOf("No NOTIFY/INDICATE characteristic") else notifyables.mapIndexed { i, c -> "RX[$i] ${c.uuid} • ${properties(c.properties)}" }
        notifySpinner.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, nNames)
        selector.addView(notifySpinner, lp(-1, -2))

        val compose = addCard("COMMAND COMPOSER", "HEX is authoritative. ASCII is a convenience view.")
        hexInput = EditText(this).apply {
            hint = "HEX  •  01 FF 00"
            setSingleLine(false)
            minLines = 2
            typeface = Typeface.MONOSPACE
        }
        asciiInput = EditText(this).apply {
            hint = "ASCII  •  optional"
            setSingleLine(false)
            minLines = 2
        }
        compose.addView(hexInput, lp(-1, -2)); compose.addView(asciiInput, lp(-1, -2))
        val sync = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val asciiToHex = button("ASCII → HEX"); val hexToAscii = button("HEX → ASCII"); val clear = button("Clear")
        sync.addView(asciiToHex, weightLp()); sync.addView(hexToAscii, weightLp()); sync.addView(clear, weightLp())
        asciiToHex.setOnClickListener { hexInput.setText(hex(asciiInput.text.toString().toByteArray(Charsets.UTF_8))) }
        hexToAscii.setOnClickListener { asciiInput.setText(printable(parseHex(hexInput.text.toString()))) }
        clear.setOnClickListener { hexInput.setText(""); asciiInput.setText("") }
        compose.addView(sync, lp(-1, -2))

        val send = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val write = button("WRITE"); val writeNr = button("WRITE NO RESPONSE"); val subscribe = button("SUBSCRIBE RX")
        send.addView(write, weightLp()); send.addView(writeNr, weightLp()); send.addView(subscribe, weightLp())
        write.setOnClickListener { sendHex(BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT) }
        writeNr.setOnClickListener { sendHex(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE) }
        subscribe.setOnClickListener { selectedNotify()?.let { enableSubscription(it, it.properties and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) } }
        compose.addView(send, lp(-1, -2))

        val analysis = addCard("COMMAND ANALYSIS", "Protocol evidence, not guesses")
        val crcRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val crc8 = button("CRC-8"); val crc16 = button("CRC-16/IBM"); val crc32 = button("CRC-32")
        crcRow.addView(crc8, weightLp()); crcRow.addView(crc16, weightLp()); crcRow.addView(crc32, weightLp())
        crc8.setOnClickListener { showChecksum("CRC-8", crc8(parseHex(hexInput.text.toString()))) }
        crc16.setOnClickListener { showChecksum("CRC-16/IBM", crc16(parseHex(hexInput.text.toString()))) }
        crc32.setOnClickListener { showChecksum("CRC-32", crc32(parseHex(hexInput.text.toString()))) }
        analysis.addView(crcRow, lp(-1, -2))
        addText(analysis, "Use checksums only when the device protocol evidence indicates one. A checksum that happens to look plausible is not proof of a command format.")

        val probe = addCard("CONTROLLED PROTOCOL DISCOVERY", "Generate a small, auditable set of byte mutations around a seed command.")
        probeSeed = EditText(this).apply { hint = "Seed HEX  •  01 00"; setSingleLine(true); typeface = Typeface.MONOSPACE }
        probe.addView(probeSeed, lp(-1, -2))
        probeInfo = TextView(this).apply { textSize = 12f; setTextColor(getColor(R.color.muted)); text = "Max 32 probes per run. Stop is always available." }
        probe.addView(probeInfo, lp(-1, -2))
        val probeRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val build = button("Build Candidates"); val run = button("START PROBE"); val stop = button("STOP")
        probeRow.addView(build, weightLp()); probeRow.addView(run, weightLp()); probeRow.addView(stop, weightLp())
        build.setOnClickListener { buildProbeCandidates() }
        run.setOnClickListener { startProbe() }
        stop.setOnClickListener { stopProbe() }
        probe.addView(probeRow, lp(-1, -2))
        addText(probe, "Probe design: for each byte position, generate low-risk boundary/value mutations (00, 01, 02, 7F, 80, FF) plus the original seed. This discovers response differences; it does not claim semantic meanings.")

        val favorites = addCard("SAVED COMMANDS", "Store exact HEX strings as reproducible commands.")
        val saveRow = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val save = button("Save Current"); val history = button("Show Writes"); val export = button("Export JSON")
        saveRow.addView(save, weightLp()); saveRow.addView(history, weightLp()); saveRow.addView(export, weightLp())
        save.setOnClickListener { saveCommand() }; history.setOnClickListener { showLog() }; export.setOnClickListener { exportLog(false) }
        favorites.addView(saveRow, lp(-1, -2))
    }

    private fun selectedWrite(): BluetoothGattCharacteristic? = writable.getOrNull(serviceSpinner.selectedItemPosition)
    private fun selectedNotify(): BluetoothGattCharacteristic? = notifyables.getOrNull(notifySpinner.selectedItemPosition)

    private fun sendHex(forceType: Int? = null, bytesOverride: ByteArray? = null) {
        val g = gatt ?: run { status.text = "Not connected"; return }
        val c = selectedWrite() ?: run { status.text = "No writable characteristic selected"; return }
        val bytes = bytesOverride ?: parseHex(hexInput.text.toString())
        if (bytes.isEmpty()) { status.text = "Enter valid HEX bytes"; return }
        val type = forceType ?: if (c.properties and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE else BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT
        try {
            val result = if (Build.VERSION.SDK_INT >= 33) {
                g.writeCharacteristic(c, bytes, type)
            } else {
                @Suppress("DEPRECATION") c.writeType = type
                @Suppress("DEPRECATION") c.value = bytes
                @Suppress("DEPRECATION") if (g.writeCharacteristic(c)) BluetoothGatt.GATT_SUCCESS else -1
            }
            addLog("WRITE", c.uuid.toString(), hex(bytes), printable(bytes), "request=$result type=${writeTypeName(type)}")
            status.text = "WRITE REQUEST • ${hex(bytes)} • ${c.uuid}"
        } catch (e: Exception) { status.text = "Write error: ${e.message}"; addLog("ERROR", c.uuid.toString(), hex(bytes), printable(bytes), e.message ?: "write error") }
    }

    private fun readCharacteristic(c: BluetoothGattCharacteristic) {
        try { gatt?.readCharacteristic(c); addLog("READ_REQUEST", c.uuid.toString(), "", "", "requested") } catch (e: Exception) { status.text = "Read error: ${e.message}" }
    }

    private fun enableSubscription(c: BluetoothGattCharacteristic, indicate: Boolean) {
        val g = gatt ?: return
        try {
            if (!g.setCharacteristicNotification(c, true)) { status.text = "Local notification registration failed"; return }
            val cccd = c.descriptors.firstOrNull { it.uuid == CCCD_UUID }
            if (cccd == null) { status.text = "CCCD not exposed by device"; return }
            val value = if (indicate) BluetoothGattDescriptor.ENABLE_INDICATION_VALUE else BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
            if (Build.VERSION.SDK_INT >= 33) g.writeDescriptor(cccd, value) else { @Suppress("DEPRECATION") run { cccd.value = value; g.writeDescriptor(cccd) } }
            addLog("SUBSCRIBE", c.uuid.toString(), hex(value), "", if (indicate) "indication requested" else "notification requested")
            status.text = if (indicate) "INDICATION SUBSCRIPTION REQUESTED" else "NOTIFICATION SUBSCRIPTION REQUESTED"
        } catch (e: Exception) { status.text = "Subscription error: ${e.message}" }
    }

    private fun handleIncoming(kind: String, c: BluetoothGattCharacteristic, value: ByteArray, statusCode: Int) {
        addLog(kind, c.uuid.toString(), hex(value), printable(value), "status=$statusCode")
        status.text = "$kind • ${hex(value)}"
        if (::logView.isInitialized) logView.text = buildLogText()
    }

    private fun buildProbeCandidates() {
        val seed = parseHex(probeSeed.text.toString())
        if (seed.isEmpty()) { probeInfo.text = "Enter a seed HEX command first."; return }
        val out = LinkedHashMap<String, ByteArray>()
        out[hex(seed)] = seed
        val values = intArrayOf(0x00, 0x01, 0x02, 0x7F, 0x80, 0xFF)
        for (i in seed.indices) {
            for (v in values) {
                val x = seed.copyOf(); x[i] = v.toByte(); out.putIfAbsent(hex(x), x)
                if (out.size >= 32) break
            }
            if (out.size >= 32) break
        }
        probeQueue = ArrayDeque(out.values)
        probeIndex = 0
        probeInfo.text = "${probeQueue.size} candidates ready • seed=${hex(seed)}"
        addLog("PROBE_PLAN", "", "", "", "built ${probeQueue.size} candidates")
    }

    private fun startProbe() {
        if (probeQueue.isEmpty()) buildProbeCandidates()
        if (probeQueue.isEmpty()) return
        if (probeRunning) return
        probeRunning = true
        probeIndex = 0
        probeInfo.text = "Probe running • 0/${probeQueue.size}"
        runNextProbe()
    }

    private fun runNextProbe() {
        if (!probeRunning) return
        if (probeQueue.isEmpty()) { stopProbe(); return }
        val bytes = probeQueue.removeFirst()
        probeIndex++
        probeInfo.text = "Probe $probeIndex • ${hex(bytes)} • remaining ${probeQueue.size}"
        sendHex(BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE, bytes)
        scheduleNextProbe()
    }

    private fun scheduleNextProbe() {
        if (!probeRunning) return
        probeTimer?.let { handler.removeCallbacks(it) }
        val r = Runnable { runNextProbe() }
        probeTimer = r
        handler.postDelayed(r, 450)
    }

    private fun stopProbe() {
        probeRunning = false
        probeTimer?.let { handler.removeCallbacks(it) }
        probeTimer = null
        probeInfoSafe("Probe stopped • ${probeIndex} sent")
        addLog("PROBE", "", "", "", "stopped after $probeIndex probes")
    }

    private fun probeInfoSafe(text: String) { if (::probeInfo.isInitialized) probeInfo.text = text }

    private fun saveCommand() {
        val bytes = parseHex(hexInput.text.toString())
        if (bytes.isEmpty()) { status.text = "Nothing to save"; return }
        val key = "SAVED_COMMAND_${SimpleDateFormat("HHmmss", Locale.US).format(Date())}"
        getPreferences(MODE_PRIVATE).edit().putString(key, hex(bytes)).apply()
        addLog("SAVE", selectedWrite()?.uuid?.toString() ?: "", hex(bytes), printable(bytes), key)
        status.text = "Saved • ${hex(bytes)}"
    }

    private fun showLog() {
        content.removeAllViews()
        status.text = "Evidence Log • ${protocolLog.size} events"
        val card = addCard("PROTOCOL EVIDENCE LOG", "Every read/write/notification is timestamped.")
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        val clear = button("Clear Log"); val exportJson = button("Export JSON"); val exportCsv = button("Export CSV")
        row.addView(clear, weightLp()); row.addView(exportJson, weightLp()); row.addView(exportCsv, weightLp())
        clear.setOnClickListener { protocolLog.clear(); showLog() }
        exportJson.setOnClickListener { exportLog(false) }
        exportCsv.setOnClickListener { exportLog(true) }
        card.addView(row, lp(-1, -2))
        logView = TextView(this).apply {
            textSize = 11f; typeface = Typeface.MONOSPACE; setTextColor(getColor(R.color.text)); setPadding(0, dp(8), 0, 0)
            text = buildLogText()
        }
        card.addView(logView, lp(-1, -2))
    }

    private fun buildLogText(): String {
        if (protocolLog.isEmpty()) return "No protocol events yet."
        return protocolLog.joinToString("\n\n") { "${it.time}  ${it.direction}\nUUID: ${it.uuid}\nHEX: ${it.hex}\nTEXT: ${it.text}\nSTATUS: ${it.status}" }
    }

    private fun addLog(direction: String, uuid: String, bytesHex: String, text: String, statusText: String) {
        val time = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US).format(Date())
        protocolLog += LogEntry(time, direction, uuid, bytesHex, text, statusText)
        if (::logView.isInitialized) logView.text = buildLogText()
    }

    private fun exportLog(csv: Boolean = false) {
        if (protocolLog.isEmpty()) { status.text = "Log is empty"; return }
        val intent = Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            type = if (csv) "text/csv" else "application/json"
            putExtra(Intent.EXTRA_TITLE, if (csv) "bluetooth_protocol_log.csv" else "bluetooth_protocol_log.json")
        }
        intent.putExtra("export_csv", csv)
        startActivityForResult(intent, 7101)
    }

    @Deprecated("Activity Result compatibility for API 26+")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        if (requestCode != 7101 || resultCode != RESULT_OK || data?.data == null) return
        val csv = data.getBooleanExtra("export_csv", false)
        try {
            contentResolver.openOutputStream(data.data!!)?.use { os ->
                BufferedWriter(OutputStreamWriter(os, Charsets.UTF_8)).use { w ->
                    if (csv) {
                        w.write("time,direction,uuid,hex,text,status\n")
                        protocolLog.forEach { e ->
                            w.write(listOf(e.time, e.direction, e.uuid, e.hex, e.text, e.status).joinToString(",") { csvField(it) })
                            w.write("\n")
                        }
                    } else {
                        w.write("{\n  \"device\": \"${json(connectedDevice?.let { safeName(it) } ?: "unknown")}\",\n")
                        w.write("  \"address\": \"${json(connectedDevice?.let { safeAddress(it) } ?: "")}\",\n")
                        w.write("  \"gatt\": ")
                        w.write(gattSnapshotJson())
                        w.write(",\n  \"events\": [\n")
                        protocolLog.forEachIndexed { i, e ->
                            val comma = if (i == protocolLog.lastIndex) "" else ","
                            w.write("    {\"time\":\"${json(e.time)}\",\"direction\":\"${json(e.direction)}\",\"uuid\":\"${json(e.uuid)}\",\"hex\":\"${json(e.hex)}\",\"text\":\"${json(e.text)}\",\"status\":\"${json(e.status)}\"}$comma\n")
                        }
                        w.write("  ]\n}\n")
                    }
                }
            }
            status.text = if (csv) "CSV exported" else "JSON exported"
        } catch (e: Exception) { status.text = "Export error: ${e.message}" }
    }

    private fun json(s: String): String = s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n")

    private fun csvField(s: String): String = "\"${s.replace("\"", "\"\"").replace("\n", " ")}\""

    private fun gattSnapshotJson(): String {
        val items = services.flatMap { s -> s.characteristics.map { c ->
            "{\"service\":\"${json(s.uuid.toString())}\",\"characteristic\":\"${json(c.uuid.toString())}\",\"properties\":\"${json(properties(c.properties))}\",\"descriptors\":${c.descriptors.size}}"
        }}
        return "[${items.joinToString(",")}]"
    }

    private fun showChecksum(name: String, value: String) {
        AlertDialog.Builder(this).setTitle(name).setMessage(value).setPositiveButton("Use as HEX suffix") { _, _ ->
            val old = parseHex(hexInput.text.toString())
            hexInput.setText(hex(old) + (if (old.isNotEmpty()) " " else "") + value)
        }.setNegativeButton("Close", null).show()
    }

    private fun connectGatt(device: BluetoothDevice) {
        if (!hasConnectPermission()) { requestBluetoothPermissions(); return }
        try {
            stopProbe()
            gatt?.close()
            gatt = if (Build.VERSION.SDK_INT >= 23) device.connectGatt(this, false, gattCallback, BluetoothDevice.TRANSPORT_LE) else @Suppress("DEPRECATION") device.connectGatt(this, false, gattCallback)
            status.text = "Connecting GATT • ${safeName(device)}"
            addLog("STATE", "", "", "", "GATT connect requested ${safeAddress(device)}")
        } catch (e: Exception) { status.text = "GATT connection error: ${e.message}" }
    }

    private fun inspectSdp(device: BluetoothDevice) {
        if (!hasConnectPermission()) { requestBluetoothPermissions(); return }
        try { device.fetchUuidsWithSdp(); status.text = "SDP UUID discovery requested" } catch (e: Exception) { status.text = "SDP error: ${e.message}" }
    }

    private fun startBleScan() {
        if (!hasBtPermission()) { requestBluetoothPermissions(); return }
        val a = adapter ?: return
        if (!a.isEnabled) { startActivity(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE)); return }
        if (scanning) return
        seen.clear(); scanner = a.bluetoothLeScanner; scanning = true
        status.text = "BLE scan running…"
        try {
            scanner?.startScan(null, ScanSettings.Builder().setScanMode(ScanSettings.SCAN_MODE_LOW_LATENCY).build(), scanCallback)
            handler.postDelayed({ stopBleScan() }, 15000)
        } catch (e: Exception) { scanning = false; status.text = "Scan error: ${e.message}" }
        showDashboard()
    }

    private fun stopBleScan() {
        if (!scanning) return
        try { scanner?.stopScan(scanCallback) } catch (_: Exception) {}
        scanning = false
        status.text = "BLE scan stopped • ${seen.size} devices"
        if (::content.isInitialized) showDashboard()
    }

    private fun showBleDetails(result: ScanResult) {
        val rec = result.scanRecord
        AlertDialog.Builder(this).setTitle("BLE Advertisement")
            .setMessage(buildString {
                append("Name: ${safeName(result.device)}\n")
                append("Address: ${safeAddress(result.device)}\n")
                append("RSSI: ${result.rssi} dBm\n")
                append("TX power: ${result.txPower}\n")
                if (rec != null) {
                    append("\nService UUIDs:\n${rec.serviceUuids?.joinToString("\n") { it.uuid.toString() } ?: "none"}\n")
                    append("\nService data:\n${formatMap(rec.serviceData)}\n")
                    append("\nManufacturer:\n${formatManufacturer(rec.manufacturerSpecificData)}\n")
                    append("\nRAW:\n${hex(rec.bytes)}")
                }
            }).setPositiveButton("Close", null).show()
    }

    private fun addCard(title: String, trailing: String): LinearLayout {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(13), dp(12), dp(13), dp(12))
            setBackgroundColor(getColor(R.color.panel))
        }
        val header = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER_VERTICAL }
        val t = TextView(this).apply { text = title; textSize = 15f; typeface = Typeface.DEFAULT_BOLD; setTextColor(getColor(R.color.text)) }
        header.addView(t, LinearLayout.LayoutParams(0, -2, 1f))
        val tr = TextView(this).apply { text = trailing; textSize = 11f; setTextColor(getColor(R.color.muted)) }
        header.addView(tr)
        card.addView(header)
        content.addView(card, lp(-1, -2))
        return card
    }

    private fun addText(parent: LinearLayout, text: String) {
        parent.addView(TextView(this).apply {
            this.text = text; textSize = 12f; setTextColor(getColor(R.color.text)); setPadding(0, dp(8), 0, 0); typeface = Typeface.MONOSPACE
        }, lp(-1, -2))
    }

    private fun button(label: String) = Button(this).apply { text = label; textSize = 11f; isAllCaps = false; minHeight = dp(42); setPadding(dp(3), 0, dp(3), 0) }
    private fun lp(w: Int, h: Int) = LinearLayout.LayoutParams(w, h).apply { setMargins(0, dp(4), 0, dp(4)) }
    private fun weightLp() = LinearLayout.LayoutParams(0, dp(46), 1f).apply { setMargins(dp(2), dp(3), dp(2), dp(3)) }
    private fun dp(v: Int) = (v * resources.displayMetrics.density).roundToInt()

    private fun safeName(d: BluetoothDevice): String = try { d.name ?: "(unknown)" } catch (_: SecurityException) { "(permission)" }
    private fun safeAddress(d: BluetoothDevice): String = try { d.address } catch (_: SecurityException) { "(permission)" }
    private fun tryName(a: BluetoothAdapter): String = try { a.name ?: "(unknown)" } catch (_: SecurityException) { "(permission)" }
    private fun tryAddress(a: BluetoothAdapter): String = try { a.address } catch (_: SecurityException) { "(restricted)" }
    private fun typeName(t: Int) = when (t) { BluetoothDevice.DEVICE_TYPE_CLASSIC -> "CLASSIC"; BluetoothDevice.DEVICE_TYPE_LE -> "BLE"; BluetoothDevice.DEVICE_TYPE_DUAL -> "DUAL"; else -> "UNKNOWN" }

    private fun properties(p: Int): String = buildList {
        if (p and BluetoothGattCharacteristic.PROPERTY_BROADCAST != 0) add("BROADCAST")
        if (p and BluetoothGattCharacteristic.PROPERTY_READ != 0) add("READ")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE != 0) add("WRITE")
        if (p and BluetoothGattCharacteristic.PROPERTY_WRITE_NO_RESPONSE != 0) add("WRITE_NO_RESPONSE")
        if (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY != 0) add("NOTIFY")
        if (p and BluetoothGattCharacteristic.PROPERTY_INDICATE != 0) add("INDICATE")
        if (p and BluetoothGattCharacteristic.PROPERTY_SIGNED_WRITE != 0) add("SIGNED_WRITE")
        if (p and BluetoothGattCharacteristic.PROPERTY_EXTENDED_PROPS != 0) add("EXTENDED")
    }.joinToString(", ").ifEmpty { "NONE" }

    private fun permissions(p: Int): String = buildList {
        if (p and BluetoothGattCharacteristic.PERMISSION_READ != 0) add("READ")
        if (p and BluetoothGattCharacteristic.PERMISSION_WRITE != 0) add("WRITE")
        if (p and BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED != 0) add("READ_ENCRYPTED")
        if (p and BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED != 0) add("WRITE_ENCRYPTED")
        if (p and BluetoothGattCharacteristic.PERMISSION_READ_ENCRYPTED_MITM != 0) add("READ_MITM")
        if (p and BluetoothGattCharacteristic.PERMISSION_WRITE_ENCRYPTED_MITM != 0) add("WRITE_MITM")
    }.joinToString(", ").ifEmpty { "NONE" }

    private fun writeTypeName(type: Int) = when (type) { BluetoothGattCharacteristic.WRITE_TYPE_DEFAULT -> "WRITE"; BluetoothGattCharacteristic.WRITE_TYPE_NO_RESPONSE -> "WRITE_NO_RESPONSE"; BluetoothGattCharacteristic.WRITE_TYPE_SIGNED -> "SIGNED_WRITE"; else -> type.toString() }

    private fun serviceName(uuid: UUID) = when (uuid.toString().lowercase()) {
        "00001800-0000-1000-8000-00805f9b34fb" -> "Generic Access"
        "00001801-0000-1000-8000-00805f9b34fb" -> "Generic Attribute"
        "0000180a-0000-1000-8000-00805f9b34fb" -> "Device Information Service"
        "0000180f-0000-1000-8000-00805f9b34fb" -> "Battery Service"
        "00001812-0000-1000-8000-00805f9b34fb" -> "Human Interface Device"
        else -> "Unknown / Custom UUID"
    }

    private fun characteristicName(uuid: UUID) = when (uuid.toString().lowercase()) {
        "00002a00-0000-1000-8000-00805f9b34fb" -> "Device Name"
        "00002a01-0000-1000-8000-00805f9b34fb" -> "Appearance"
        "00002a19-0000-1000-8000-00805f9b34fb" -> "Battery Level"
        "00002a29-0000-1000-8000-00805f9b34fb" -> "Manufacturer Name String"
        "00002a24-0000-1000-8000-00805f9b34fb" -> "Model Number String"
        "00002a25-0000-1000-8000-00805f9b34fb" -> "Serial Number String"
        "00002a26-0000-1000-8000-00805f9b34fb" -> "Firmware Revision String"
        "00002a27-0000-1000-8000-00805f9b34fb" -> "Hardware Revision String"
        "00002a28-0000-1000-8000-00805f9b34fb" -> "Software Revision String"
        "00002a29-0000-1000-8000-00805f9b34fb" -> "Manufacturer Name String"
        "00002a19-0000-1000-8000-00805f9b34fb" -> "Battery Level"
        else -> "Custom / Unknown"
    }

    private fun formatManufacturer(sparse: android.util.SparseArray<ByteArray>?): String {
        if (sparse == null || sparse.size() == 0) return "none"
        return buildString { for (i in 0 until sparse.size()) append("ID=${sparse.keyAt(i)}: ${hex(sparse.valueAt(i))}\n") }.trim()
    }
    private fun formatMap(map: Map<ParcelUuid, ByteArray>?): String = if (map.isNullOrEmpty()) "none" else map.entries.joinToString("\n") { "${it.key}: ${hex(it.value)}" }
    private fun hex(bytes: ByteArray?): String = bytes?.joinToString(" ") { "%02X".format(it.toInt() and 0xFF) } ?: ""
    private fun printable(bytes: ByteArray?): String = try { bytes?.toString(Charset.forName("UTF-8"))?.replace(Regex("[^\\x20-\\x7E]"), ".") ?: "" } catch (_: Exception) { "" }
    private fun parseHex(s: String): ByteArray = s.trim().replace(Regex("[,:-]"), " ").split(Regex("\\s+")).filter { it.matches(Regex("[0-9A-Fa-f]{1,2}")) }.map { it.toInt(16).toByte() }.toByteArray()

    private fun crc8(bytes: ByteArray): String { var crc = 0; bytes.forEach { crc = crc xor (it.toInt() and 0xFF); repeat(8) { crc = if ((crc and 0x80) != 0) ((crc shl 1) xor 0x07) and 0xFF else (crc shl 1) and 0xFF } }; return "%02X".format(crc) }
    private fun crc16(bytes: ByteArray): String { var crc = 0x0000; bytes.forEach { crc = crc xor (it.toInt() and 0xFF); repeat(8) { crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xA001 else crc ushr 1 } }; return "%04X".format(crc and 0xFFFF) }
    private fun crc32(bytes: ByteArray): String { var crc = 0xFFFFFFFF.toInt(); bytes.forEach { crc = crc xor (it.toInt() and 0xFF); repeat(8) { crc = if ((crc and 1) != 0) (crc ushr 1) xor 0xEDB88320.toInt() else crc ushr 1 } }; return "%08X".format(crc.inv()) }

    private val CCCD_UUID = UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    private fun hasBtPermission(): Boolean = if (Build.VERSION.SDK_INT >= 31) checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN) == PackageManager.PERMISSION_GRANTED else checkSelfPermission(Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
    private fun hasConnectPermission(): Boolean = Build.VERSION.SDK_INT < 31 || checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED
    private fun requestBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= 31) requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT), permissionRequest)
        else if (Build.VERSION.SDK_INT >= 23) requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION), permissionRequest)
    }

    private inline fun <reified T : Parcelable> Intent.getParcelableExtraCompat(key: String): T? = if (Build.VERSION.SDK_INT >= 33) getParcelableExtra(key, T::class.java) else @Suppress("DEPRECATION") getParcelableExtra(key)
}
