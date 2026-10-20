package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import java.security.MessageDigest

/** Stable representation for reproducible evidence, independent of JSON key order. */
internal fun canonicalWaveJson(value:Any?):String = when(value) {
    null,JSONObject.NULL->"null"
    is JSONObject->value.keys().asSequence().toList().sorted().joinToString(",","{","}") {
        JSONObject.quote(it)+":"+canonicalWaveJson(value.get(it))
    }
    is JSONArray->(0 until value.length()).joinToString(",","[","]") {
        canonicalWaveJson(value.get(it))
    }
    is String->JSONObject.quote(value)
    else->value.toString()
}

internal fun waveEvidenceSha256(text:String):String = MessageDigest.getInstance("SHA-256")
    .digest(text.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }

private class CausalWaveArchiveSeries(input:List<WilliamsWaveBar>,val duration:Long) {
    val bars=input.sortedBy { it.t }
    private val invalid=IntArray(bars.size+1)
    private val gaps=IntArray(bars.size+1)
    private val duplicates=IntArray(bars.size+1)
    private var hashedCount=0
    private var inputHash=""
    init {
        for((i,b) in bars.withIndex()) {
            val good=b.t>=0L && listOf(b.o,b.h,b.l,b.c,b.v).all(Double::isFinite) &&
                b.l>0 && b.h>=maxOf(b.o,b.c,b.l) && b.l<=minOf(b.o,b.c) && b.v>=0
            invalid[i+1]=invalid[i]+if(good) 0 else 1
            val difference=if(i>0) b.t-bars[i-1].t else duration
            gaps[i+1]=gaps[i]+if(difference>duration || difference in 1 until duration) 1 else 0
            duplicates[i+1]=duplicates[i]+if(i>0 && difference==0L) 1 else 0
        }
    }
    fun countAt(asOf:Long):Int {
        var lo=0;var hi=bars.size
        while(lo<hi) {
            val mid=(lo+hi)/2
            if(bars[mid].t<=asOf-duration) lo=mid+1 else hi=mid
        }
        return lo
    }
    private fun inputHashAt(count:Int):String {
        require(count>=hashedCount) { "NON_MONOTONIC_REPLAY_SOURCE_CURSOR" }
        while(hashedCount<count) {
            val b=bars[hashedCount++]
            inputHash=waveEvidenceSha256("$inputHash\n${b.t},${b.o},${b.h},${b.l},${b.c},${b.v}")
        }
        return inputHash
    }
    fun quality(count:Int):JSONObject = JSONObject().put("closed_input_bars",count)
        .put("input_prefix_sha256",inputHashAt(count))
        .put("invalid_ohlcv",invalid[count]).put("gap_boundaries",gaps[count])
        .put("duplicate_open_times",duplicates[count])
        .put("first_open_ms",if(count>0) bars.first().t else JSONObject.NULL)
        .put("last_closed_ms",if(count>0) bars[count-1].t+duration else JSONObject.NULL)
}

private fun adaptiveReplayRecords(report:JSONObject):Map<String,String> {
    val records=sortedMapOf<String,String>()
    fun add(array:String,key:(JSONObject)->String) {
        val rows=report.getJSONArray(array)
        for(i in 0 until rows.length()) {
            val item=rows.getJSONObject(i)
            records["$array:"+key(item)]=canonicalWaveJson(item)
        }
    }
    add("hypotheses") { it.getString("scenario_id") }
    add("observed_parent_child_links") { it.getString("parent")+"/"+it.getString("child") }
    add("complex_wave_patterns") {
        listOf("frame","direction","pattern","start_at","end_at").joinToString(":") { k->it.get(k).toString() }
    }
    return records
}

/**
 * Visit EVERY supplied candle-close event, not just the last archive window.
 * Calls the actual Android observational model on the causal prefix at each
 * event. The per-snapshot pivot window is explicitly bounded; the timeline is
 * not. Missing history remains missing, and expired hypotheses are recorded
 * as NO_LONGER_OBSERVED rather than falsely called invalidated or completed.
 *
 * No network, trading API, order state, stop policy or fill simulation is used.
 * The callback streams snapshots without retaining the entire replay in RAM.
 * A previous snapshot resumes both the event cursor and the evidence hash chain.
 */
