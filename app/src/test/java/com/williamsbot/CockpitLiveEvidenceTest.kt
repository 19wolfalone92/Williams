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

    @Test fun recoveredStopOnlyDashboardMatchesConfirmedSupervisionButNeverClaimsOwnership() {
        val proven=JSONObject().put("stop_proof","EXTERNAL_PROTECTED")
            .put("automatic_trailing_authorized",true)
            .put("strategy_ownership_verified",false)
            .put("management_mode","AUTO_VERIFIED_STOP_ONLY")
        assertTrue(williamsObservedStopCanTrail(proven))
        assertTrue(williamsObservedStopCanTrail(JSONObject(proven.toString())
            .put("management_mode","OPERATOR_APPROVED_STOP_ONLY")))
        // An arbitrary external Binance STOP cannot become a trailing permission.
        assertFalse(williamsObservedStopCanTrail(JSONObject(proven.toString())
            .put("automatic_trailing_authorized",false)))
        assertFalse(williamsObservedStopCanTrail(JSONObject(proven.toString())
            .put("management_mode","OBSERVE_ONLY")))
        assertFalse(williamsObservedStopCanTrail(JSONObject(proven.toString())
            .put("strategy_ownership_verified",true)))
        assertFalse(williamsObservedStopCanTrail(JSONObject(proven.toString())
            .put("stop_proof","STOP_UNCONFIRMED")))
        assertFalse(williamsObservedStopCanTrail(null))
    }

    @Test fun diagnostics3PendingStopEntryIsNotYetPositionOrProtectiveStop() {
        for ((symbol,entry,stop,qty) in listOf(
            listOf("SOLUSDT","100.39","101.83","1.11"),
            listOf("BTCUSDT","80123.0","80676.1","0.0024")
        )) {
            val pending=JSONObject().put("symbol",symbol).put("direction","SHORT")
                .put("campaign_status","ENTRY_PENDING").put("order_status","NEW")
                .put("quantity",qty).put("entry_price",entry)
                .put("persisted_protective_stop",stop)
                .put("protection_status","NOT_SUBMITTED").put("executed_qty","0")
            assertFalse(cockpitEntryMayHaveFilled(pending))
            assertFalse(cockpitSignedStopVerified(pending,status(
                positions=emptyList(),reportedCount=0),now))
            assertTrue(cockpitEntryMayHaveFilled(pending.put("executed_qty","0.0001")))
            assertTrue(cockpitEntryMayHaveFilled(pending.put("order_status","PARTIALLY_FILLED")
                .put("executed_qty","0")))
        }
    }

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
        val staleOwner=status(positions=emptyList(),reportedCount=0).copy(
            futuresIntents=listOf(intent()))
        assertEquals("—",cockpitKnownOpenPositionsLabel(staleOwner,now))
        assertTrue(cockpitKnownOpenPositionsDetail(staleOwner,now).contains("журнале открытых кампаний: 1"))
        assertTrue(cockpitKnownOpenPositionsDetail(status(positionsAt=now-30_000L),now)
            .contains("ранее учтено позиций: 1"))
        assertTrue(cockpitKnownOpenPositionsDetail(status(positions=emptyList(),reportedCount=0,
            positionsAt=now-30_000L),now).contains("НЕ подтверждено"))
    }

    @Test fun wm3JournalExplanationIsRussianNotRawEnglish() {
        val explanation=cockpitBookSignalRu("BOOK H1 WM3 eligible confirmed fractal outside Teeth; waiting")
        assertTrue(explanation.contains("третий Мудрец"))
        assertFalse(explanation.contains("BOOK H1"))
        assertTrue(explanation.contains("M15"))
    }

    @Test fun diagnostics17DisagreedCredentialScreenMustNotClaimMissingKeys() {
        val stale=Status(binanceConfigured=false,running=true,
            balance=4911.02,accountSnapshotAtMs=now-20_000L,snapshotAtMs=now)
        val label=cockpitCredentialTruth(stale)
        assertTrue(label.contains("противоречивы"))
        assertFalse(label.contains("ещё не заданы"))
        assertTrue(label.contains("Не удаляй"))
        assertTrue(cockpitCredentialTruth(Status()).contains("ещё не получен"))
        assertTrue(cockpitCredentialTruth(stale.copy(binanceConfigured=true))
            .contains("защищённом хранилище"))
    }

    @Test fun diagnostics17FourPreviouslyProtectedPositionsRemainUnverifiedWhenCacheIsStale() {
        val old=status(snapshotOk=false,positionsAt=now-70_000L).copy(
            positions=listOf(position().copy(stopConfirmedAtMs=now-80_000L)))
        assertFalse(cockpitSignedStopVerified(intent(),old,now))
        val note=cockpitPreviousStopEvidence(intent(),old,now)
        assertNotNull(note)
        assertTrue(note!!.contains("СВЕДЕНИЯ УСТАРЕЛИ"))
        assertTrue(note.contains("104.02"))
        assertNull(cockpitPreviousStopEvidence(intent(),status(
            positions=listOf(position().copy(stopConfirmedAtMs=now-1_000L))),now))
        assertNull(cockpitPreviousStopEvidence(intent(),status(snapshotOk=false,
            positions=listOf(position(null,"UNVERIFIED_OR_STALE"))),now))
    }
}
