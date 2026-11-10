package com.williamsbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CampaignCockpitEventPolicyTest {
    @Test fun coldRestartDoesNotEmitHundredsOfAlreadyTerminalExecutionEvents() {
        // Entire historical intent journal is still durable and signed
        // reconciliation still checks orphan STOP identities separately.
        assertFalse(campaignCockpitEventRequired(
            previouslyObserved=false,historicalTerminal=true,
            severity="PASS",hasDurableIdentity=true))
        assertFalse(campaignCockpitIncidentRequired(
            historicalTerminal=true,severity="PASS",
            protectionStatus="SUBMISSION_UNKNOWN"))
        assertFalse(campaignCockpitIncidentRequired(
            historicalTerminal=true,severity="PASS",
            protectionStatus="UNRESOLVED_EXTRA_ORDER"))
    }

    @Test fun newLifecycleTransitionsAndActiveUnverifiedStopsRemainVisible() {
        assertTrue(campaignCockpitEventRequired(
            previouslyObserved=true,historicalTerminal=true,
            severity="PASS",hasDurableIdentity=true))
        assertTrue(campaignCockpitEventRequired(
            previouslyObserved=false,historicalTerminal=false,
            severity="NOT_VERIFIED",hasDurableIdentity=true))
        assertTrue(campaignCockpitEventRequired(
            previouslyObserved=false,historicalTerminal=false,
            severity="WARNING",hasDurableIdentity=true))
        assertTrue(campaignCockpitIncidentRequired(
            historicalTerminal=false,severity="WARNING",
            protectionStatus="SUBMISSION_UNKNOWN"))
        assertTrue(campaignCockpitIncidentRequired(
            historicalTerminal=false,severity="NOT_VERIFIED",
            protectionStatus="UNRESOLVED_EXTRA_ORDER"))
    }

    @Test fun malformedOrFailingHistoricalEntriesAreNeverSilenced() {
        assertTrue(campaignCockpitEventRequired(
            previouslyObserved=false,historicalTerminal=true,
            severity="FAIL",hasDurableIdentity=true))
        assertTrue(campaignCockpitEventRequired(
            previouslyObserved=false,historicalTerminal=true,
            severity="PASS",hasDurableIdentity=false))
        assertTrue(campaignCockpitIncidentRequired(
            historicalTerminal=true,severity="FAIL",
            protectionStatus="NOT_SUBMITTED"))
    }
    @Test fun confirmedHistoricalExitIsQuietButPendingOrPartiallyFilledExitIsNot() {
        val historical=org.json.JSONObject().put("intent_role","EXIT")
            .put("exchange_status","EXIT_CONFIRMED")
            .put("order_status","FILLED")
        assertTrue(campaignIsHistoricalTerminalForCockpit(historical))
        assertFalse(campaignCockpitEventRequired(
            previouslyObserved=false,
            historicalTerminal=campaignIsHistoricalTerminalForCockpit(historical),
            severity="PASS",hasDurableIdentity=true))
        historical.put("exchange_status","EXIT_TERMINAL_POSITION_OPEN")
        assertFalse(campaignIsHistoricalTerminalForCockpit(historical))
        historical.put("exchange_status","SUBMISSION_UNKNOWN")
        assertFalse(campaignIsHistoricalTerminalForCockpit(historical))
        historical.put("exchange_status","FILLED")
        assertFalse(campaignIsHistoricalTerminalForCockpit(historical))
    }

}
