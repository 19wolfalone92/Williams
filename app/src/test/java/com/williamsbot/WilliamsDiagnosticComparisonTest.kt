package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsDiagnosticComparisonTest {
    private fun issue(id:String,domain:String="runtime",severity:String="FAIL")=
        JSONObject().put("id",id).put("domain",domain)
            .put("name","check $id").put("severity",severity)

    @Test fun distinguishNewWorsenedAndNoLongerObservedWithoutClaimingRecovery() {
        val earlier=JSONArray()
            .put(issue("A","execution","WARNING"))
            .put(issue("B","runtime","FAIL"))
            .put(issue("C","strategy","FAIL"))
            .put(issue("D","database","WARNING"))
        val latest=JSONArray()
            .put(issue("A","execution","FAIL"))
            .put(issue("B","runtime","WARNING"))
            .put(issue("C","strategy","FAIL"))
            .put(issue("E","binance","NOT_VERIFIED"))
        val comparison=williamsDiagnosticComparison(earlier,latest,100_000L,120_000L)
        assertEquals("COMPARABLE",comparison.getString("status"))
        assertEquals(1,comparison.getInt("new_count"))
        assertEquals(1,comparison.getInt("worsened_count"))
        assertEquals(1,comparison.getInt("less_severe_count"))
        assertEquals(1,comparison.getInt("unchanged_issue_count"))
        assertEquals(1,comparison.getInt("no_longer_reported_count"))
        assertEquals("D",comparison.getJSONArray("no_longer_reported")
            .getJSONObject(0).getString("id"))
        assertTrue(comparison.getString("note").contains("НЕ доказывает"))
    }

    @Test fun unknownHistoryOrClockRollbackNeverPretendsToRepairChecks() {
        for ((previous,oldTime) in listOf(
            null to 100L,
            JSONArray() to 0L,
            JSONArray() to 200L
        )) {
            val result=williamsDiagnosticComparison(previous,JSONArray(),oldTime,150L)
            assertEquals("NOT_COMPARABLE",result.getString("status"))
            assertEquals(0,result.getJSONArray("no_longer_reported").length())
            assertEquals(0,result.getJSONArray("new_issues").length())
        }
    }

    @Test fun identicalIssueIdInTwoDomainsMustNotMerge() {
        val previous=JSONArray()
            .put(issue("REBOOT","runtime","FAIL"))
            .put(issue("REBOOT","execution","WARNING"))
        val current=JSONArray().put(issue("REBOOT","execution","FAIL"))
        val comparison=williamsDiagnosticComparison(previous,current,10L,100L)
        assertEquals(1,comparison.getInt("worsened_count"))
        assertEquals(1,comparison.getInt("no_longer_reported_count"))
        assertEquals("runtime",comparison.getJSONArray("no_longer_reported")
            .getJSONObject(0).getString("domain"))
    }

    @Test fun severeDuplicateWinsAndWarnAliasNormalizes() {
        val previous=JSONArray().put(issue("A","execution","WARN"))
            .put(issue("A","execution","FAIL"))
        val current=JSONArray().put(issue("A","execution","WARNING"))
        val comparison=williamsDiagnosticComparison(previous,current,1L,2L)
        assertEquals(1,comparison.getInt("less_severe_count"))
    }
}
