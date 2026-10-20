package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test
import kotlin.math.sin

class WilliamsDeviceWaveReplayTest {
    private val h=3_600_000L
    private val bars=(0 until 140).map { i->
        val p=100+8*sin(i/4.0)
        WilliamsWaveBar((1000+i)*h,p,p+1,p-1,p,10.0)
    }
    @Test fun aRestartedCursorProducesTheSameNextStateAndHashChain() {
        var cursor=DeviceWaveReplayCursor()
        var checkpoint:JSONObject?=null
        val chain=mutableListOf<String>()
        for(n in 1..140) {
            val step=buildDeviceWaveReplayStep(cursor,mapOf("1h" to bars.take(n)),(1000+n)*h)
            chain+=step.cursor.chain;cursor=step.cursor
            if(n==70) checkpoint=JSONObject(step.checkpoint.toString())
            assertFalse(step.journal.getBoolean("can_place_orders"))
        }
        var restored=deviceWaveCursorFromJson(checkpoint!!)
        val second=mutableListOf<String>()
        for(n in 71..140) {
            val step=buildDeviceWaveReplayStep(restored,mapOf("1h" to bars.take(n)),(1000+n)*h)
            second+=step.cursor.chain;restored=step.cursor
        }
        assertEquals(chain.drop(70),second)
        assertEquals(140L,restored.processed)
    }

    @Test fun futureBarsCannotAlterADeviceHistoricalCheckpoint() {
        val a=buildDeviceWaveReplayStep(DeviceWaveReplayCursor(),mapOf("1h" to bars.take(90)),1090*h)
        val b=buildDeviceWaveReplayStep(DeviceWaveReplayCursor(),mapOf("1h" to bars),1090*h)
        assertEquals(a.stateHash,b.stateHash)
        assertEquals(a.cursor,b.cursor)
    }

    @Test fun checkpointEvidenceHashSurvivesActualJsonNumberSerialization() {
        val step=buildDeviceWaveReplayStep(DeviceWaveReplayCursor(),mapOf("1h" to bars.take(90)),1090*h)
        val disk=JSONObject(step.checkpoint.toString())
        assertEquals(step.stateHash,waveEvidenceSha256(canonicalWaveJson(disk.getJSONObject("report"))))
        val numbers=JSONObject().put("whole",100.0).put("small",1.0e-9).put("large",1.0e10)
            .put("negative_zero",-0.0)
        assertEquals(canonicalWaveJson(numbers),canonicalWaveJson(JSONObject(numbers.toString())))
    }

    @Test fun changedModelOrRepeatedCloseCannotSilentlyAdvanceACheckpoint() {
        val step=buildDeviceWaveReplayStep(DeviceWaveReplayCursor(),mapOf("1h" to bars.take(90)),1090*h)
        try {
            buildDeviceWaveReplayStep(step.cursor,mapOf("1h" to bars),1090*h)
            fail("Repeated clock must be rejected")
        } catch(e:IllegalArgumentException) { assertEquals("NON_MONOTONIC_DEVICE_WAVE_REPLAY",e.message) }
        try {
            deviceWaveCursorFromJson(JSONObject(step.checkpoint.toString()).put("model","OLD"))
            fail("Old analytical model must be replayed")
        } catch(e:IllegalArgumentException) { assertEquals("DEVICE_REPLAY_MODEL_CHANGED",e.message) }
    }
}
