package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import kotlin.math.abs

/** Standalone runner for the production Kotlin wave model, never a trading engine. */
object WilliamsWaveArchiveReplayCli {
    /** Independent rolling-sum reference, from the original closed OHLCV rows. */
    private fun verifyAo(row:JSONObject,history:Map<String,List<WilliamsWaveBar>>,now:Long):Boolean {
        val evidence=row.getJSONObject("w4_ao")
        if(!evidence.has("w3_ao_peak")) return false
        val frame=evidence.getJSONObject("resolution").getString("frame")
        val ms=williamsWaveFrames.getValue(frame)
        val bars=history.getValue(frame).filter { it.t<=now-ms }.sortedBy { it.t }
        val sums=DoubleArray(bars.size+1)
        for(i in bars.indices) sums[i+1]=sums[i]+(bars[i].h+bars[i].l)/2
        fun ao(i:Int)=(sums[i+1]-sums[i-4])/5-(sums[i+1]-sums[i-33])/34
        val points=row.getJSONArray("anchors")
        val parentMs=williamsWaveFrames.getValue(row.getString("frame"))
        fun actual(i:Int):Long {
            val p=points.getJSONObject(i);val start=p.getLong("extreme_at");val price=p.getDouble("price")
            return bars.filter { b->b.t>=start && b.t<start+parentMs &&
                abs((if(p.getString("kind")=="HIGH") b.h else b.l)-price)<=maxOf(1e-9,price*1e-9) }.maxOf { it.t }
        }
        val from=actual(2);val third=actual(3);val fourth=actual(4)
        check(from==evidence.getLong("w2_actual_extreme_ms"))
        check(third==evidence.getLong("w3_actual_extreme_ms"))
        check(fourth==evidence.getLong("w4_actual_extreme_ms"))
        val sign=if(row.getString("direction")=="UP") 1 else -1
        val thirdIndex=bars.indexOfFirst { it.t==third }
        val fourthIndex=bars.indexOfFirst { it.t==fourth }
        val momentum=evidence.getJSONObject("w3_momentum")
        val observed=momentum.getString("status")=="GLOBAL_PEAK_AND_PRICE_LAG_OBSERVED"
        val peak=if(observed) momentum.getDouble("global_peak") else bars.indices.filter {
            it>=33 && bars[it].t>=from && it<=minOf(fourthIndex,thirdIndex+5) && sign*ao(it)>0 }
            .maxOf { sign*ao(it) }*sign
        check(abs(peak-evidence.getDouble("w3_ao_peak"))<=maxOf(1e-7,abs(peak)*1e-8))
        val peakAt=evidence.getLong("w3_ao_peak_ms")
        val correction=bars.indices.filter { it>=33 && bars[it].t>maxOf(third,peakAt) && bars[it].t<=fourth }
        val crossed=correction.firstOrNull { sign*ao(it)<0 }
        check((crossed!=null)==evidence.getBoolean("crossed_zero"))
        check(crossed==null || bars[crossed].t+ms==evidence.getLong("cross_known_at_ms"))
        val nearest=correction.minOf { abs(ao(it)) }
        check(abs(nearest-evidence.getDouble("wave4_min_absolute_ao"))<=maxOf(1e-7,abs(peak)*1e-8))
        if(observed) {
            val cycle=actual(0)
            val global=bars.indices.filter { it>=33 && bars[it].t in cycle..fourth }.maxOf { sign*ao(it) }
            check(abs(global-sign*peak)<=maxOf(1e-7,abs(peak)*1e-8))
            check((peakAt-third)/ms in 1L..5L)
        }
        return true
    }