internal fun replayWilliamsWaveArchive(
    history:Map<String,List<WilliamsWaveBar>>,
    throughMs:Long,
    afterMs:Long=0L,
    clockFrames:Set<String> = history.keys,
    analysisLimit:Int=1800,
    previousSnapshot:JSONObject?=null,
    cancelled:()->Boolean = { false },
    onSnapshot:(JSONObject)->Unit = {}
):JSONObject {
    require(throughMs>0L && afterMs in 0L..throughMs && analysisLimit>=40) { "INVALID_REPLAY_RANGE" }
    require(history.keys.all { it in williamsWaveFrames }) { "UNSUPPORTED_REPLAY_FRAME" }
    require(clockFrames.isNotEmpty() && clockFrames.all { it in history }) { "MISSING_REPLAY_CLOCK" }
    require(previousSnapshot==null || previousSnapshot.getLong("as_of_closed_ms")==afterMs) {
        "REPLAY_CHECKPOINT_TIME_MISMATCH"
    }
    require(previousSnapshot==null || previousSnapshot.getInt("analysis_window_limit")==analysisLimit) {
        "REPLAY_CHECKPOINT_WINDOW_MISMATCH"
    }
    val archive=history.mapValues { (frame,rows)->
        CausalWaveArchiveSeries(rows,williamsWaveFrames.getValue(frame))
    }
    fun qualityAt(counts:Map<String,Int>):JSONObject {
        val quality=JSONObject()
        for((frame,series) in archive) {
            val n=counts.getValue(frame)
            if(n>0) quality.put(frame,series.quality(n))
        }
        return quality
    }
    // A checkpoint is valid only for exactly the same already-closed source
    // bytes, model window, clock and evidence. A changed old OHLCV bar must
    // not silently continue a previously accepted replay chain.
    if(previousSnapshot!=null) {
        require(canonicalWaveJson(previousSnapshot.getJSONArray("clock_frames"))==
            canonicalWaveJson(JSONArray(clockFrames.sorted()))) { "REPLAY_CHECKPOINT_CLOCK_MISMATCH" }
        val counts=archive.mapValues { (_,s)->s.countAt(afterMs) }
        val prefix=archive.mapValues { (f,s)->s.bars.subList(0,counts.getValue(f)) }
        val report=buildAdaptiveHistoricalWaveReport(prefix,afterMs,analysisLimit)
        val hash=waveEvidenceSha256(canonicalWaveJson(JSONObject().put("report",report)
            .put("archive_quality",qualityAt(counts))))
        require(hash==previousSnapshot.getString("state_sha256") &&
            canonicalWaveJson(JSONObject(adaptiveReplayRecords(report)))==
                canonicalWaveJson(previousSnapshot.getJSONObject("record_checkpoint"))) {
            "REPLAY_CHECKPOINT_SOURCE_OR_MODEL_CHANGED"
        }
        require(waveEvidenceSha256(previousSnapshot.getString("previous_chain_sha256")+
            "\n$afterMs\n$hash")==previousSnapshot.getString("chain_sha256")) {
            "REPLAY_CHECKPOINT_HASH_MISMATCH"
        }
    }
    val moments=java.util.TreeSet<Long>()
    for(frame in clockFrames) {
        val series=archive.getValue(frame)
        for(b in series.bars) if(b.t>=0L && b.t<=throughMs-series.duration) {
            val end=b.t+series.duration
            if(end>afterMs) moments.add(end)
        }
    }
    var previousAt=afterMs
    var previousChain=previousSnapshot?.getString("chain_sha256")?:""
    var previousRecords=previousSnapshot?.getJSONObject("record_checkpoint")?.let { obj->
        obj.keys().asSequence().associateWith { obj.getString(it) }
    }.orEmpty()
    var processed=0
    var revisions=0L
    var interrupted=false
    val w4Counts=sortedMapOf<String,Int>()
    val patternCounts=sortedMapOf<String,Int>()
    var countWithLinks=0
    for(asOf in moments) {
        if(cancelled()) { interrupted=true;break }
        val counts=archive.mapValues { (_,s)->s.countAt(asOf) }
        val prefix=archive.mapValues { (f,s)->s.bars.subList(0,counts.getValue(f)) }
        val report=buildAdaptiveHistoricalWaveReport(prefix,asOf,analysisLimit)
        val records=adaptiveReplayRecords(report)
        val changes=JSONArray()
        for((id,body) in records) if(previousRecords[id]!=body) {
            changes.put(JSONObject().put("record_id",id)
                .put("event",if(id in previousRecords) "REVISED" else "FIRST_OBSERVED")
                .put("known_at_ms",asOf).put("evidence",JSONObject(body)))
        }
        for(id in (previousRecords.keys-records.keys).sorted()) {
            changes.put(JSONObject().put("record_id",id).put("event","NO_LONGER_OBSERVED")
                .put("known_at_ms",asOf).put("reason","ALTERNATIVE_REPLACED_INVALIDATED_OR_OUTSIDE_WORKING_WINDOW"))
        }
        val quality=qualityAt(counts)
        val evidence=JSONObject().put("report",report).put("archive_quality",quality)
        val stateHash=waveEvidenceSha256(canonicalWaveJson(evidence))
        val chain=waveEvidenceSha256("$previousChain\n$asOf\n$stateHash")
        val snapshot=JSONObject().put("schema","WILLIAMS_CAUSAL_WAVE_REPLAY_V1")
            .put("as_of_closed_ms",asOf).put("previous_as_of_closed_ms",previousAt)
            .put("analysis_window_limit",analysisLimit)
            .put("clock_frames",JSONArray(clockFrames.sorted()))
            .put("state_sha256",stateHash).put("previous_chain_sha256",previousChain)
            .put("chain_sha256",chain).put("changes",changes)
            .put("archive_quality",quality).put("report",report)
            .put("record_checkpoint",JSONObject(records))
            .put("can_place_orders",false).put("can_close_positions",false)
        val hypotheses=report.getJSONArray("hypotheses")
        for(i in 0 until hypotheses.length()) {
            val state=hypotheses.getJSONObject(i).getJSONObject("w4_ao").getString("status")
            w4Counts[state]=(w4Counts[state]?:0)+1
        }
        val patterns=report.getJSONArray("complex_wave_patterns")
        for(i in 0 until patterns.length()) {
            val pattern=patterns.getJSONObject(i).getString("pattern")
            patternCounts[pattern]=(patternCounts[pattern]?:0)+1
        }
        if(report.getJSONArray("observed_parent_child_links").length()>0) countWithLinks++
        // The caller persists/consumes this snapshot before the cursor advances.
        onSnapshot(snapshot)
        processed++
        revisions+=changes.length()
        previousAt=asOf
        previousChain=chain
        previousRecords=records
    }
    return JSONObject().put("schema","WILLIAMS_CAUSAL_WAVE_REPLAY_SUMMARY_V1")
        .put("status",when {
            interrupted->"INTERRUPTED_RESUMABLE"
            moments.isEmpty()->"NO_CLOSED_CANDLE_EVENTS"
            else->"SUPPLIED_TIMELINE_REPLAYED"
        })
        .put("after_ms",afterMs).put("requested_through_ms",throughMs)
        .put("last_replayed_ms",previousAt).put("scheduled_close_events",moments.size)
        .put("processed_close_events",processed).put("revision_events",revisions)
        .put("clock_frames",JSONArray(clockFrames.sorted()))
        .put("analysis_window_limit",analysisLimit).put("final_chain_sha256",previousChain)
        .put("snapshots_with_links",countWithLinks).put("w4_ao_observations",JSONObject(w4Counts))
        .put("complex_pattern_observations",JSONObject(patternCounts))
        .put("all_exchange_history_proven",false).put("human_wave_count_accuracy_proven",false)
        .put("wave_genealogy_confirmed",false).put("tc2_integration_approved",false)
        .put("can_place_orders",false).put("can_close_positions",false)
}
