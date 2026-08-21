package com.example.bluetoothinspector

import android.Manifest
import android.app.Activity
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothGatt
import android.bluetooth.BluetoothGattCallback
import android.bluetooth.BluetoothGattCharacteristic
import android.bluetooth.BluetoothGattDescriptor
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothProfile
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Typeface
import android.hardware.input.InputManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.InputDevice
import android.view.KeyEvent
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.UUID

class MainActivity : Activity(), InputManager.InputDeviceListener {

    private val bluetoothManager by lazy {
        getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
    }

    private val inputManager by lazy {
        getSystemService(Context.INPUT_SERVICE) as InputManager
    }

    private var gatt: BluetoothGatt? = null
    private var connectedDevice: BluetoothDevice? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null

    private var receiving = false
    private var captureEnabled = false
    private var eventNumber = 0
    private var lastPacket: ByteArray? = null
    private var connecting = false

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var deviceName: TextView
    private lateinit var mode: TextView
    private lateinit var commandLog: TextView

    private val targetName = "glaze-4"

    /*
     * These UUIDs came from the previously observed BLE traffic.
     * They are searched only after we have selected glaze-4.
     */
    private val commandServiceUuid =
        UUID.fromString("00001000-0000-1000-8000-00805F9B34FB")

    private val commandCharacteristicUuid =
        UUID.fromString("00001002-0000-1000-8000-00805F9B34FB")

    private val hidServiceUuid =
        UUID.fromString("00001812-0000-1000-8000-00805F9B34FB")

    private val hidInputReportUuid =
        UUID.fromString("00002A4D-0000-1000-8000-00805F9B34FB")

    private val cccdUuid =
        UUID.fromString("00002902-0000-1000-8000-00805F9B34FB")

    /*
     * Poll only the system's GATT connected list and the Android input
     * device list. No BLE scan and no unrelated-device list are shown.
     */
    private val autoDetectRunnable = object : Runnable {
        override fun run() {
            autoDetectGlaze4()
            handler.postDelayed(this, 1000)
        }
    }

    private val gattCallback = object : BluetoothGattCallback() {

        override fun onConnectionStateChange(
            g: BluetoothGatt,
            statusCode: Int,
            newState: Int
        ) {
            if (newState == BluetoothProfile.STATE_CONNECTED) {
                connectedDevice = g.device
                connecting = false

                runOnUiThread {
                    setDeviceName(g.device)
                    mode.text = "GATT • جارٍ اكتشاف قناة الأزرار"
                    status.text = "متصل فعليًا — جارٍ تجهيز الاستقبال"
                    commandLog.text = "يتم الآن اكتشاف قناة الأوامر. انتظر قليلًا…"
                }

                try {
                    g.discoverServices()
                } catch (_: SecurityException) {
                    runOnUiThread {
                        status.text = "صلاحية Bluetooth Connect مطلوبة"
                    }
                }
                return
            }

            if (newState == BluetoothProfile.STATE_DISCONNECTED && gatt === g) {
                try {
                    g.close()
                } catch (_: Exception) {
                }

                gatt = null
                connectedDevice = null
                commandCharacteristic = null
                receiving = false
                connecting = false
                lastPacket = null

                runOnUiThread {
                    status.text = "في انتظار اتصال glaze-4…"
                    mode.text = "GATT / HID • تلقائي"
                    commandLog.text = "اتصل بـ glaze-4 من إعدادات Bluetooth."
                }
            }
        }

        override fun onServicesDiscovered(
            g: BluetoothGatt,
            statusCode: Int
        ) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread {
                    status.text = "اتصال GATT موجود لكن اكتشاف الخدمات فشل"
                    commandLog.text =
                        "لم نستطع قراءة خدمات glaze-4 عبر GATT."
                }
                return
            }

            val target = findBestCommandCharacteristic(g)

            if (target == null) {
                runOnUiThread {
                    mode.text = "GATT • لا توجد قناة إشعار مناسبة"
                    status.text = "متصل — بانتظار HID"
                    commandLog.text =
                        "لم نجد قناة GATT تستقبل أوامر الأزرار.\n\n" +
                        "إذا كان glaze-4 يستخدم HID التقليدي، سيظهر الإدخال " +
                        "عند ضغط أزراره أثناء تركيز التطبيق."
                }
                return
            }

