package com.williamsbot

import org.junit.Assert.assertEquals
import org.junit.Test

class DurableMarketHistoryPolicyTest {
    @Test fun full_history_flag_survives_new_closed_candles_and_restart_tail_ingestion() {
        // Existing upsertBatch(..., complete=false) calls are ordinary live updates.
        assertEquals(true, historicBackfillComplete(true, false))
        assertEquals(true, historicBackfillComplete(true, null))
        assertEquals(true, historicBackfillComplete(true, true))
    }

    @Test fun partial_history_never_claims_exchange_beginning_without_evidence() {
        assertEquals(false, historicBackfillComplete(false, false))
        assertEquals(false, historicBackfillComplete(false, null))
        assertEquals(true, historicBackfillComplete(false, true))
    }
}
