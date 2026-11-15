package com.example.obd508

import android.Manifest
import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.widget.Button
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.FileProvider
import java.util.concurrent.ConcurrentHashMap

class MainActivity : Activity() {
    private val elm = Elm327()
    private lateinit var status: TextView
    private lateinit var rpmG: GaugeView
    private lateinit var speedG: GaugeView
    private lateinit var boostG: GaugeView
    private lateinit var coolantG: GaugeView
    private lateinit var customView: TextView
    private lateinit var dtcView: TextView
    private lateinit var termOut: TextView
    private lateinit var recBtn: Button
    private lateinit var screens: List<View>
    private val tiles = mutableMapOf<String, TextView>()

    private val latest = ConcurrentHashMap<String, Double>()
    @Volatile private var running = false
    @Volatile private var baro = 101.0
    @Volatile private var logger: CsvLogger? = null
    @Volatile private var customPids: List<CustomPid> = emptyList()

    private val defs = STANDARD_PIDS.associateBy { it.pid }
    private val fast = listOf("0C", "0D", "0B", "11", "04")
    private val slow = listOf("05", "0F", "0E", "06", "07", "33")
    private val tilePids = listOf("0F", "04", "11", "0E", "06", "07")

    private val gray = 0xFF8A97A6.toInt()
    private val card = 0xFF1C232B.toInt()

