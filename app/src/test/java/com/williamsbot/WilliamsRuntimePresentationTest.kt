package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsRuntimePresentationTest {
    @Test fun workingHistoryDoesNotMasqueradeAsTenCompletedWaveArchives() {
        val summary=russianWaveArchiveProgress(JSONObject()
            .put("worker_state","WAITING_FULL_ARCHIVE")
            .put("items",JSONArray().put(JSONObject().put("processed_h1_closes",123)
                .put("status","CAUGHT_UP_AVAILABLE_H1"))
                .put(JSONObject().put("processed_h1_closes",7).put("status","REPLAYING"))))
        assertTrue(summary.contains("130 часовых срезов"))
        assertTrue(summary.contains("1 из 10 пар"))
        assertTrue(summary.contains("ожидание полной загрузки"))
        assertTrue(summary.contains("гипотезой"))
        assertEquals("",russianWaveArchiveProgress(null))
    }

    @Test fun dayResetIsDisplayedInUtcRatherThanPhoneTimezone() {
        val previous=java.util.TimeZone.getDefault()
        try {
            java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone("Europe/Minsk"))
            val text=russianDailyEntryHaltText(JSONObject().put("available",true)
                .put("allow_new_entries",false).put("until_ms",java.time.Instant.parse("2026-10-21T00:00:00Z").toEpochMilli()))
            assertTrue(text.contains("21.10 00:00 UTC"))
            assertTrue(text.contains("Сопровождение позиций продолжается"))
            assertFalse(russianDailyEntryHaltText(JSONObject().put("available",false)).contains("Дневного запрета нет"))
        } finally { java.util.TimeZone.setDefault(previous) }
    }
}