    @JvmStatic fun main(args:Array<String>) {
        require(args.size==2) { "usage: WilliamsWaveArchiveReplayCli input.json output_directory" }
        val inputFile=File(args[0])
        val input=JSONObject(inputFile.readText())
        val frames=input.getJSONObject("frames")
        val history=frames.keys().asSequence().associateWith { f->
            val rows=frames.getJSONArray(f)
            (0 until rows.length()).map { i->
                val b=rows.getJSONArray(i)
                WilliamsWaveBar(b.getLong(0),b.getDouble(1),b.getDouble(2),b.getDouble(3),b.getDouble(4),b.getDouble(5))
            }
        }
        val through=input.optLong("through_ms",history.maxOf { (f,b)->
            b.lastOrNull()?.t?.plus(williamsWaveFrames.getValue(f))?:0L
        })
        val clocks=input.optJSONArray("clock_frames")?.let { a->
            (0 until a.length()).map { a.getString(it) }.toSet()
        }?:history.keys
        val output=File(args[1]);output.mkdirs()
        var last:JSONObject?=null
        var anchorChecks=0L;var futureChecks=0L;var proofChecks=0L;var aoChecks=0L
        val uniquePatterns=mutableSetOf<String>()
        val examples=JSONArray()
        val outOfRange=mutableSetOf<String>()
        var prefixCount=0
        val poison=history.mapValues { (frame,bars)->
            val ms=williamsWaveFrames.getValue(frame)
            bars+(1..2000).map { i->
                WilliamsWaveBar(through+i*ms,999.0,1001.0,998.0,1000.0,1.0)
            }
        }
        File(output,"events.jsonl").bufferedWriter().use { writer->
            val result=replayWilliamsWaveArchive(history,through,clockFrames=clocks) { snapshot->
                val now=snapshot.getLong("as_of_closed_ms")
                val report=snapshot.getJSONObject("report")
                val hypotheses=report.getJSONArray("hypotheses")
                for(i in 0 until hypotheses.length()) {
                    val row=hypotheses.getJSONObject(i)
                    check(row.getLong("known_at_ms")<=now)
                    val points=row.getJSONArray("anchors")
                    for(j in 0 until points.length()) {
                        check(points.getJSONObject(j).getLong("known_at")<=now);anchorChecks++
                    }
                    val w4=row.getJSONObject("w4_ao")
                    if(verifyAo(row,history,now)) aoChecks++
                    check(!w4.getBoolean("wave4_completion_confirmed"))
                    check(w4.isNull("cross_known_at_ms") || w4.getLong("cross_known_at_ms")<=now)
                    if(w4.optBoolean("minimum_condition_observed")) {
                        check(w4.getBoolean("crossed_zero"))
                        check(w4.getJSONObject("resolution").getString("status")=="BOOK_WINDOW_OBSERVED")
                    }
                    val key=row.getString("frame")+":"+w4.getString("status")
                    if(outOfRange.add(key) && examples.length()<30) examples.put(
                        JSONObject().put("as_of",now).put("hypothesis",row))
                }
                val patterns=report.getJSONArray("complex_wave_patterns")
                for(i in 0 until patterns.length()) {
                    val p=patterns.getJSONObject(i)
                    check(p.getLong("known_at")<=now)
                    check(!p.getBoolean("can_place_orders"))
                    val proof=p.getJSONObject("internal_proof")
                    check(proof.getLong("child_known_at")<=now)
                    if(proof.getString("status")=="PRICE_SUBDIVISIONS_CONFIRMED") {
                        check(canonicalWaveJson(proof.getJSONArray("expected"))==
                            canonicalWaveJson(proof.getJSONArray("observed")))
                        proofChecks++
                    }
                    uniquePatterns.add(p.getString("frame")+":"+p.getString("pattern")+
                        ":"+p.getLong("start_at")+":"+p.getLong("end_at"))
                }
                if(prefixCount%200==0) {
                    // Real future suffix plus 2000 extreme future bars per TF.
                    check(canonicalWaveJson(buildAdaptiveHistoricalWaveReport(poison,now))==
                        canonicalWaveJson(report)) { "FUTURE_SUFFIX_CHANGED_CAUSAL_REPORT_AT_$now" }
                    futureChecks++
                }
                prefixCount++
                val compact=JSONObject(snapshot.toString())
                compact.remove("report");compact.remove("record_checkpoint")
                writer.write(compact.toString());writer.newLine()
                last=snapshot
            }
            result.put("symbol",input.optString("symbol"))
                .put("source",input.opt("source")?:"UNSPECIFIED")
                .put("input_sha256",waveEvidenceSha256(inputFile.readText()))
                .put("validation",JSONObject().put("anchor_known_at_checks",anchorChecks)
                    .put("independent_rolling_sum_ao_checks",aoChecks)
                    .put("future_suffix_comparisons",futureChecks)
                    .put("positive_price_subdivision_observations",proofChecks)
                    .put("distinct_complex_candidates",uniquePatterns.size).put("failures",0))
            File(output,"summary.json").writeText(result.toString(2))
            println(result.toString())
        }
        File(output,"examples.json").writeText(examples.toString(2))
        last?.let { File(output,"last-checkpoint.json").writeText(it.toString()) }
    }
}
