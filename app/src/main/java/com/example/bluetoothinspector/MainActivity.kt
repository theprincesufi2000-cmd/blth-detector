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
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.graphics.Typeface
import android.view.Gravity
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import java.util.UUID

class MainActivity : Activity() {

    private val bluetoothManager by lazy {
        getSystemService(BLUETOOTH_SERVICE) as BluetoothManager
    }

    private var gatt: BluetoothGatt? = null
    private var connectedDevice: BluetoothDevice? = null
    private var commandCharacteristic: BluetoothGattCharacteristic? = null
    private var receiving = false
    private var eventNumber = 0
    private var lastPacket: ByteArray? = null
    private var connecting = false

    private val handler = Handler(Looper.getMainLooper())

    private lateinit var status: TextView
    private lateinit var deviceName: TextView
    private lateinit var commandLog: TextView

    /*
     * From the device you showed previously:
     *
     * Service:        00001000-0000-1000-8000-00805F9B34FB
     * Notify command: 00001002-0000-1000-8000-00805F9B34FB
     *
     * This channel produced the real 7-byte button commands such as:
     * 54 51 2B 3C 7F 7F 7F
     *
     * We therefore search this channel FIRST.
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
     * No Bluetooth scan is used.
     *
     * Android exposes the devices currently connected to the GATT profile
     * through BluetoothManager. We poll that list so the app can discover
     * the device that is ALREADY connected in Android Bluetooth settings.
     */
    private val autoDetectRunnable = object : Runnable {
        override fun run() {
            findAlreadyConnectedDevice()
            handler.postDelayed(this, 1000)
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
                    connecting = false

                    setDeviceName(g.device)
                    status.text = "متصل — البحث عن قناة الأزرار…"

                    try {
                        g.discoverServices()
                    } catch (_: SecurityException) {
                        status.text = "صلاحية Bluetooth Connect مطلوبة"
                    }

                } else if (newState == BluetoothProfile.STATE_DISCONNECTED) {
                    if (gatt === g) {
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

                        status.text = "في انتظار الجهاز المتصل…"
                        commandLog.text =
                            "اتصل بالجهاز من إعدادات Bluetooth.\n" +
                            "سيتم اكتشافه تلقائياً."
                    }
                }
            }
        }

        override fun onServicesDiscovered(
            g: BluetoothGatt,
            statusCode: Int
        ) {
            if (statusCode != BluetoothGatt.GATT_SUCCESS) {
                runOnUiThread {
                    status.text = "تعذر قراءة خدمات الجهاز"
                    commandLog.text =
                        "تم الاتصال، لكن Android لم يكمل اكتشاف خدمات GATT."
                }
                return
            }

            /*
             * IMPORTANT:
             * We do not display the services to the user.
             * They are searched internally only to find the command channel.
             */
            val target = findBestCommandCharacteristic(g)

            if (target == null) {
                runOnUiThread {
                    status.text = "متصل — لم يتم العثور على قناة أزرار"
                    commandLog.text =
                        "تم الاتصال بالجهاز، لكن لم نجد قناة إشعارات " +
                        "للأوامر.\n\n" +
                        "لن نعرض خدمات أو UUIDs أو بيانات غير مرتبطة."
                }
                return
            }

            commandCharacteristic = target

            runOnUiThread {
                status.text = "متصل — قناة الأزرار جاهزة"
                commandLog.text =
                    "جاهز لاستقبال أوامر الأزرار.\n\n" +
                    "اضغط أي زر في الجهاز الأصلي."
            }

            enableNotifications(g, target)
        }

        // Android 13+
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic,
            value: ByteArray
        ) {
            handleCommandPacket(characteristic, value)
        }

        // Older Android
        @Deprecated("Deprecated in Android 13")
        override fun onCharacteristicChanged(
            g: BluetoothGatt,
            characteristic: BluetoothGattCharacteristic
        ) {
            handleCommandPacket(
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
                    status.text = "متصل — جاهز لاستقبال الأوامر"
                    commandLog.text =
                        "اضغط أي زر في الجهاز الأصلي الآن."
                } else {
                    receiving = false
                    status.text = "تعذر تشغيل قناة الأزرار"
                    commandLog.text =
                        "تم العثور على قناة الأزرار، لكن Android " +
                        "لم يستطع تفعيل الإشعارات."
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        buildCleanUi()

        requestConnectPermission()

        /*
         * Start immediately. No Search button.
         */
        handler.post(autoDetectRunnable)
    }

    override fun onResume() {
        super.onResume()
        findAlreadyConnectedDevice()
    }

    override fun onDestroy() {
        handler.removeCallbacks(autoDetectRunnable)

        try {
            gatt?.disconnect()
            gatt?.close()
        } catch (_: Exception) {
        }

        gatt = null
        super.onDestroy()
    }

    private fun findAlreadyConnectedDevice() {

        if (!hasConnectPermission()) {
            status.text = "اسمح للتطبيق بالوصول إلى Bluetooth"
            return
        }

        if (connecting || gatt != null) return

        val devices = try {
            bluetoothManager.getConnectedDevices(
                BluetoothProfile.GATT
            )
        } catch (_: SecurityException) {
            emptyList()
        } catch (_: Exception) {
            emptyList()
        }

        /*
         * Only devices that Android itself currently reports as GATT-connected
         * are considered. We do NOT show nearby devices and do NOT scan.
         */
        if (devices.isEmpty()) {
            runOnUiThread {
                status.text = "في انتظار الجهاز المتصل…"
                deviceName.text = "لا يوجد جهاز GATT متصل حالياً"
            }
            return
        }

        /*
         * Prefer a bonded connected device because the user said the target
         * device is already paired/connected in Android Bluetooth settings.
         */
        val selected =
            devices.firstOrNull { device ->
                try {
                    device.bondState == BluetoothDevice.BOND_BONDED
                } catch (_: SecurityException) {
                    false
                }
            } ?: devices.first()

        connectToAlreadyConnectedDevice(selected)
    }

    private fun connectToAlreadyConnectedDevice(
        device: BluetoothDevice
    ) {

        if (!hasConnectPermission()) return
        if (connecting || gatt != null) return

        connecting = true

        setDeviceName(device)

        runOnUiThread {
            status.text = "الجهاز متصل — جارٍ فتح قناة الأزرار…"
            commandLog.text =
                "تم اكتشاف الجهاز المتصل تلقائياً."
        }

        try {
            /*
             * We connect as a GATT client only to read the already-connected
             * peripheral's services and subscribe to its command notifications.
             */
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
            connecting = false
            gatt = null

            runOnUiThread {
                status.text = "تعذر فتح اتصال الجهاز"
                commandLog.text =
                    "الجهاز ظاهر كمتصل في Android، لكن لم يسمح النظام " +
                    "بفتح جلسة GATT إضافية."
            }
        }
    }

    private fun findBestCommandCharacteristic(
        g: BluetoothGatt
    ): BluetoothGattCharacteristic? {

        /*
         * Priority 1:
         * The exact custom command channel observed on this device.
         */
        val customService =
            g.getService(commandServiceUuid)

        val exact =
            customService?.getCharacteristic(
                commandCharacteristicUuid
            )

        if (exact != null &&
            isNotificationCharacteristic(exact)
        ) {
            return exact
        }

        /*
         * Priority 2:
         * Standard HID Input Report.
         */
        val hidService =
            g.getService(hidServiceUuid)

        val hidInput =
            hidService?.getCharacteristic(
                hidInputReportUuid
            )

        if (hidInput != null &&
            isNotificationCharacteristic(hidInput)
        ) {
            return hidInput
        }

        /*
         * Priority 3:
         * If the manufacturer changed the custom UUID, look for a
         * notification characteristic. This is internal only; nothing is
         * displayed to the user.
         */
        val all =
            g.services.flatMap {
                it.characteristics
            }

        return all.firstOrNull {
            isNotificationCharacteristic(it)
        }
    }

    private fun isNotificationCharacteristic(
        characteristic: BluetoothGattCharacteristic
    ): Boolean {

        val properties = characteristic.properties

        return (
            properties and
                BluetoothGattCharacteristic.PROPERTY_NOTIFY
        ) != 0 ||
            (
                properties and
                    BluetoothGattCharacteristic.PROPERTY_INDICATE
            ) != 0
    }

    private fun enableNotifications(
        g: BluetoothGatt,
        characteristic: BluetoothGattCharacteristic
    ) {

        if (!hasConnectPermission()) return

        try {

            val localResult =
                g.setCharacteristicNotification(
                    characteristic,
                    true
                )

            if (!localResult) {
                runOnUiThread {
                    status.text = "تعذر تجهيز قناة الأزرار"
                }
                return
            }

            val descriptor =
                characteristic.getDescriptor(cccdUuid)

            if (descriptor == null) {
                runOnUiThread {
                    status.text = "قناة الأزرار بلا إعداد إشعارات"
                }
                return
            }

            val isIndication =
                (
                    characteristic.properties and
                        BluetoothGattCharacteristic
                            .PROPERTY_INDICATE
                ) != 0 &&
                    (
                        characteristic.properties and
                            BluetoothGattCharacteristic
                                .PROPERTY_NOTIFY
                    ) == 0

            descriptor.value =
                if (isIndication) {
                    BluetoothGattDescriptor
                        .ENABLE_INDICATION_VALUE
                } else {
                    BluetoothGattDescriptor
                        .ENABLE_NOTIFICATION_VALUE
                }

            val started =
                g.writeDescriptor(descriptor)

            if (!started) {
                runOnUiThread {
                    status.text = "تعذر بدء استقبال الأوامر"
                }
            }

        } catch (_: Exception) {
            runOnUiThread {
                status.text = "تعذر تفعيل قناة الأزرار"
            }
        }
    }

    private fun handleCommandPacket(
        characteristic: BluetoothGattCharacteristic,
        value: ByteArray
    ) {

        if (!receiving) return
        if (value.isEmpty()) return

        val expected =
            commandCharacteristic

        if (expected != null &&
            characteristic.uuid != expected.uuid
        ) {
            return
        }

        /*
         * Ignore all-zero HID release/idle packets.
         * For the custom 1002 command channel, a packet is captured whenever
         * its bytes actually change.
         */
        val previous = lastPacket

        if (previous != null &&
            previous.contentEquals(value)
        ) {
            return
        }

        val isAllZero =
            value.all { byte ->
                (byte.toInt() and 0xFF) == 0
            }

        if (isAllZero) {
            lastPacket = value.clone()
            return
        }

        eventNumber++

        val hex =
            value.joinToString(" ") { byte ->
                "%02X".format(
                    byte.toInt() and 0xFF
                )
            }

        val changed =
            if (previous == null) {
                "أول أمر"
            } else {
                value.indices
                    .filter { index ->
                        index >= previous.size ||
                            value[index] != previous[index]
                    }
                    .joinToString(", ") { index ->
                        (index + 1).toString()
                    }
                    .ifEmpty {
                        "لا يوجد"
                    }
            }

        lastPacket = value.clone()

        runOnUiThread {
            status.text =
                "تم التقاط الأمر #$eventNumber"

            commandLog.text =
                """
                الأمر #$eventNumber

                HEX الأصلي:
                $hex

                البايتات المتغيرة:
                $changed

                اضغط الزر التالي في الجهاز الأصلي.
                """.trimIndent()
        }
    }

    private fun setDeviceName(
        device: BluetoothDevice
    ) {

        val name =
            try {
                device.name
                    ?.takeIf { it.isNotBlank() }
                    ?: "Bluetooth Device"
            } catch (_: SecurityException) {
                "Bluetooth Device"
            }

        runOnUiThread {
            deviceName.text = name
        }
    }

    private fun buildCleanUi() {

        val root =
            LinearLayout(this).apply {
                orientation =
                    LinearLayout.VERTICAL

                setBackgroundColor(
                    0xFF090D12.toInt()
                )

                setPadding(
                    dp(18),
                    dp(18),
                    dp(18),
                    dp(18)
                )
            }

        val title =
            makeText(
                "Bluetooth Command Capture",
                25f,
                true
            )

        root.addView(
            title,
            params(-1, -2)
        )

        status =
            makeText(
                "في انتظار الجهاز المتصل…",
                16f,
                true
            ).apply {
                setTextColor(
                    0xFF6EE78A.toInt()
                )

                gravity = Gravity.CENTER_VERTICAL

                setPadding(
                    dp(14),
                    dp(16),
                    dp(14),
                    dp(16)
                )

                setBackgroundColor(
                    0xFF121A24.toInt()
                )
            }

        root.addView(
            status,
            marginParams(
                -1,
                -2,
                0,
                14,
                0,
                12
            )
        )

        deviceName =
            makeText(
                "لا يوجد جهاز متصل",
                23f,
                true
            ).apply {
                gravity = Gravity.CENTER
            }

        root.addView(
            deviceName,
            marginParams(
                -1,
                -2,
                0,
                0,
                0,
                16
            )
        )

        val readyTitle =
            makeText(
                "التقاط أوامر الأزرار",
                18f,
                true
            )

        root.addView(
            readyTitle,
            marginParams(
                -1,
                -2,
                0,
                4,
                0,
                8
            )
        )

        commandLog =
            makeText(
                "اتصل بالجهاز من إعدادات Bluetooth.\n" +
                    "سيتم اكتشافه تلقائياً.",
                17f,
                false
            ).apply {

                typeface =
                    Typeface.MONOSPACE

                setTextColor(
                    0xFFE8EDF3.toInt()
                )

                setPadding(
                    dp(16),
                    dp(18),
                    dp(16),
                    dp(18)
                )

                setBackgroundColor(
                    0xFF111820.toInt()
                )
            }

        val scroll =
            ScrollView(this).apply {
                addView(commandLog)
            }

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

    private fun requestConnectPermission() {

        if (Build.VERSION.SDK_INT <
            Build.VERSION_CODES.S
        ) {
            return
        }

        if (
            checkSelfPermission(
                Manifest.permission.BLUETOOTH_CONNECT
            ) !=
            PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(
                arrayOf(
                    Manifest.permission.BLUETOOTH_CONNECT
                ),
                2001
            )
        }
    }

    private fun hasConnectPermission(): Boolean {

        return Build.VERSION.SDK_INT <
            Build.VERSION_CODES.S ||
            checkSelfPermission(
                Manifest.permission.BLUETOOTH_CONNECT
            ) ==
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

            setTextColor(
                0xFFE8EDF3.toInt()
            )

            if (bold) {
                typeface =
                    Typeface.DEFAULT_BOLD
            }
        }
    }

    private fun params(
        width: Int,
        height: Int
    ): LinearLayout.LayoutParams {

        return LinearLayout.LayoutParams(
            width,
            height
        )
    }

    private fun marginParams(
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
