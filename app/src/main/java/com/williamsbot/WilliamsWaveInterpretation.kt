package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/** TC1 PDF61-65 / Course PDF34-46: explicit, degree-scoped engineering
 * interpretations. These do not certify a unique Elliott count or forecast.
 * No exchange, account, stop, order or execution dependency is permitted. */
internal fun williamsWaveInterpretation(hierarchy:JSONObject):JSONObject {
    fun rows(a:JSONArray?):List<JSONObject> = if(a==null) emptyList() else
        (0 until a.length()).mapNotNull { a.optJSONObject(it) }
    fun number(x:Any?):Boolean = x is Number && x.toDouble().isFinite()
    val asOf=hierarchy.optLong("as_of",0L).coerceAtLeast(0L)
    val frames=listOf("1d","6h","4h","1h","15m")
    val active=mapOf(2 to "W2",3 to "W3",4 to "W4",5 to "W5",6 to "A",7 to "B",8 to "C")
    val corrective=setOf("W2","W4","A","C")
    val liveBreaks=setOf("LIVE_PRICE_INVALIDATED_COUNT","LOWER_FRAME_OBSERVED_INVALIDATION")
    val all=rows(hierarchy.optJSONArray("scenarios"))
    val ids=all.groupingBy { it.optString("scenario_id") }.eachCount()
    val readings=rows(hierarchy.optJSONArray("frame_readings"))
    val errors=hierarchy.optJSONObject("frame_errors") ?: JSONObject()
    fun fresh(frame:String):Boolean {
        if(errors.has(frame)) return false
        val reading=readings.filter { it.optString("frame")==frame }.singleOrNull() ?: return false
        val close=reading.optLong("as_of_close_ms",0L)
        return reading.optString("state")=="OBSERVED" && number(reading.opt("as_of_close_ms")) &&
            close>0L && asOf-close in 0 until williamsWaveFrames.getValue(frame)
    }
    fun causal(s:JSONObject):Boolean {
        val frame=s.optString("frame");val direction=s.optString("direction")
        val duration=williamsWaveFrames[frame] ?: return false
        val anchors=rows(s.optJSONArray("anchors"))
        if(direction !in setOf("UP","DOWN") || anchors.size !in 2..8 ||
            anchors.size!=s.optJSONArray("anchors")?.length()) return false
        val label=active.getValue(anchors.size)
        val expected=if(label in corrective) { if(direction=="UP") "DOWN" else "UP" } else direction
        if(s.optString("active_label")!=label || s.optString("active_direction")!=expected ||
            s.optLong("observed_at")!=asOf) return false
        for((i,a) in anchors.withIndex()) {
            val t=a.optLong("extreme_at",-1L);val known=a.optLong("known_at",-1L)
            val kind=if((i%2==0)==(direction=="UP")) "LOW" else "HIGH"
            if(listOf("extreme_at","known_at","price").any { !number(a.opt(it)) } ||
                a.optDouble("price",Double.NaN)<=0.0 || t<0L || a.optString("kind")!=kind ||
                known<t+3*duration || known>asOf || i>0 && t<=anchors[i-1].optLong("extreme_at")) return false
        }
        val low=s.optDouble("leg_low",Double.NaN);val high=s.optDouble("leg_high",Double.NaN)
        return listOf("leg_low","leg_high","invalidation").all { number(s.opt(it)) && s.optDouble(it)>0.0 } &&
            low<=high && s.optLong("leg_start",-1L)==anchors.last().optLong("extreme_at")
    }
    fun broken(s:JSONObject):Boolean {
        if((s.optJSONArray("anchors")?.length() ?: 0)>=6) return false
        // A Course PDF46/48 W2 touch is not a breach; later motives keep their own limits.
        val w2Boundary=(s.optJSONArray("anchors")?.length() ?: 0)<=3
        return if(s.optString("direction")=="UP") {
            if(w2Boundary) s.getDouble("leg_low")<s.getDouble("invalidation")
            else s.getDouble("leg_low")<=s.getDouble("invalidation")
        } else {
            if(w2Boundary) s.getDouble("leg_high")>s.getDouble("invalidation")
            else s.getDouble("leg_high")>=s.getDouble("invalidation")
        }
    }
    fun state(parent:JSONObject,child:JSONObject):String {
        val p=parent.getString("active_label");val c=child.getString("active_label")
        val childImpulse=c in setOf("W3","W5") && child.getString("active_direction")==child.getString("direction")
        if(parent.getString("status")=="INVALIDATED") {
            return if(broken(parent) && childImpulse && child.getString("direction")!=parent.getString("direction"))
                "SENIOR_REVERSAL_CANDIDATE" else "UNRESOLVED"
        }
        if(broken(parent)) return "UNRESOLVED"
        if(p in setOf("W3","W5")) {
            if(child.getString("direction")==parent.getString("direction")) {
                if(c in corrective) return "JUNIOR_CORRECTION_CANDIDATE"
                if(childImpulse) return "IMPULSE_CONTINUATION_CANDIDATE"
            }
            if(childImpulse && child.getString("direction")!=parent.getString("direction")) return "OPPOSING_IMPULSE_UNRESOLVED"
        } else if(p in corrective && childImpulse && child.getString("direction")==parent.getString("active_direction"))
            return "PARENT_CORRECTION_IN_PROGRESS_CANDIDATE"
        return "UNRESOLVED"
    }
    val rejected=mutableListOf<String>()
    val usable=all.filter { s ->
        val id=s.optString("scenario_id");val status=s.optString("status")
        val reasonArray=s.optJSONArray("invalid_reasons") ?: JSONArray()
        val reasons=(0 until reasonArray.length()).map { reasonArray.optString(it) }.toSet()
        val ok=id.isNotEmpty() && ids[id]==1 && causal(s) && status in setOf("PROVISIONAL","INVALIDATED") &&
            (status!="INVALIDATED" || reasons.isNotEmpty() && reasons.all { it in liveBreaks })
        if(!ok) rejected+=id
        ok
    }
    fun explanation(state:String):String = when(state) {
        "JUNIOR_CORRECTION_CANDIDATE" -> "Младшая коррекция внутри действующего старшего импульса; старший разворот не подтверждён."
        "IMPULSE_CONTINUATION_CANDIDATE" -> "Младший импульс согласован с действующей старшей волной; продолжение остаётся гипотезой."
        "PARENT_CORRECTION_IN_PROGRESS_CANDIDATE" -> "Младший импульс развивается в направлении старшей коррекции; это не доказывает смену старшего тренда."
        "OPPOSING_IMPULSE_UNRESOLVED" -> "Младший импульс направлен против старшей волны; её структурная отмена не наблюдается."
        "SENIOR_REVERSAL_CANDIDATE" -> "Уровень отмены старшего незавершённого импульса нарушен; встречная младшая структура допускает старший разворот, но не доказывает его."
        "ALTERNATIVES" -> "Допустимые разметки дают разные объяснения; однозначного вывода нет."
        "HISTORY_UNVERIFIED" -> "Нет свежих независимых закрытых свечей обоих масштабов; волновой вывод не подтверждён."
        else -> "Недостаточно совместимой структуры для различения коррекции, продолжения и старшего разворота."
    }
    val scopes=JSONArray()
    for(i in 0 until frames.lastIndex) for(childFrame in frames.drop(i+1)) {
        val parentFrame=frames[i];val isFresh=asOf>0L && fresh(parentFrame) && fresh(childFrame)
        val relations=mutableListOf<JSONObject>()
        if(isFresh) for(parent in usable.filter { it.optString("frame")==parentFrame }) {
            val legStart=parent.getLong("leg_start")
            val observedStart=parent.optLong("leg_observation_start",0L)
            // A missing refinement cannot establish intrabar extreme order.
            val boundary=if(observedStart>legStart) observedStart else legStart+williamsWaveFrames.getValue(parentFrame)
            val low=parent.getDouble("leg_low");val high=parent.getDouble("leg_high")
            for(child in usable.filter { it.optString("frame")==childFrame && it.optString("status")=="PROVISIONAL" }) {
                val anchors=rows(child.getJSONArray("anchors"))
                if(anchors.first().getLong("extreme_at")<boundary || child.getDouble("leg_low")<low ||
                    child.getDouble("leg_high")>high || anchors.any { it.getDouble("price") !in low..high }) continue
                val warnings=JSONArray()
                if(child.optBoolean("divergence") && parent.optString("w4_zero")!="MINIMUM_ONLY")
                    warnings.put("JUNIOR_DIVERGENCE_WITHOUT_PARENT_W4_ZERO_PROOF")
                relations+=JSONObject().put("parent",parent.getString("scenario_id")).put("child",child.getString("scenario_id"))
                    .put("state",state(parent,child)).put("parent_active_leg",parent.getString("active_label"))
                    .put("child_active_leg",child.getString("active_label"))
                    .put("parent_motive_direction",parent.getString("direction"))
                    .put("parent_active_direction",parent.getString("active_direction"))
                    .put("child_motive_direction",child.getString("direction"))
                    .put("child_active_direction",child.getString("active_direction"))
                    .put("active_directions_aligned",parent.getString("active_direction")==child.getString("active_direction"))
                    .put("parent_invalidation",parent.getDouble("invalidation")).put("parent_count_broken",broken(parent))
                    .put("parent_observation_start",boundary).put("child_sequence_start",anchors.first().getLong("extreme_at"))
                    .put("anchors_known_at",(rows(parent.getJSONArray("anchors"))+anchors).maxOf { it.getLong("known_at") })
                    .put("proof","CAUSAL_ANCHORS_AND_FULL_OBSERVED_TIME_PRICE_CONTAINMENT")
                    .put("parent_w4_minimum_only",parent.optString("w4_zero")=="MINIMUM_ONLY")
                    .put("parent_scale_100_140",parent.optInt("scale_bars") in 100..140)
                    .put("degree_warnings",warnings).put("wave_genealogy_confirmed",false)
                    .put("can_place_orders",false).put("can_close",false).put("can_reverse",false)
            }
        }
        val states=relations.map { it.getString("state") }.distinct().sorted()
        val summary=when { !isFresh->"HISTORY_UNVERIFIED";states.isEmpty()->"UNRESOLVED";states.size==1->states.single();else->"ALTERNATIVES" }
        scopes.put(JSONObject().put("parent_frame",parentFrame).put("child_frame",childFrame).put("state",summary)
            .put("candidate_states",JSONArray(states)).put("relation_count",relations.size)
            .put("relations",JSONArray(relations.sortedWith(compareBy({it.getString("parent")},{it.getString("child")}))))
            .put("explanation_ru",explanation(summary)).put("wave_genealogy_confirmed",false))
    }
    return JSONObject().put("schema","WILLIAMS_WAVE_INTERPRETATION_V1").put("as_of",asOf).put("scopes",scopes)
        .put("rejected_scenario_ids",JSONArray(rejected.sorted())).put("source","TC1_PDF61_65_COURSE_PDF34_46")
        .put("method","EXPLICIT_ENGINEERING_PREDICATES_NOT_UNIQUE_ELLIOTT_COUNT")
        .put("wave_genealogy_confirmed",false).put("forecast_accuracy_certified",false)
        .put("can_place_orders",false).put("can_close",false).put("can_reverse",false)
}