            commandCharacteristic = target
            runOnUiThread {
                mode.text = "GATT • قناة الأزرار جاهزة"
                status.text = "متصل — جارٍ تفعيل الاستقبال"
                commandLog.text =
                    "قناة GATT جاهزة.\n\n" +
                    "يتم الآن تفعيل الإشعارات، ولن نسجل أي أمر قبل بدء الالتقاط."
            }
            enableNotifications(g, target)
        }

        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleGattPacket(characteristic, value)
        }

        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleGattPacket(
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

            if (statusCode == BluetoothGatt.GATT_SUCCESS) {
                receiving = true
                runOnUiThread {
                    mode.text = "GATT • استقبال الإشعارات فعال"
                    status.text = "جاهز — اضغط زر بدء الالتقاط"
                    commandLog.text =
                        "تم فتح قناة الأوامر بنجاح.\n\n" +
                        "لن نسجل شيئًا حتى تضغط «بدء الالتقاط»."
                }
            } else {
                receiving = false
                runOnUiThread {
                    status.text = "تعذر تفعيل إشعارات GATT"
                    commandLog.text =
                        "القناة موجودة لكن الجهاز رفض تفعيل الإشعارات."
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        buildCleanUi()
        requestConnectPermission()

        inputManager.registerInputDeviceListener(this, handler)
        handler.post(autoDetectRunnable)
    }

    override fun onResume() {
        super.onResume()
        autoDetectGlaze4()
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoDetectRunnable)

        try {
            inputManager.unregisterInputDeviceListener(this)
        } catch (_: Exception) {
        }

        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }

        gatt = null
        super.onDestroy()
    }

    /*
     * This is the important correction:
     * the old code was hard-coded to 9B94-BLE.
     *
     * We now select glaze-4 only. We never select the first random GATT
     * device and never perform a BLE scan.
     */
    private fun autoDetectGlaze4() {
        // Once the user starts capture, the capture screen owns the status area.
        // The 1-second auto detector must never overwrite it with a transient
        // "no GATT/HID" message.
        if (captureEnabled) return

        if (!hasConnectPermission()) {
            status.text = "اسمح للتطبيق بصلاحية Bluetooth"
            return
        }

        // HID input has priority. If Android already exposes glaze-4 as an
        // input device, do NOT try to open GATT for it.
        if (isHidInputPresent()) {
            if (gatt == null && !connecting) {
                setHidReadyUi()
            }
            return
        }

        if (connecting || gatt != null) return

        val activeGatt = try {
            bluetoothManager.getConnectedDevices(BluetoothProfile.GATT)
        } catch (_: Exception) {
            emptyList()
        }

        // First choice: a GATT connection that Android already reports.
        val glazeGatt = activeGatt.firstOrNull { matchesGlaze4(it) }
        if (glazeGatt != null) {
            connectGattToGlaze4(glazeGatt)
            return
        }

        val bonded = try {
            bluetoothManager.adapter.bondedDevices.toList()
        } catch (_: Exception) {
            emptyList()
        }

        val glaze = bonded.firstOrNull { matchesGlaze4(it) }
        if (glaze == null) {
            status.text = "في انتظار اتصال glaze-4…"
            mode.text = "HID / GATT • تلقائي"
            deviceName.text = targetName
            commandLog.text =
                "اتصل بـ glaze-4 من إعدادات Bluetooth.\n\n" +
                "لن يبحث التطبيق عن أجهزة أخرى."
            return
        }

        setDeviceName(glaze)

        when (glaze.type) {
            BluetoothDevice.DEVICE_TYPE_CLASSIC -> {
                // Classic Bluetooth cannot be opened with connectGatt().
                // Wait for Android's HID input stack instead.
                status.text = "glaze-4 متصل — بانتظار HID"
                mode.text = "Bluetooth Classic / HID"
                commandLog.text =
                    "تم العثور على glaze-4 كـ Bluetooth Classic.\n\n" +
                    "لن نحاول فتح GATT لهذا الجهاز.\n" +
                    "اضغط «بدء التقاط الأوامر» ثم اضغط زرًا في الجهاز."
            }

            BluetoothDevice.DEVICE_TYPE_LE,
            BluetoothDevice.DEVICE_TYPE_DUAL -> {
                status.text = "glaze-4 — جارٍ فتح GATT…"
                mode.text =
                    if (glaze.type == BluetoothDevice.DEVICE_TYPE_DUAL)
                        "Dual • GATT تلقائي"
                    else
                        "BLE • GATT تلقائي"
                commandLog.text =
                    "تم اختيار glaze-4 فقط.\n" +
                    "جارٍ فتح قناة GATT…"
                connectGattToGlaze4(glaze)
            }

            else -> {
                status.text = "نوع glaze-4 غير معروف"
                mode.text = "Bluetooth • غير محدد"
                commandLog.text =
                    "Android لم يحدد نوع النقل لهذا الجهاز.\n\n" +
                    "لن يتم فتح GATT عشوائيًا."
            }
        }
    }

    private fun matchesGlaze4(device: BluetoothDevice): Boolean {
        if (!hasConnectPermission()) return false

        val name = try {
            device.name.orEmpty()
        } catch (_: Exception) {
            ""
        }

        return normalize(name) == normalize(targetName)
    }

    private fun normalize(value: String): String =
        value.trim()
            .replace("-", "")
            .replace("_", "")
            .replace(" ", "")
            .lowercase()

    private fun isLikelyHid(device: BluetoothDevice): Boolean {
        if (Build.VERSION.SDK_INT < 33) return false

        return try {
            device.bluetoothClass?.hasService(
                android.bluetooth.BluetoothClass.Service.RENDER
            ) == false &&
                device.bluetoothClass?.majorDeviceClass ==
                android.bluetooth.BluetoothClass.Device.Major.PERIPHERAL
        } catch (_: Exception) {
            false
        }
    }

    private fun connectGattToGlaze4(device: BluetoothDevice) {
        if (!hasConnectPermission()) return
        if (connecting || gatt != null) return

        // Never force GATT on a Classic-only device.
        if (device.type == BluetoothDevice.DEVICE_TYPE_CLASSIC) {
            connecting = false
            runOnUiThread {
                setDeviceName(device)
                status.text = "glaze-4 متصل — بانتظار HID"
                mode.text = "Bluetooth Classic / HID"
                commandLog.text =
                    "هذا الجهاز Classic Bluetooth وليس GATT.\n\n" +
                    "اضغط «بدء التقاط الأوامر» ثم اضغط زرًا في glaze-4."
            }
            return
        }

        connecting = true
        setDeviceName(device)

        runOnUiThread {
            status.text = "glaze-4 — جارٍ فتح GATT…"
            mode.text = "GATT • اتصال تلقائي"
            commandLog.text =
                "تم اختيار glaze-4 فقط.\nجارٍ فتح قناة GATT…"
        }

        try {
            // For Dual devices, explicitly request LE. For LE devices this is
            // also the correct transport. Classic devices were rejected above.
            gatt = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                device.connectGatt(
                    this,
                    false,
                    gattCallback,
                    BluetoothDevice.TRANSPORT_LE
                )
            } else {
                @Suppress("DEPRECATION")
                device.connectGatt(this, false, gattCallback)
            }

            // Do not leave the UI stuck forever if the stack never calls back.
            handler.postDelayed({
                if (connecting && gatt != null) {
                    try {
                        gatt?.disconnect()
                        gatt?.close()
                    } catch (_: Exception) {
                    }
                    gatt = null
                    connecting = false
                    runOnUiThread {
                        status.text = "تعذر فتح GATT لـ glaze-4"
                        mode.text = "HID / GATT • تلقائي"
                        commandLog.text =
                            "لم يستجب glaze-4 لاتصال GATT.\n\n" +
                            "إذا كان الجهاز يعمل كـ HID، سيظهر عند توفره في Android."
                    }
                }
            }, 12000L)
        } catch (_: Exception) {
            connecting = false
            gatt = null

            runOnUiThread {
                status.text = "تعذر فتح GATT لـ glaze-4"
                mode.text = "HID / GATT • تلقائي"
                commandLog.text =
                    "لم يفتح glaze-4 كـ GATT.\n\n" +
                    "لن يتم الاتصال بأي جهاز آخر."
            }
        }
    }

    private fun findBestCommandCharacteristic(
        g: BluetoothGatt
    ): BluetoothGattCharacteristic? {

        val exact =
            g.getService(commandServiceUuid)
                ?.getCharacteristic(commandCharacteristicUuid)

        if (exact != null && isNotificationCharacteristic(exact)) {
            return exact
        }

        val hidInput =
            g.getService(hidServiceUuid)
                ?.getCharacteristic(hidInputReportUuid)

        if (hidInput != null && isNotificationCharacteristic(hidInput)) {
            return hidInput
        }

        /*
         * Last resort is still restricted to notification/indication
         * characteristics. Nothing is displayed to the user.
         */
        return g.services
            .flatMap { it.characteristics }
            .firstOrNull { isNotificationCharacteristic(it) }
    }

    private fun isNotificationCharacteristic(
        characteristic: BluetoothGattCharacteristic
    ): Boolean {
        val p = characteristic.properties
        return (p and BluetoothGattCharacteristic.PROPERTY_NOTIFY) != 0 ||
            (p and BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0
    }

    private fun enableNotifications(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {
        if (!hasConnectPermission()) return

        try {
            if (!g.setCharacteristicNotification(characteristic, true)) {
                return
            }

            val descriptor = characteristic.getDescriptor(cccdUuid)
            if (descriptor == null) {
                runOnUiThread {
                    status.text = "قناة GATT بلا CCCD"
                }
                return
            }

            val indicationOnly =
                (characteristic.properties and
                    BluetoothGattCharacteristic.PROPERTY_INDICATE) != 0 &&
                (characteristic.properties and
                    BluetoothGattCharacteristic.PROPERTY_NOTIFY) == 0

            descriptor.value =
                if (indicationOnly) {
                    BluetoothGattDescriptor.ENABLE_INDICATION_VALUE
                } else {
                    BluetoothGattDescriptor.ENABLE_NOTIFICATION_VALUE
                }

            if (!g.writeDescriptor(descriptor)) {
                runOnUiThread {
                    status.text = "تعذر بدء إشعارات GATT"
                }
            }
        } catch (_: Exception) {
            runOnUiThread {
                status.text = "تعذر تفعيل قناة GATT"
            }
        }
    }

    private fun handleGattPacket(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {
        if (!captureEnabled || !receiving || value.isEmpty()) return

        val expected = commandCharacteristic
        if (expected != null && characteristic.uuid != expected.uuid) return

        val previous = lastPacket

        if (previous != null && previous.contentEquals(value)) return

        lastPacket = value.clone()
        eventNumber++

        val hex = value.joinToString(" ") {
            "%02X".format(it.toInt() and 0xFF)
        }

        val changed =
            if (previous == null) {
                "أول أمر"
            } else {
                value.indices
                    .filter { i ->
                        i >= previous.size || value[i] != previous[i]
                    }
                    .joinToString(", ") { (it + 1).toString() }
                    .ifEmpty { "لا يوجد" }
            }

        showCaptured(
            title = "GATT COMMAND #$eventNumber",
            body =
                "RAW HEX:\n$hex\n\n" +
                    "البايتات المتغيرة: $changed"
        )
    }

    /*
     * HID fallback:
     *
     * Android public Bluetooth APIs do not expose a generic "HID host raw
     * input-report callback" to an ordinary app. BluetoothHidDevice is the
     * HID-device role (our phone acting as a HID device), not a raw HID-host
     * sniffer. Therefore the safe public fallback is to capture the KeyEvent
     * delivered by Android when glaze-4 is acting as a keyboard/remote.
     */
    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        if (captureEnabled && isLikelyExternalInput(event)) {
            val action =
                when (event.action) {
                    KeyEvent.ACTION_DOWN -> "DOWN"
                    KeyEvent.ACTION_UP -> "UP"
                    else -> event.action.toString()
                }

            val source = "0x%08X".format(event.source)

            showCaptured(
                title = "HID INPUT #${++eventNumber}",
                body =
                    "KEY CODE: ${event.keyCode}\n" +
                        "ACTION: $action\n" +
                        "SOURCE: $source\n" +
                        "SCAN CODE: ${event.scanCode}"
            )
        }

        return super.dispatchKeyEvent(event)
    }

    override fun dispatchGenericMotionEvent(event: android.view.MotionEvent): Boolean {
        if (captureEnabled) {
            val source = event.source
            val external =
                (source and InputDevice.SOURCE_JOYSTICK) != 0 ||
                (source and InputDevice.SOURCE_GAMEPAD) != 0 ||
                (source and InputDevice.SOURCE_DPAD) != 0

            if (external) {
                val device = event.device
                val axes = listOf(
                    android.view.MotionEvent.AXIS_X,
                    android.view.MotionEvent.AXIS_Y,
                    android.view.MotionEvent.AXIS_Z,
                    android.view.MotionEvent.AXIS_RZ,
                    android.view.MotionEvent.AXIS_HAT_X,
                    android.view.MotionEvent.AXIS_HAT_Y
                )
                val values = axes.joinToString(" | ") { axis ->
                    "A$axis=${event.getAxisValue(axis)}"
                }

                showCaptured(
                    title = "HID MOTION #${++eventNumber}",
                    body =
                        "DEVICE: ${device?.name ?: "unknown"}\n" +
                        "SOURCE: 0x%08X\n".format(source) +
                        "ACTION: ${event.action}\n" +
                        values
                )
                return true
            }
        }
        return super.dispatchGenericMotionEvent(event)
    }

    private fun isLikelyExternalInput(event: KeyEvent): Boolean {
        val source = event.source

        return (source and InputDevice.SOURCE_KEYBOARD) != 0 ||
            (source and InputDevice.SOURCE_DPAD) != 0 ||
            (source and InputDevice.SOURCE_GAMEPAD) != 0 ||
            (source and InputDevice.SOURCE_JOYSTICK) != 0
    }

    private fun isHidInputPresent(): Boolean {
        return try {
            InputDevice.getDeviceIds().any { id ->
                val input = InputDevice.getDevice(id) ?: return@any false
                val name = input.name.orEmpty()

                normalize(name) == normalize(targetName) &&
                    isExternalInputDevice(input)
            }
        } catch (_: Exception) {
            false
        }
    }

    private fun isExternalInputDevice(input: InputDevice): Boolean {
        val sources = input.sources
        return (sources and InputDevice.SOURCE_KEYBOARD) != 0 ||
            (sources and InputDevice.SOURCE_DPAD) != 0 ||
            (sources and InputDevice.SOURCE_GAMEPAD) != 0 ||
            (sources and InputDevice.SOURCE_JOYSTICK) != 0
    }

    private fun updateHidPresence() {
        if (isHidInputPresent()) {
            setHidReadyUi()
        }
    }

    private fun setHidReadyUi() {
        runOnUiThread {
            setDeviceName(targetName)
            mode.text = "HID • إدخال النظام"
            status.text =
                if (captureEnabled)
                    "HID متصل — الالتقاط فعال"
                else
                    "HID متصل — جاهز لبدء الالتقاط"

            if (!captureEnabled) {
                commandLog.text =
                    "تم اكتشاف glaze-4 كجهاز إدخال HID من Android.\n\n" +
                    "اضغط «بدء الالتقاط»، ثم اضغط الزر المطلوب.\n" +
                    "سيعرض التطبيق الحدث الذي سلّمه Android للتطبيق."
            }
        }
    }

    override fun onInputDeviceAdded(deviceId: Int) {
        updateHidPresence()
    }

    override fun onInputDeviceRemoved(deviceId: Int) {
        if (!isHidInputPresent() && gatt == null) {
            runOnUiThread {
                status.text = "في انتظار اتصال glaze-4…"
                mode.text = "GATT / HID • تلقائي"
            }
        }
    }

    override fun onInputDeviceChanged(deviceId: Int) {
        updateHidPresence()
    }

    private fun startCapture() {
        captureEnabled = true
        window.decorView.isFocusableInTouchMode = true
        window.decorView.requestFocus()
        eventNumber = 0
        lastPacket = null

        if (receiving) {
            status.text = "التقاط GATT فعال"
            commandLog.text =
                "اضغط زرًا واحدًا فقط في glaze-4.\n\n" +
                "سيظهر RAW HEX هنا."
        } else if (isHidInputPresent()) {
            status.text = "التقاط HID فعال"
            commandLog.text =
                "اضغط زرًا واحدًا فقط في glaze-4.\n\n" +
                "سيظهر أي KeyEvent أو MotionEvent يسلّمه Android من glaze-4.\n" +
                "لن تتغير هذه الشاشة تلقائيًا أثناء الالتقاط."
        } else {
            status.text = "في انتظار قناة الأزرار"
            commandLog.text =
                "الجهاز محدد: glaze-4\n\n" +
                "لا توجد قناة GATT إشعار ولا HID input ظاهر في واجهة Android العامة.\n\n" +
                "سيبقى الالتقاط فعالًا ولن يختفي هذا التنبيه. إذا سلّم Android زرًا للتطبيق فسيظهر فورًا."
        }
    }

    private fun stopCapture() {
        captureEnabled = false
        status.text = "الالتقاط متوقف"
        commandLog.text =
            "لم يعد التطبيق يسجل ضغطات الأزرار.\n\n" +
            "يمكنك الضغط على «بدء الالتقاط» مرة أخرى."
    }

    private fun clearCapture() {
        eventNumber = 0
        lastPacket = null
        commandLog.text =
            "السجل فارغ.\n\nاضغط زرًا في glaze-4 بعد بدء الالتقاط."
    }

    private fun showCaptured(title: String, body: String) {
        runOnUiThread {
            status.text = "تم التقاط إشارة #$eventNumber"
            commandLog.text =
                "$title\n\n$body\n\n" +
                "اضغط زرًا آخر في glaze-4."
        }
    }

    private fun setDeviceName(device: BluetoothDevice) {
        val name =
            try {
                device.name?.takeIf { it.isNotBlank() } ?: targetName
            } catch (_: SecurityException) {
                targetName
            }

        runOnUiThread {
            deviceName.text = name
        }
    }

    private fun setDeviceName(name: String) {
        runOnUiThread {
            deviceName.text = name
        }
    }

    private fun buildCleanUi() {
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF090D12.toInt())
            setPadding(dp(18), dp(18), dp(18), dp(18))
        }

        root.addView(
            makeText("Bluetooth Command Capture", 25f, true),
            params(-1, -2)
        )

        status = makeText("في انتظار اتصال glaze-4…", 16f, true).apply {
            setTextColor(0xFF6EE78A.toInt())
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(14), dp(16), dp(14), dp(16))
            setBackgroundColor(0xFF121A24.toInt())
        }

        root.addView(
            status,
            marginParams(-1, -2, 0, 14, 0, 8)
        )

        deviceName = makeText(targetName, 23f, true).apply {
            gravity = Gravity.CENTER
        }

        root.addView(
            deviceName,
            marginParams(-1, -2, 0, 0, 0, 4)
        )

        mode = makeText("GATT / HID • تلقائي", 15f, false).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFF9DA9B8.toInt())
        }

        root.addView(
            mode,
            marginParams(-1, -2, 0, 0, 0, 16)
        )

        val start = makeButton("▶  بدء التقاط الأوامر")
        start.setOnClickListener { startCapture() }

        root.addView(
            start,
            marginParams(-1, -2, 0, 0, 0, 8)
        )

        val stop = makeButton("■  إيقاف الالتقاط")
        stop.setOnClickListener { stopCapture() }

        root.addView(
            stop,
            marginParams(-1, -2, 0, 0, 0, 8)
        )

        val clear = makeButton("مسح السجل")
        clear.setOnClickListener { clearCapture() }

        root.addView(
            clear,
            marginParams(-1, -2, 0, 0, 0, 14)
        )

        commandLog = makeText(
            "اتصل بـ glaze-4 من إعدادات Bluetooth.\n\n" +
                "سيختار التطبيق glaze-4 فقط تلقائيًا.",
            17f,
            false
        ).apply {
            typeface = Typeface.MONOSPACE
            setTextColor(0xFFE8EDF3.toInt())
            setPadding(dp(16), dp(18), dp(16), dp(18))
            setBackgroundColor(0xFF111820.toInt())
        }

        val scroll = ScrollView(this).apply {
            addView(commandLog)
        }

        root.addView(
            scroll,
            LinearLayout.LayoutParams(-1, 0, 1f)
        )

        setContentView(root)
    }

    private fun makeButton(text: String): TextView {
        return makeText(text, 16f, true).apply {
            gravity = Gravity.CENTER
            setTextColor(0xFFFFFFFF.toInt())
            setPadding(dp(12), dp(14), dp(12), dp(14))
            setBackgroundColor(0xFF5B5B60.toInt())
            isClickable = true
        }
    }

    private fun requestConnectPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT),
                2001
            )
        }
    }

    private fun hasConnectPermission(): Boolean {
        return Build.VERSION.SDK_INT < Build.VERSION_CODES.S ||
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun makeText(
        value: String,
        size: Float,
        bold: Boolean
    ): TextView {
        return TextView(this).apply {
            text = value
            textSize = size
            setTextColor(0xFFE8EDF3.toInt())
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
    }

    private fun params(
        width: Int,
        height: Int
    ) = LinearLayout.LayoutParams(width, height)

    private fun marginParams(
        width: Int,
        height: Int,
        left: Int,
        top: Int,
        right: Int,
        bottom: Int
    ) = LinearLayout.LayoutParams(width, height).apply {
        setMargins(
            dp(left),
            dp(top),
            dp(right),
            dp(bottom)
        )
    }

    private fun dp(value: Int): Int =
        (value * resources.displayMetrics.density).toInt()
}
