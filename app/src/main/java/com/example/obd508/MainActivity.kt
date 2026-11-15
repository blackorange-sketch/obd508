package com.example.obd508

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView

class MainActivity : Activity() {
    private val elm = Elm327()
    private lateinit var status: TextView
    private lateinit var rpmG: GaugeView
    private lateinit var speedG: GaugeView
    private lateinit var boostG: GaugeView
    private lateinit var coolantG: GaugeView
    private val tiles = mutableMapOf<String, TextView>()
    @Volatile private var running = false
    @Volatile private var baro = 101.0

    private val defs = STANDARD_PIDS.associateBy { it.pid }
    private val fast = listOf("0C", "0D", "0B", "11", "04")
    private val slow = listOf("05", "0F", "0E", "06", "07", "33")
    private val tilePids = listOf("0F", "04", "11", "0E", "06", "07")

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101418.toInt())
            setPadding(dp(12), dp(32), dp(12), dp(12))
        }

        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val btn = Button(this).apply { text = "Підключити" }
        status = TextView(this).apply {
            text = "Не підключено"; setTextColor(0xFF8A97A6.toInt()); setPadding(dp(12), 0, 0, 0)
        }
        bar.addView(btn)
        bar.addView(status)
        root.addView(bar)

        rpmG = GaugeView(this, "ОБЕРТИ", "об/хв", 0f, 7000f, 6300f, 0)
        speedG = GaugeView(this, "ШВИДКІСТЬ", "км/год", 0f, 240f, 9999f, 0)
        boostG = GaugeView(this, "БУСТ", "бар", -1f, 2f, 1.6f, 2)
        coolantG = GaugeView(this, "ОХОЛОДЖЕННЯ", "°C", 40f, 130f, 110f, 0)

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(row(rpmG, speedG))
        content.addView(row(boostG, coolantG))

        val extra = tilePids.chunked(2)
        for (pair in extra) content.addView(row(*pair.map { tile(it) }.toTypedArray()))

        root.addView(ScrollView(this).apply { addView(content) })
        setContentView(root)

        btn.setOnClickListener { pickDevice() }
    }

    private fun row(vararg views: View): LinearLayout {
        val r = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        for (v in views) {
            val lp = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            lp.setMargins(dp(4), dp(4), dp(4), dp(4))
            r.addView(v, lp)
        }
        return r
    }

    private fun tile(pid: String): View {
        val def = defs.getValue(pid)
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(14), dp(10), dp(14), dp(10))
            background = GradientDrawable().apply {
                setColor(0xFF1C232B.toInt()); cornerRadius = dp(14).toFloat()
            }
        }
        box.addView(TextView(this).apply {
            text = def.name; textSize = 12f; setTextColor(0xFF8A97A6.toInt())
        })
        val value = TextView(this).apply {
            text = "—"; textSize = 24f; setTextColor(Color.WHITE)
            typeface = android.graphics.Typeface.DEFAULT_BOLD
        }
        box.addView(value)
        tiles[pid] = value
        return box
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
            status.text = "Спаруйте ELM327 у налаштуваннях Bluetooth (PIN 1234 або 0000)"
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
                runOnUiThread { status.text = "Підключено" }
                running = true
                loop()
            } catch (e: Exception) {
                runOnUiThread { status.text = "Помилка: ${e.message}" }
            }
        }.start()
    }

    private fun loop() {
        var n = 0
        while (running) {
            val list = if (n % 8 == 0) fast + slow else fast
            for (pid in list) {
                if (!running) break
                val data = try {
                    elm.pid(pid)
                } catch (e: Exception) {
                    running = false
                    runOnUiThread { status.text = "З'єднання втрачено" }
                    break
                }
                val v = data?.let { d -> runCatching { defs.getValue(pid).calc(d) }.getOrNull() }
                if (pid == "33" && v != null) baro = v
                runOnUiThread { show(pid, v) }
            }
            n++
        }
    }

    private fun show(pid: String, v: Double?) {
        when (pid) {
            "0C" -> rpmG.setValue(v?.toFloat())
            "0D" -> speedG.setValue(v?.toFloat())
            "0B" -> boostG.setValue(v?.let { ((it - baro) / 100.0).toFloat() })
            "05" -> coolantG.setValue(v?.toFloat())
            else -> tiles[pid]?.text = v?.let { "%.1f %s".format(it, defs.getValue(pid).unit) } ?: "—"
        }
    }

    override fun onDestroy() {
        running = false
        elm.close()
        super.onDestroy()
    }
}
