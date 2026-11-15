package com.example.obd508

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private val elm = Elm327()
    private lateinit var status: TextView
    private lateinit var values: TextView
    @Volatile private var running = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(32, 48, 32, 32)
        }
        val btn = Button(this).apply { text = "Підключитись до ELM327" }
        status = TextView(this).apply { text = "Не підключено"; textSize = 16f }
        values = TextView(this).apply { textSize = 20f; typeface = android.graphics.Typeface.MONOSPACE }
        root.addView(btn)
        root.addView(status)
        root.addView(ScrollView(this).apply { addView(values) })
        setContentView(root)

        btn.setOnClickListener { pickDevice() }
    }

    @SuppressLint("MissingPermission")
    private fun pickDevice() {
        if (Build.VERSION.SDK_INT >= 31 &&
            checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED
        ) {
            requestPermissions(arrayOf(Manifest.permission.BLUETOOTH_CONNECT), 1)
            return
        }
        val adapter: BluetoothAdapter? = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        val devices = adapter?.bondedDevices?.toList().orEmpty()
        if (devices.isEmpty()) {
            status.text = "Немає спарених пристроїв. Спаруйте ELM327 у налаштуваннях Bluetooth (PIN 1234 або 0000)."
            return
        }
        AlertDialog.Builder(this)
            .setTitle("Оберіть адаптер")
            .setItems(devices.map { "${it.name}\n${it.address}" }.toTypedArray()) { _, i -> connect(devices[i]) }
            .show()
    }

    override fun onRequestPermissionsResult(code: Int, perms: Array<out String>, res: IntArray) {
        super.onRequestPermissionsResult(code, perms, res)
        if (res.firstOrNull() == PackageManager.PERMISSION_GRANTED) pickDevice()
    }

    private fun connect(device: BluetoothDevice) {
        running = false
        status.text = "Підключення…"
        Thread {
            try {
                elm.connect(device)
                runOnUiThread { status.text = "Підключено. Запалювання має бути ввімкнене." }
                running = true
                loop()
            } catch (e: Exception) {
                runOnUiThread { status.text = "Помилка: ${e.message}" }
            }
        }.start()
    }

    private fun loop() {
        while (running) {
            val sb = StringBuilder()
            for (p in STANDARD_PIDS) {
                val data = try { elm.pid(p.pid) } catch (e: Exception) { running = false; null }
                val txt = if (data != null) "%.1f %s".format(p.calc(data), p.unit) else "—"
                sb.append("${p.name}: $txt\n")
            }
            runOnUiThread { values.text = sb.toString() }
        }
    }

    override fun onDestroy() {
        running = false
        elm.close()
        super.onDestroy()
    }
}
