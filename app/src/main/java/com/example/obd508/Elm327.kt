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

    /** Надсилає команду й читає відповідь до символу '>'. */
    @Synchronized
    fun send(cmd: String, timeoutMs: Long = 3000): String {
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
        return sb.toString().replace("\r", " ").replace("\n", " ").trim()
    }

    /** Запит PID режиму 01. Повертає байти даних або null. */
    fun pid(pid: String): IntArray? {
        val resp = send("01$pid").replace(" ", "").uppercase()
        val marker = "41$pid"
        val i = resp.indexOf(marker)
        if (i < 0) return null
        val hex = resp.substring(i + marker.length)
        if (hex.length < 2) return null
        return IntArray(hex.length / 2) { hex.substring(it * 2, it * 2 + 2).toInt(16) }
    }

    companion object {
        val SPP_UUID: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
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
