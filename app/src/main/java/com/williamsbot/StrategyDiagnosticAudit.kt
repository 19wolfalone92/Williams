package com.williamsbot

import org.json.JSONObject
import kotlin.math.abs
import kotlin.math.max

/** Independent numerical checks of the values produced by the actual closed-bar engine. */
internal fun auditWilliamsIndicatorObservation(medians: List<Double>, observed: JSONObject): JSONObject {
    val result = JSONObject()
    fun record(id: String, severity: String, detail: String) {
        result.put(id, JSONObject().put("severity", severity).put("detail", detail))
    }
    if (medians.size < 40 || medians.any { !it.isFinite() }) {
        for (id in listOf("STR-01", "STR-02", "STR-03", "STR-04"))
            record(id, "NOT_VERIFIED", "Нет достаточного закрытого H1-набора")
        return result
    }
    fun equal(a: Double, b: Double) = a.isFinite() && b.isFinite() && abs(a - b) <= max(1.0, abs(b)) * 1e-9
    // Deliberately do not call the production SMMA/AO/AC helpers here.
    fun expectedLine(period: Int, shift: Int): Double {
        var value = medians.take(period).sum() / period
        for (index in period..(medians.lastIndex - shift)) value += (medians[index] - value) / period
        return value
    }
    val correctAlligator = listOf(Triple("jaw", 13, 8), Triple("teeth", 8, 5), Triple("lips", 5, 3)).all { (key, period, shift) ->
        equal(observed.optDouble(key), expectedLine(period, shift))
    }
    record("STR-01", if (correctAlligator) "PASS" else "FAIL",
        "Сравнение фактических Jaw 13/8, Teeth 8/5, Lips 5/3 с независимым SMMA; свечей=${medians.size}")
    val fractals = listOf("fractal_up_index", "fractal_down_index").filter { observed.has(it) && !observed.isNull(it) }
    record("STR-02", when {
        fractals.isEmpty() -> "NOT_VERIFIED"
        fractals.any { observed.optInt(it, -1) !in 2..(medians.size - 3) } -> "FAIL"
        else -> "PASS"
    }, "Проверяются границы фактически найденных фракталов: две закрытые свечи справа; полный каталог паттернов этим не доказывается")
    fun expectedAo(index: Int): Double = medians.subList(index - 4, index + 1).sum() / 5 -
        medians.subList(index - 33, index + 1).sum() / 34
    val expectedAoValue = expectedAo(medians.lastIndex)
    record("STR-03", if (equal(observed.optDouble("ao"), expectedAoValue)) "PASS" else "FAIL",
        "AO двигателя=${observed.opt("ao")}; независимый SMA5(median)-SMA34(median)= $expectedAoValue; проверка численного значения")
    val expectedAc = expectedAoValue - (0..4).map { expectedAo(medians.lastIndex - it) }.average()
    record("STR-04", if (equal(observed.optDouble("ac"), expectedAc)) "PASS" else "FAIL",
        "AC двигателя=${observed.opt("ac")}; независимый AO-SMA5(AO)=$expectedAc; подтверждения входа проверяются отдельно")
    return result
}

