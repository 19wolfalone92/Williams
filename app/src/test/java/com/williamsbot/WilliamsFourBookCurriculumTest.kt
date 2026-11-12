package com.williamsbot

import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Test

class WilliamsFourBookCurriculumTest {
    private val now=1_800_000_000_000L

    @Test fun allFourOriginalsHaveEveryPhysicalPageInChapterMapButNoFakeCertification() {
        val report=WilliamsFourBookCurriculum.assess(JSONObject(),now)
        assertEquals("WILLIAMS_FOUR_BOOKS_FULL_CHAPTER_COVERAGE_V1",
            report.getString("schema"))
        assertEquals(642,report.getInt("source_pdf_total_pages"))
        assertEquals(642,report.getInt("source_pages_physically_indexed"))
        assertEquals(57,report.getInt("source_chapters_in_inventory"))
        assertEquals(0,report.getInt("chapters_with_independent_figure_exam"))
        assertFalse(report.getBoolean("book_understanding_99_percent_proven"))
        assertFalse(report.getBoolean("whole_book_understood_or_certified"))
        assertFalse(report.getBoolean("can_modify_tc2_execution"))
        assertFalse(report.getBoolean("trading_authority"))
        val chapters=report.getJSONArray("chapters")
        assertEquals(57,chapters.length())
        val sources=report.getJSONArray("source_catalog")
        val expected=mapOf("TC1" to 140,"NTD" to 156,"TC2" to 224,"COURSE" to 122)
        assertEquals(4,sources.length())
        for(i in 0 until sources.length()) {
            val source=sources.getJSONObject(i)
            assertEquals(expected[source.getString("book")],
                source.getInt("indexed_pdf_pages"))
            assertEquals(source.getInt("indexed_sections"),source.getInt("ungraded_sections"))
            assertEquals(0,source.getInt("independent_figure_certified_sections"))
            assertFalse(source.getBoolean("new_order_authority"))
        }
        for(i in 0 until chapters.length()) {
            val chapter=chapters.getJSONObject(i)
            assertFalse(chapter.getBoolean("complete_chapter_comprehension_proven"))
            assertFalse(chapter.getBoolean("can_place_orders"))
            assertFalse(chapter.getBoolean("can_move_stop"))
            assertEquals("NOT_GRADED",chapter.getString("independent_figure_grade"))
        }
    }

    @Test fun scannerFreshnessIsNotProofOfPaperTradeStopOrHumanPsychology() {
        val scanner=JSONObject().put("last_scan_at",now-10_000L)
            .put("scanner_state","READY_BLOCKED").put("symbols_scanned",APPROVED_FUTURES_SYMBOLS.size)
            .put("scanning",false)
        val book=WilliamsFourBookCurriculum.assess(scanner,now)
        val checks=book.getJSONArray("tc2_appendix_1_preflight_research")
        assertEquals(6,checks.length())
        assertEquals("RECENT_SCANNER_OBSERVED",
            checks.getJSONObject(0).getString("state"))
        for(i in 1 until checks.length()) {
            val state=checks.getJSONObject(i).getString("state")
            assertTrue(state in setOf("NOT_VERIFIED","OUTSIDE_AUTOMATIC_ASSESSMENT","SOURCE_MISSING"))
            assertFalse(checks.getJSONObject(i).getBoolean("is_authorization_for_trade"))
        }
        scanner.put("last_scan_at",now-360_000L)
        val stale=WilliamsFourBookCurriculum.assess(scanner,now)
        assertEquals("NOT_VERIFIED",stale.getJSONArray("tc2_appendix_1_preflight_research")
            .getJSONObject(0).getString("state"))
    }

    @Test fun appendixOneChartReadsActualH1WithoutPretendingSignedStopOrHumanPsychology() {
        val symbol=APPROVED_FUTURES_SYMBOLS.first()
        val closed=now
        val candidate=JSONObject().put("symbol",symbol)
            .put("closed_h1_as_of_ms",closed)
            .put("campaign_signals",org.json.JSONArray().put(JSONObject()
                .put("strategy_profile",williamsTc2Profile)
                .put("signal_id","$symbol:1h:SUPER_AO:close")))
            .put("decision_trace",JSONObject().put("as_of_close_ms",closed)
                .put("market_reading",JSONObject().put("state","OBSERVED")
                    .put("as_of_close_ms",closed).put("observed_bias","UP")
                    .put("ao_direction","UP")
                    .put("alligator",JSONObject().put("state","EATING"))))
        val scanner=JSONObject().put("last_scan_at",now-10_000L)
            .put("scanner_state","READY_BLOCKED")
            .put("symbols_scanned",APPROVED_FUTURES_SYMBOLS.size)
            .put("scanning",false)
            .put("candidates",org.json.JSONArray().put(candidate))
        val summary=WilliamsFourBookCurriculum.assess(scanner,now)
            .getJSONObject("tc2_appendix_1_closed_h1_chart_checklist")
        assertEquals(1,summary.getInt("source_chart_observed_count"))
        assertFalse(summary.getBoolean("complete_market_account_and_stop_preflight_proven"))
        assertFalse(summary.getBoolean("trading_authority"))
        val first=summary.getJSONArray("symbols").getJSONObject(0)
        assertEquals("CLOSED_H1_SNAPSHOT_OBSERVED",first.getString("chart_observation_state"))
        assertEquals("UP",first.getString("observed_trend"))
        assertEquals("EATING",first.getString("observed_alligator"))
        assertEquals("NOT_VERIFIED_IN_THIS_CHART_OBSERVER",
            first.getString("exchange_confirmed_protective_stop"))
        assertFalse(first.getBoolean("psychology_independently_assessed"))
        assertFalse(first.getBoolean("can_approve_order"))
        assertFalse(first.getBoolean("can_veto_tc2_order"))
        // An old scan never becomes a new trusted chart just because the
        // data is present in local preferences.
        scanner.put("last_scan_at",now-250_000L)
        val stale=WilliamsFourBookCurriculum.assess(scanner,now)
            .getJSONObject("tc2_appendix_1_closed_h1_chart_checklist")
        assertEquals(0,stale.getInt("source_chart_observed_count"))
    }

    @Test fun scannedCourseExternalTutorialNotFabricatedFromBookMetadata() {
        val report=WilliamsFourBookCurriculum.assess(JSONObject(),now)
        assertFalse(report.getBoolean("source_original_course_fully_ocr_certified"))
        assertFalse(report.getBoolean("source_original_course_external_tutorial_complete"))
        val chapters=report.getJSONArray("chapters")
        val missing=(0 until chapters.length()).map { chapters.getJSONObject(it) }
            .single { it.getString("section_id")=="COURSE.LEVEL5B" }
        assertEquals(122,missing.getInt("pdf_start_page"))
        assertEquals("EXTERNAL_SOURCE_MISSING",missing.getString("kind"))
        assertFalse(missing.getBoolean("complete_chapter_comprehension_proven"))
    }
}
