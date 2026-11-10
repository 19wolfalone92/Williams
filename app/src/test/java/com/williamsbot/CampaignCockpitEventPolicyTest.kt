package com.williamsbot

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class CampaignCockpitEventPolicyTest {
    @Test fun coldRestartDoesNotEmitHundredsOfAlreadyTerminalExecutionEvents() {
        // Entire historical intent journal is still durable and signed
        // reconciliation still checks orphan STOP identities separately.
        assertFalse(campaignCockpitEventRequired(
            previouslyObserved=false,lifecycle="CLOSED_OR_STOPPED",
            severity="PASS",hasDurableIdentity=true))
        assertFalse(campaignCockpitIncidentRequired(
            lifecycle="CLOSED_OR_STOPPED",severity="PASS",
            protectionStatus="SUBMISSION_UNKNOWN"))
        assertFalse(campaignCockpitIncidentRequired(
            lifecycle="CLOSED_OR_STOPPED",severity="PASS",
            protectionStatus="UNRESOLVED_EXTRA_ORDER"))
    }

    @Test fun newLifecycleTransitionsAndActiveUnverifiedStopsRemainVisible() {
        assertTrue(campaignCockpitEventRequired(
            previouslyObserved=true,lifecycle="CLOSED_OR_STOPPED",
            severity="PASS",hasDurableIdentity=true))
        assertTrue(campaignCockpitEventRequired(
            previouslyObserved=false,lifecycle="ENTRY_PENDING",
            severity="NOT_VERIFIED",hasDurableIdentity=true))
        assertTrue(campaignCockpitEventRequired(
            previouslyObserved=false,lifecycle="OPEN_PROTECTED",
            severity="WARNING",hasDurableIdentity=true))
        assertTrue(campaignCockpitIncidentRequired(
            lifecycle="OPEN_PROTECTED",severity="WARNING",
            protectionStatus="SUBMISSION_UNKNOWN"))
        assertTrue(campaignCockpitIncidentRequired(
            lifecycle="ENTRY_PENDING",severity="NOT_VERIFIED",
            protectionStatus="UNRESOLVED_EXTRA_ORDER"))
    }

    @Test fun malformedOrFailingHistoricalEntriesAreNeverSilenced() {
        assertTrue(campaignCockpitEventRequired(
            previouslyObserved=false,lifecycle="CLOSED_OR_STOPPED",
            severity="FAIL",hasDurableIdentity=true))
        assertTrue(campaignCockpitEventRequired(
            previouslyObserved=false,lifecycle="CLOSED_OR_STOPPED",
            severity="PASS",hasDurableIdentity=false))
        assertTrue(campaignCockpitIncidentRequired(
            lifecycle="CLOSED_OR_STOPPED",severity="FAIL",
            protectionStatus="NOT_SUBMITTED"))
    }
}
