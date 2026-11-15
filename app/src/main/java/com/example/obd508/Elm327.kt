package com.example.obd508

import android.annotation.SuppressLint
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

/** Мінімальний клієнт ELM327 по Classic Bluetooth (SPP). */
class Elm327 {
    private var socket: BluetoothSocket? = null
    private var input: InputStream? = null
    private var output: OutputStream? = null

    @SuppressLint("MissingPermission")
    fun connect(device: BluetoothDevice) {
        val s = device.createRfcommSocketToServiceRecord(SPP_UUID)
        s.connect()
        socket = s
        input = s.inputStream
        output = s.outputStream
        // Ініціалізація: скидання, без ехо, без пробілів/заголовків, авто-протокол
        for (cmd in listOf("ATZ", "ATE0", "ATL0", "ATS0", "ATH0", "ATSP0")) {
            send(cmd)
            if (cmd == "ATZ") Thread.sleep(800)
        }
    }

    fun close() {
        try { socket?.close() } catch (_: Exception) {}
        socket = null
    }

    /** Надсилає команду й читає відповідь до символу '>'. Повертає рядки відповіді. */
    @Synchronized
    fun sendLines(cmd: String, timeoutMs: Long = 3000): List<String> {
        val out = output ?: error("Не підключено")
        val inp = input ?: error("Не підключено")
        while (inp.available() > 0) inp.read() // очистити буфер
        out.write((cmd + "\r").toByteArray())
        out.flush()
        val sb = StringBuilder()
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            if (inp.available() > 0) {
                val c = inp.read().toChar()
                if (c == '>') break
                sb.append(c)
            } else Thread.sleep(10)
        }
        return sb.toString().split('\r', '\n').map { it.trim() }.filter { it.isNotEmpty() }
    }

    fun send(cmd: String, timeoutMs: Long = 3000): String =
        sendLines(cmd, timeoutMs).joinToString(" ")

    /** Запит PID режиму 01. Повертає байти даних або null. */
    fun pid(pid: String): IntArray? {
        val resp = send("01$pid").replace(" ", "").uppercase()
        val marker = "41$pid"
        val i = resp.indexOf(marker)
        if (i < 0) return null
        return hexBytes(resp.substring(i + marker.length))
    }

    /** Читання кодів помилок: mode = "03" (збережені) або "07" (очікувані). */
    @Synchronized
    fun readDtcs(mode: String): List<String> {
        val prefix = "4" + mode.substring(1)
        val proto = send("ATDPN").replace(" ", "").uppercase().removePrefix("A")
        val isCan = proto.firstOrNull() in '6'..'9'
        val lines = sendLines(mode, 5000)

        // Склеюємо багатокадрові відповіді ("0:", "1:" ...)
        val msgs = mutableListOf<StringBuilder>()
        var cur: StringBuilder? = null
        for (l in lines) {
            val c = l.replace(" ", "").uppercase()
            when {
                Regex("^[0-9A-F]:.*").matches(c) -> {
                    if (cur == null) cur = StringBuilder().also { msgs.add(it) }
                    cur.append(c.substring(2))
                }
                c.length == 3 -> cur = StringBuilder().also { msgs.add(it) } // лічильник байтів
                else -> { cur = null; msgs.add(StringBuilder(c)) }
            }
        }

        val result = linkedSetOf<String>()
        for (m in msgs) {
            val h = m.toString()
            if (!h.startsWith(prefix)) continue
            var data = hexBytes(h.substring(prefix.length)).toList()
            if (isCan && data.isNotEmpty()) data = data.drop(1) // байт кількості помилок
            var k = 0
            while (k + 1 < data.size) {
                val a = data[k]; val b = data[k + 1]
                if (a != 0 || b != 0) result.add(dtcName(a, b))
                k += 2
            }
        }
        return result.toList()
    }

    /** Скидання помилок (режим 04). */
    fun clearDtcs(): Boolean = send("04", 5000).replace(" ", "").contains("44")

    /**
     * Власний запит до конкретного блока: header (напр. 7E0), request (напр. 22F190).
     * Повертає байти даних після підтвердження відповіді або null.
     */
    @Synchronized
    fun custom(header: String, request: String): IntArray? {
        val req = request.replace(" ", "").uppercase()
        if (req.length < 2) return null
        send("ATSH$header")
        val lines = sendLines(req, 3000)
        send("ATSH7DF")
        val resp = lines.joinToString("") {
            it.replace(" ", "").uppercase().replace(Regex("^[0-9A-F]:"), "")
        }
        val svc = req.substring(0, 2).toInt(16) + 0x40
        val marker = "%02X".format(svc) + req.substring(2)
        val i = resp.indexOf(marker)
        if (i < 0) return null
        return hexBytes(resp.substring(i + marker.length))
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

        fun hexBytes(hex: String): IntArray =
            IntArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toIntOrNull(16) ?: 0 }

        fun dtcName(a: Int, b: Int): String =
            "%s%d%X%02X".format("PCBU"[a shr 6], (a shr 4) and 3, a and 0xF, b)
    }
}

/** Опис стандартних PID: назва, одиниця, формула. */
data class PidDef(val pid: String, val name: String, val unit: String, val calc: (IntArray) -> Double)

val STANDARD_PIDS = listOf(
    PidDef("0C", "Оберти", "об/хв") { ((it[0] * 256) + it[1]) / 4.0 },
    PidDef("0D", "Швидкість", "км/год") { it[0].toDouble() },
    PidDef("05", "Температура ОЖ", "°C") { it[0] - 40.0 },
    PidDef("0F", "Температура впуску", "°C") { it[0] - 40.0 },
    PidDef("0B", "Тиск впуску (MAP)", "кПа") { it[0].toDouble() },
    PidDef("33", "Атмосферний тиск", "кПа") { it[0].toDouble() },
    PidDef("04", "Навантаження", "%") { it[0] * 100.0 / 255 },
    PidDef("11", "Дросель", "%") { it[0] * 100.0 / 255 },
    PidDef("0E", "Кут запалювання", "°") { it[0] / 2.0 - 64 },
    PidDef("06", "STFT банк 1", "%") { (it[0] - 128) * 100.0 / 128 },
    PidDef("07", "LTFT банк 1", "%") { (it[0] - 128) * 100.0 / 128 },
)