    private fun dp(v: Int) = (v * resources.displayMetrics.density).toInt()
    private val prefs by lazy { getSharedPreferences("obd", MODE_PRIVATE) }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF101418.toInt())
            setPadding(dp(8), dp(28), dp(8), dp(8))
        }

        // Рядок підключення
        val bar = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL }
        val connectBtn = smallButton("Підключити") { pickDevice() }
        status = TextView(this).apply {
            text = "Не підключено"; setTextColor(gray); setPadding(dp(12), 0, 0, 0)
        }
        bar.addView(connectBtn)
        bar.addView(status)
        root.addView(bar)

        // Екрани
        screens = listOf(buildDashboard(), buildErrors(), buildAdvanced())
        val content = FrameLayout(this)
        screens.forEach { content.addView(it, ViewGroup.LayoutParams(-1, -1)) }

        // Навігація
        val nav = LinearLayout(this)
        val titles = listOf("Дашборд", "Помилки", "Розширені")
        titles.forEachIndexed { i, t -> nav.addView(smallButton(t) { showScreen(i) }, weight()) }
        recBtn = smallButton("● REC") { toggleRec() }
        nav.addView(recBtn, weight())

        root.addView(nav)
        root.addView(content, LinearLayout.LayoutParams(-1, 0, 1f))
        setContentView(root)
        showScreen(0)

        customPids = CustomPid.parseAll(prefs.getString("defs", "") ?: "")
        refreshCustom()
    }

    private fun weight() = LinearLayout.LayoutParams(0, -2, 1f)

    private fun smallButton(text: String, onClick: () -> Unit) = Button(this).apply {
        this.text = text
        textSize = 12f
        isAllCaps = false
        minWidth = 0
        minimumWidth = 0
        setOnClickListener { onClick() }
    }

    private fun showScreen(i: Int) {
        screens.forEachIndexed { j, v -> v.visibility = if (i == j) View.VISIBLE else View.GONE }
    }

    // ---------- Дашборд ----------

    private fun buildDashboard(): View {
        rpmG = GaugeView(this, "ОБЕРТИ", "об/хв", 0f, 7000f, 6300f, 0)
        speedG = GaugeView(this, "ШВИДКІСТЬ", "км/год", 0f, 240f, 9999f, 0)
        boostG = GaugeView(this, "БУСТ", "бар", -1f, 2f, 1.6f, 2)
        coolantG = GaugeView(this, "ОХОЛОДЖЕННЯ", "°C", 40f, 130f, 110f, 0)

        val content = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        content.addView(row(rpmG, speedG))
        content.addView(row(boostG, coolantG))
        for (pair in tilePids.chunked(2)) content.addView(row(*pair.map { tile(it) }.toTypedArray()))
        return ScrollView(this).apply { addView(content) }
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
            background = GradientDrawable().apply { setColor(card); cornerRadius = dp(14).toFloat() }
        }
        box.addView(TextView(this).apply { text = def.name; textSize = 12f; setTextColor(gray) })
        val value = TextView(this).apply {
            text = "—"; textSize = 24f; setTextColor(Color.WHITE); typeface = Typeface.DEFAULT_BOLD
        }
        box.addView(value)
        tiles[pid] = value
        return box
    }

    // ---------- Помилки ----------

    private fun buildErrors(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(4), dp(8), dp(4), dp(4))
        }
        val btns = LinearLayout(this)
        btns.addView(smallButton("Прочитати помилки") { readDtcs() }, weight())
        btns.addView(smallButton("Скинути помилки") { confirmClear() }, weight())
        col.addView(btns)
        dtcView = TextView(this).apply {
            text = "Натисніть «Прочитати помилки»"
            textSize = 15f; setTextColor(Color.WHITE); typeface = Typeface.MONOSPACE
            setPadding(dp(8), dp(12), dp(8), dp(12))
        }
        col.addView(ScrollView(this).apply { addView(dtcView) })
        return col
    }

    private fun readDtcs() {
        dtcView.text = "Читання…"
        Thread {
            val text = try {
                val stored = elm.readDtcs("03")
                val pending = elm.readDtcs("07")
                fun fmt(l: List<String>) =
                    if (l.isEmpty()) "  немає" else l.joinToString("\n") { c ->
                        "  " + (DTC_INFO[c]?.let { "$c — $it" } ?: c)
                    }
                "Збережені:\n${fmt(stored)}\n\nОчікувані:\n${fmt(pending)}"
            } catch (e: Exception) {
                "Помилка: ${e.message}"
            }
            runOnUiThread { dtcView.text = text }
        }.start()
    }

    private fun confirmClear() {
        AlertDialog.Builder(this)
            .setTitle("Скинути помилки?")
            .setMessage("Буде стерто коди помилок і freeze frame, також скинуться монітори готовності.")
            .setNegativeButton("Скасувати", null)
            .setPositiveButton("Скинути") { _, _ ->
                Thread {
                    val msg = try {
                        if (elm.clearDtcs()) "Помилки скинуто" else "Блок не підтвердив скидання"
                    } catch (e: Exception) {
                        "Помилка: ${e.message}"
                    }
                    runOnUiThread { dtcView.text = msg }
                }.start()
            }
            .show()
    }

    // ---------- Розширені ----------

    private fun buildAdvanced(): View {
        val col = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(8), dp(8), dp(8), dp(8))
        }
        col.addView(TextView(this).apply {
            text = "Власні PID. Рядок: Назва;Одиниця;Заголовок;Запит;Формула\n" +
                "Змінні A,B,C,D — байти відповіді. Приклад: Назва;°C;7E0;220000;A-40"
            textSize = 12f; setTextColor(gray)
        })
        val defsEdit = darkEdit().apply {
            setText(prefs.getString("defs", "") ?: "")
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_MULTI_LINE
            minLines = 4
            hint = "# рядки з # ігноруються"
        }
        col.addView(defsEdit)
        col.addView(smallButton("Зберегти власні PID") {
            val text = defsEdit.text.toString()
            prefs.edit().putString("defs", text).apply()
            customPids = CustomPid.parseAll(text)
            refreshCustom()
            Toast.makeText(this, "Збережено: ${customPids.size}", Toast.LENGTH_SHORT).show()
        })
        customView = TextView(this).apply {
            textSize = 15f; setTextColor(Color.WHITE); typeface = Typeface.MONOSPACE
            setPadding(0, dp(8), 0, dp(8))
        }
        col.addView(customView)

        col.addView(TextView(this).apply {
            text = "Термінал ELM327 (після ATSH не забудьте ATSH7DF)"
            textSize = 12f; setTextColor(gray)
        })
        val cmdRow = LinearLayout(this)
        val cmdEdit = darkEdit().apply {
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_FLAG_CAP_CHARACTERS
            hint = "напр. 0100"
            setSingleLine(true)
        }
        cmdRow.addView(cmdEdit, LinearLayout.LayoutParams(0, -2, 1f))
        cmdRow.addView(smallButton("Надіслати") {
            val cmd = cmdEdit.text.toString().trim()
            if (cmd.isNotEmpty()) Thread {
                val out = try { elm.sendLines(cmd, 5000).joinToString("\n") } catch (e: Exception) { "Помилка: ${e.message}" }
                runOnUiThread { termOut.text = "> $cmd\n$out\n\n" + termOut.text }
            }.start()
        })
        col.addView(cmdRow)
        termOut = TextView(this).apply {
            textSize = 13f; setTextColor(Color.WHITE); typeface = Typeface.MONOSPACE
        }
        col.addView(termOut)
        return ScrollView(this).apply { addView(col) }
    }

    private fun darkEdit() = EditText(this).apply {
        setTextColor(Color.WHITE); setHintTextColor(gray)
        typeface = Typeface.MONOSPACE; textSize = 14f
        setBackgroundColor(card)
        setPadding(dp(10), dp(8), dp(10), dp(8))
    }

    private fun refreshCustom() {
        customView.text = if (customPids.isEmpty()) "" else customPids.joinToString("\n") { p ->
            val v = latest[p.name]
            "${p.name}: " + (v?.let { "%.1f %s".format(it, p.unit) } ?: "—")
        }
    }

    // ---------- Запис логу ----------

    private fun toggleRec() {
        val active = logger
        if (active == null) {
            val dir = getExternalFilesDir(null) ?: return
            val cols = STANDARD_PIDS.map { it.name } + "Буст (бар)" + customPids.map { it.name }
            logger = CsvLogger(dir, cols)
            recBtn.text = "■ STOP"
            recBtn.setTextColor(0xFFFF5252.toInt())
        } else {
            logger = null
            active.close()
            recBtn.text = "● REC"
            recBtn.setTextColor(Color.WHITE)
            val uri = FileProvider.getUriForFile(this, "$packageName.files", active.file)
            startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                type = "text/csv"
                putExtra(Intent.EXTRA_STREAM, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }, "Поділитись логом"))
        }
    }

    // ---------- Підключення ----------

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

    // ---------- Опитування ----------

    private fun loop() {
        var n = 0
        try {
            while (running) {
                val slowPass = n % 8 == 0
                val list = if (slowPass) fast + slow else fast
                for (pid in list) {
                    if (!running) break
                    val def = defs.getValue(pid)
                    val data = elm.pid(pid)
                    val v = data?.let { d -> runCatching { def.calc(d) }.getOrNull() }
                    if (v != null) latest[def.name] = v else latest.remove(def.name)
                    if (pid == "33" && v != null) baro = v
                    if (pid == "0B" && v != null) latest["Буст (бар)"] = (v - baro) / 100.0
                    runOnUiThread { show(pid, v) }
                }
                if (slowPass) {
                    for (cp in customPids) {
                        if (!running) break
                        val d = elm.custom(cp.header, cp.request)
                        val v = d?.let { runCatching { cp.eval(it) }.getOrNull() }
                        if (v != null) latest[cp.name] = v else latest.remove(cp.name)
                    }
                    runOnUiThread { refreshCustom() }
                }
                logger?.row(latest)
                n++
            }
        } catch (e: Exception) {
            running = false
            runOnUiThread { status.text = "З'єднання втрачено" }
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
        logger?.close()
        logger = null
        elm.close()
        super.onDestroy()
    }
}
