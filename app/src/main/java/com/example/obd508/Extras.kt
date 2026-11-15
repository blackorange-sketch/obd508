package com.example.obd508

import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Власний PID: Назва;Одиниця;Заголовок;Запит;Формула (змінні A,B,C,D — байти відповіді). */
data class CustomPid(
    val name: String,
    val unit: String,
    val header: String,
    val request: String,
    val formula: String,
) {
    fun eval(data: IntArray): Double {
        val vars = mapOf(
            'A' to data.getOrElse(0) { 0 }.toDouble(),
            'B' to data.getOrElse(1) { 0 }.toDouble(),
            'C' to data.getOrElse(2) { 0 }.toDouble(),
            'D' to data.getOrElse(3) { 0 }.toDouble(),
        )
        return Formula(formula.replace(" ", ""), vars).parse()
    }

    companion object {
        fun parseAll(text: String): List<CustomPid> =
            text.lines().map { it.trim() }
                .filter { it.isNotEmpty() && !it.startsWith("#") }
                .mapNotNull { line ->
                    val p = line.split(";").map { it.trim() }
                    if (p.size < 5 || p[0].isEmpty()) null
                    else CustomPid(p[0], p[1], p[2], p[3], p[4])
                }
    }
}

/** Крихітний обчислювач формул: + - * / дужки, числа та змінні A-D. */
class Formula(private val s: String, private val vars: Map<Char, Double>) {
    private var i = 0

    fun parse(): Double {
        val v = expr()
        if (i != s.length) error("Помилка у формулі")
        return v
    }

    private fun expr(): Double {
        var v = term()
        while (i < s.length && (s[i] == '+' || s[i] == '-')) {
            val op = s[i++]
            val t = term()
            v = if (op == '+') v + t else v - t
        }
        return v
    }

    private fun term(): Double {
        var v = factor()
        while (i < s.length && (s[i] == '*' || s[i] == '/')) {
            val op = s[i++]
            val f = factor()
            v = if (op == '*') v * f else v / f
        }
        return v
    }

    private fun factor(): Double {
        if (i >= s.length) error("Помилка у формулі")
        val c = s[i]
        if (c == '-') { i++; return -factor() }
        if (c == '(') {
            i++
            val v = expr()
            if (i >= s.length || s[i] != ')') error("Немає закриваючої дужки")
            i++
            return v
        }
        if (c.isLetter()) {
            i++
            return vars[c.uppercaseChar()] ?: error("Невідома змінна $c")
        }
        val st = i
        while (i < s.length && (s[i].isDigit() || s[i] == '.')) i++
        return s.substring(st, i).toDoubleOrNull() ?: error("Помилка у формулі")
    }
}

/** Запис показників у CSV. */
class CsvLogger(dir: File, private val columns: List<String>) {
    val file = File(
        dir,
        "obd_" + SimpleDateFormat("yyyyMMdd_HHmmss", Locale.US).format(Date()) + ".csv"
    )
    private val w = file.bufferedWriter()
    private var closed = false

    init {
        w.write("time_ms," + columns.joinToString(",") { "\"$it\"" })
        w.newLine()
    }

    @Synchronized
    fun row(values: Map<String, Double>) {
        if (closed) return
        w.write(System.currentTimeMillis().toString() + "," +
            columns.joinToString(",") { c -> values[c]?.let { "%.2f".format(Locale.US, it) } ?: "" })
        w.newLine()
        w.flush()
    }

    @Synchronized
    fun close() {
        if (closed) return
        closed = true
        w.flush()
        w.close()
    }
}

/** Загальні (SAE) описи частих кодів, корисних для 1.6 THP. */
val DTC_INFO = mapOf(
    "P0011" to "Фаза розподільного валу (впуск) — випередження завелике",
    "P0012" to "Фаза розподільного валу (впуск) — запізнення завелике",
    "P0014" to "Фаза розподільного валу (випуск) — випередження завелике",
    "P0016" to "Невідповідність колінвал/розподільний вал",
    "P0087" to "Тиск у паливній рампі занизький",
    "P0171" to "Бідна суміш (банк 1)",
    "P0172" to "Багата суміш (банк 1)",
    "P0234" to "Завищений тиск наддуву",
    "P0299" to "Недостатній тиск наддуву",
    "P0300" to "Випадкові пропуски запалювання",
    "P0301" to "Пропуски запалювання, циліндр 1",
    "P0302" to "Пропуски запалювання, циліндр 2",
    "P0303" to "Пропуски запалювання, циліндр 3",
    "P0304" to "Пропуски запалювання, циліндр 4",
    "P0325" to "Датчик детонації — несправність кола",
    "P0335" to "Датчик положення колінвала — несправність кола",
    "P0420" to "Ефективність каталізатора нижче норми",
)
