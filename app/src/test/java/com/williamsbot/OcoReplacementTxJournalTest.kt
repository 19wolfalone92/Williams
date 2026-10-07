package com.williamsbot

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OcoReplacementTxJournalTest {
    @Test
    fun transaction_isDurableBeforeMutation_andRemainsPendingUntilCompleted() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val journal = OcoReplacementTxJournal(context)

        val txId = journal.begin(
            symbol = "BTCUSDT",
            oldOcoId = "123",
            oldOcoClientId = "W4O_old",
            quantity = 0.01
        )

        val pending = journal.pending()
        assertTrue(pending != null)
        assertEquals(txId, pending?.txId)
        assertEquals("INITIATED", pending?.step)

        journal.updateStep(txId, "OLD_CANCEL_CONFIRMED")
        assertEquals("OLD_CANCEL_CONFIRMED", journal.pending()?.step)

        journal.markCompleted(txId)
        assertTrue(!journal.hasPending())
    }
}
