package com.williamsbot

import org.json.JSONObject
import java.time.Instant
import java.time.ZoneOffset

/** User policy for new exposure. Never gates reconciliation, protection or exits. */
internal data class WilliamsEntryTimePolicy(
    val mode:String="ALWAYS",val startUtc:String="08:00",val endUtc:String="20:00"
) {
    private fun minute(text:String):Int {
        require(Regex("(?:[01]\\d|2[0-3]):[0-5]\\d").matches(text)) { "Время входа: HH:MM UTC" }
        val (h,m)=text.split(":").map { it.toInt() };return h*60+m
    }
    init {
        require(mode in setOf("ALWAYS","UTC_WINDOW")) { "Неизвестная политика времени входа" }
        val start=minute(startUtc);val end=minute(endUtc)
        require(mode!="UTC_WINDOW" || start!=end) { "Пустое окно; для 24/7 выбери круглосуточный режим" }
    }
    fun allows(epochMs:Long):Boolean {
        if(epochMs<=0L) return false
        if(mode=="ALWAYS") return true
        val now=Instant.ofEpochMilli(epochMs).atOffset(ZoneOffset.UTC)
        val current=now.hour*60+now.minute;val start=minute(startUtc);val end=minute(endUtc)
        return if(start<end) current>=start && current<end else current>=start || current<end
    }
    fun toJson()=JSONObject().put("mode",mode).put("start_utc",startUtc).put("end_utc",endUtc)
        .put("timezone","UTC").put("scope","NEW_EXPOSURE_ONLY")
    companion object {
        fun fromJson(json:JSONObject)=WilliamsEntryTimePolicy(json.optString("mode","ALWAYS"),json.optString("start_utc","08:00"),json.optString("end_utc","20:00"))
    }
}
