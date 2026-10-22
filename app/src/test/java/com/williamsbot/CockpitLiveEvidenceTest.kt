package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

/**
 * Diagnostics (15): SOLUSDT had a populated OPEN_PROTECTED journal,
 * but its independently signed positions snapshot had stop=null and
 * status=UNVERIFIED_OR_STALE. Never show a green STOP or false FLAT.
 */
class CockpitLiveEvidenceTest {
    private val now=1_792_708_039_846L
    private fun intent()=JSONObject()
        .put("symbol","SOLUSDT").put("direction","SHORT")
        .put("campaign_status","OPEN_PROTECTED").put("protection_status","PROTECTED")
        .put("strategy_ownership_verified",true)
        .put("protection_client_id_suffix","known")
        .put("persisted_protective_stop","104.02")
        .put("confirmed_position_qty","0.91")
        .put("last_reconciled_ms",now-1000L)
    private fun position(stop:Double?=104.02,proof:String="OWNED_STOP_SEEN")=PositionView(
        symbol="SOLUSDT",direction="SHORT",qty=0.91,entry=103.61,stop=stop,take=null,
        riskPct=.0004,stopVerificationStatus=proof
    )
    private fun status(
        positions:List<PositionView> = listOf(position()),
        snapshotAt:Long=now,positionsAt:Long=now,
        snapshotOk:Boolean=true,reportedCount:Int=positions.size
    )=Status(positions=positions,openPositions=reportedCount,
        snapshotAtMs=snapshotAt,positionsSnapshotAtMs=positionsAt,
        positionsSnapshotOk=snapshotOk)

    @Test fun signedMissingStopNeverConfirmsTheOldPersistedJournal() {
        for(st in listOf(
            status(positions=listOf(position(null,"UNVERIFIED_OR_STALE"))),
            status(positions=listOf(position(104.71))),
            status(positions=listOf(position(104.02,"UNVERIFIED_OR_STALE"))),
            status(positionsAt=now-16_000L),
            status(snapshotOk=false),
            status(snapshotAt=now-31_000L),
            status(positions=emptyList(),reportedCount=0)
        )) assertFalse(cockpitSignedStopVerified(intent(),st,now))
        assertTrue(cockpitSignedStopVerified(intent(),status(),now))
    }

    @Test fun changingOwnerDirectionOrQuantityBlocksGreenStop() {
        val foreign=intent().put("symbol","BNBUSDT")
        assertFalse(cockpitSignedStopVerified(foreign,status(),now))
        assertFalse(cockpitSignedStopVerified(intent().put("direction","LONG"),status(),now))
        assertFalse(cockpitSignedStopVerified(intent().put("strategy_ownership_verified",false),status(),now))
        assertFalse(cockpitSignedStopVerified(intent(),status(
            positions=listOf(position().copy(qty=0.0))),now))
        assertFalse(cockpitSignedStopVerified(intent(),status(
            positions=listOf(position(),position())),now))
    }

    @Test fun oldOrContradictoryPositionSnapshotCannotDisplayFlatZero() {
        val known=status()
        assertEquals("1",cockpitKnownOpenPositionsLabel(known,now))
        assertEquals("—",cockpitKnownOpenPositionsLabel(status(positionsAt=now-30_000L),now))
        assertEquals("—",cockpitKnownOpenPositionsLabel(status(snapshotOk=false),now))
        assertEquals("—",cockpitKnownOpenPositionsLabel(status(reportedCount=0),now))
        assertTrue(cockpitKnownOpenPositionsDetail(status(positionsAt=now-30_000L),now)
            .contains("предыдущем снимке 1"))
        assertTrue(cockpitKnownOpenPositionsDetail(status(positions=emptyList(),reportedCount=0,
            positionsAt=now-30_000L),now).contains("НЕ подтверждено"))
    }

    @Test fun wm3JournalExplanationIsRussianNotRawEnglish() {
        val explanation=cockpitBookSignalRu("BOOK H1 WM3 eligible confirmed fractal outside Teeth; waiting")
        assertTrue(explanation.contains("третий Мудрец"))
        assertFalse(explanation.contains("BOOK H1"))
        assertTrue(explanation.contains("M15"))
    }
}
