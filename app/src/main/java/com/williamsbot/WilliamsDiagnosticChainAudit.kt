package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/**
 * Read-only, bounded, end-to-end diagnostic coverage across native Android,
 * Binance Futures, TC2 decision, execution, protection, recovery and study.
 *
 * A PASS means that the named COMPONENT observation passed its existing test;
 * it never implies that an upstream-to-downstream order chain was exercised.
 * All edges explicitly require separate signed Demo acceptance evidence.
 *
 * No API calls, order mutations, credentials, arbitrary exception strings,
 * huge decision traces, or SQLite writes are performed here.
 */
internal object WilliamsDiagnosticChainAudit {
    private data class Core(
        val id: String, val title: String, val tests: List<String>,
        val parents: List<String> = emptyList(),
        val proof: String
    )

    private val cores = listOf(
        Core("ANDROID_PROCESS","Android · процесс, фон, загрузка",
            listOf("version_consistency","runtime_lifecycle","reboot_credentials_and_engine","AND-01","AND-02","AND-04"),
            proof="Android process exits, boot witness, watchdog heartbeat and real 24h phone evidence"),
        Core("HEAP_PRESSURE","JVM · память и риск OutOfMemoryError",
            listOf("RES-HEAP-01"),listOf("ANDROID_PROCESS"),
            "heap headroom, native memory, GC pressure and observed crash fingerprints"),
        Core("FOREGROUND_WORKER","Android · сервис и worker",
            listOf("local_runtime_dispatch","self_heal_contract","user_stream_reconciliation_lifecycle","state_machine"),
            listOf("ANDROID_PROCESS"),"foreground lease, generation, worker heartbeat and safe recovery"),
        Core("SQLITE_JOURNAL","SQLite · состояние и журнал",
            listOf("DB-01","history_lifecycle","HIST-01"),
            listOf("ANDROID_PROCESS"),"quick_check, durability, no recreated intent after restart"),
        Core("EXCHANGE_CLOCK","Binance · время и TTL",
            listOf("signed_futures_reconciliation_freshness"),
            listOf("FOREGROUND_WORKER"),"signed clock sample, UTC trading day and 90-second signed data TTL"),
        Core("PUBLIC_REST","Binance · котировки REST",
            listOf("rest_market_data","DATA-01"),
            listOf("EXCHANGE_CLOCK"),"all approved contract quotes and last-good timestamp"),
        Core("PUBLIC_STREAMS","Binance · market/depth WebSocket",
            listOf("market_websocket","public_websocket"),
            listOf("PUBLIC_REST"),"independent last-message ages and reconnects"),
        Core("SIGNED_ACCOUNT","Binance · private REST/account",
            listOf("credentials_state","rest_account","campaign_reconciliation_lifecycle"),
            listOf("EXCHANGE_CLOCK","SQLITE_JOURNAL"),"authenticated account generation and complete signed reconciliation"),
        Core("NETWORK_GOVERNOR","Binance · REST weight / rate budget",
            listOf("RES-RATE-01"),listOf("PUBLIC_REST","SIGNED_ACCOUNT"),
            "request weight, 429/418 events and rate governor bounded retry/cooldown"),
        Core("PRIVATE_STREAM","Binance · user WebSocket",
            listOf("user_websocket","user_stream_freshness",
                "user_stream_last_completed_signed_rest_duration",
                "user_stream_event_observation","DATA-04"),
            listOf("SIGNED_ACCOUNT"),"same listenKey session and signed event-driven reconciliation"),
        Core("H1_HISTORY","История · закрытые H1/H4",
            listOf("DATA-02","DATA-03","history_ready"),
            listOf("SQLITE_JOURNAL","PUBLIC_REST"),"contiguous independently closed exchange candles and provenance"),
        Core("WAVE_RESEARCH","Волны · D1/H4/H1/M15",
            listOf("DATA-06","WAVE-04","WAVE-ARCHIVE-01"),
            listOf("H1_HISTORY"),"causal closed-bar replay, known analytic degree and no look-ahead"),
        Core("SCANNER","Scanner · полный цикл 15 пар",
            listOf("scanner_state","STR-08"),
            listOf("H1_HISTORY","PUBLIC_STREAMS"),"progress, scan age and candidate rejection reasons"),
        Core("EXECUTOR_BACKLOG","Потоки · Scanner/Research/History очереди",
            listOf("RES-QUEUE-01"),listOf("FOREGROUND_WORKER","HEAP_PRESSURE"),
            "active scanner workers, queue depth, unbounded history backlog and starvation"),
        Core("TC2_RULES","Стратегия · TC2 и источники",
            listOf("STR-01","STR-02","STR-03","STR-04","STR-05","STR-06",
                "STR-07","STR-10","STR-13","STR-15","STR-12"),
            listOf("SCANNER","WAVE_RESEARCH"),"one book-cited closed-H1 signal, independent expert labels"),
        Core("RISK_GATE","Риск · сутки и лимиты",
            listOf("risk_limits","daily_risk_baseline","RISK-01","RISK-02",
                "RISK-03","RISK-04","EXE-09"),
            listOf("SIGNED_ACCOUNT","SQLITE_JOURNAL"),"daily equity, exposure budget, liquidation and STOP"),
        Core("EXECUTION_DOOR","Execution Door · fail-closed",
            listOf("execution_gate","execution_config_audit","futures_execution_adapter",
                "signed_futures_reconciliation_freshness","SAF-LEGACY-01"),
            listOf("PRIVATE_STREAM","RISK_GATE","TC2_RULES"),
            "durable lease, signed pre-intent proof and atomic no-duplicate submission"),
        Core("PENDING_ORDERS","Binance · conditional ENTRY",
            listOf("EXE-01","EXE-02","EXE-11"),
            listOf("EXECUTION_DOOR"),"signed open/algo order, unique client id, partial-fill lifecycle"),
        Core("POSITIONS","Binance · ownership/positions",
            listOf("DATA-05","EXE-12","EXE-03"),
            listOf("PENDING_ORDERS","SIGNED_ACCOUNT"),
            "signed actual fill, side/quantity and durable strategy ownership"),
        Core("STOP_PROTECTION","Binance · активный защитный STOP",
            listOf("EXE-13","EXE-04","EXE-08","EXE-FILL-STOP-TIMING"),
            listOf("POSITIONS","SIGNED_ACCOUNT"),
            "live reduce-only STOP matches actual exchange position and signed algo id"),
        Core("STOP_TRAILING","TC2 · контроль переноса STOP",
            listOf("EXE-05","EXE-SL-AUDIT"),
            listOf("STOP_PROTECTION","H1_HISTORY"),
            "closed-bar movement, audit trail and confirmed old/new Binance STOP"),
        Core("EXITS_PNL","Закрытие · исполнение и PnL",
            listOf("EXE-06","HIST-01","HIST-02"),
            listOf("POSITIONS","STOP_PROTECTION","SQLITE_JOURNAL"),
            "closed exchange fill, commission/funding and no phantom positions"),
        Core("RECONNECT_RECOVERY","Восстановление · WS/reboot",
            listOf("EXE-07","AND-02","self_heal_contract"),
            listOf("FOREGROUND_WORKER","SIGNED_ACCOUNT","STOP_PROTECTION"),
            "reboot/offline recovery preserves STOP and never replays uncertain ENTRY"),
        Core("UI_CONSISTENCY","Интерфейс · подтверждение фактов",
            listOf("AND-03","EXE-10","diagnostic_consistency"),
            listOf("SIGNED_ACCOUNT","STOP_PROTECTION"),
            "UI, journal and signed position agree without claiming entry permission"),
        Core("FORENSIC_EXPORT","Диагностика · причины и экспорт",
            listOf("diagnostic_consistency","DB-01","EXE-SL-AUDIT"),
            listOf("SQLITE_JOURNAL","UI_CONSISTENCY"),
            "bounded memory, versioned chain map, full streamed ZIP and comparison")
    )

    /**
     * Compare ONLY previous saved, fully closed ZIP graph against this new
     * capture. Statuses are observations, not proof that an issue was fixed.
     */
    fun compare(
        previous: JSONObject?, current: JSONObject,
        previousAtMs: Long, currentAtMs: Long
    ): JSONObject {
        val out=JSONObject().put("schema","WILLIAMS_CHAIN_COMPARISON_V1")
            .put("previous_at_ms",previousAtMs)
            .put("current_at_ms",currentAtMs)
            .put("can_clear_trade_gate",false)
        val before=previous?.optJSONArray("components")
        val after=current.optJSONArray("components")
        if(before==null || after==null || before.length()==0 || after.length()==0 ||
            previousAtMs<=0L || currentAtMs<=previousAtMs)
            return out.put("status","NOT_COMPARABLE")
                .put("reason","No strictly older complete chain snapshot")
        val old=linkedMapOf<String,String>()
        for(i in 0 until before.length()) {
            val row=before.optJSONObject(i) ?: continue
            val id=row.optString("id")
            if(id.isNotBlank()) old[id]=severity(row.optString("status"))
        }
        val next=linkedMapOf<String,String>()
        for(i in 0 until after.length()) {
            val row=after.optJSONObject(i) ?: continue
            val id=row.optString("id")
            if(id.isNotBlank()) next[id]=severity(row.optString("status"))
        }
        val worsened=JSONArray()
        val lessSevere=JSONArray()
        val added=JSONArray()
        val disappeared=JSONArray()
        for((id,actual) in next) {
            val prior=old[id]
            when {
                prior==null -> added.put(id)
                rank(actual)<rank(prior) -> worsened.put(JSONObject()
                    .put("id",id).put("before",prior).put("after",actual))
                rank(actual)>rank(prior) -> lessSevere.put(JSONObject()
                    .put("id",id).put("before",prior).put("after",actual))
            }
        }
        for(id in old.keys) if(id !in next) disappeared.put(id)
        return out.put("status","COMPARABLE")
            .put("worsened_count",worsened.length())
            .put("less_severe_count",lessSevere.length())
            .put("added_core_count",added.length())
            .put("no_longer_observed_count",disappeared.length())
            .put("worsened",worsened).put("less_severe",lessSevere)
            .put("added",added).put("no_longer_observed",disappeared)
            .put("caveat","A less severe component is not a verified repaired bug or successful signed Demo trade.")
    }

    private fun severity(value: String): String = when(value.uppercase()) {
        "FAIL" -> "FAIL"
        "WARNING","WARN" -> "WARNING"
        "PASS" -> "PASS"
        else -> "NOT_VERIFIED"
    }

    private fun rank(value: String): Int = when(value) {
        "FAIL" -> 0
        "WARNING" -> 1
        "NOT_VERIFIED" -> 2
        else -> 3
    }

    private fun age(timestamp: Long, now: Long): Any =
        if(timestamp <= 0L || now < timestamp) JSONObject.NULL
        else (now - timestamp)

    fun build(
        status: JSONObject, coordinator: JSONObject, scanner: JSONObject,
        checks: JSONArray, observedAtMs: Long,
        events: JSONArray = JSONArray(), nonAtomic: Boolean = false
    ): JSONObject {
        // Prefer fixed IDs; old diagnostics predate IDs and use name instead.
        val byId=linkedMapOf<String,JSONObject>()
        val collisions=linkedSetOf<String>()
        for(i in 0 until checks.length()) {
            val row=checks.optJSONObject(i) ?: continue
            val id=row.optString("id").ifBlank { row.optString("name") }
            if(id.isBlank()) continue
            if(byId.containsKey(id)) collisions.add(id) else byId[id]=row
        }
        val mapped=linkedSetOf<String>()
        val observedStatus=linkedMapOf<String,String>()
        val components=JSONArray()
        var failed=0;var warning=0;var unverified=0;var passed=0

        for(core in cores) {
            val missing=core.tests.filter { !byId.containsKey(it) }
            val present=core.tests.filter { byId.containsKey(it) }
            mapped.addAll(present)
            val assessments=JSONArray()
            var worst=if(missing.isEmpty()) "PASS" else "NOT_VERIFIED"
            for(id in present) {
                val row=byId.getValue(id)
                val actual=severity(row.optString("severity",
                    if(row.optBoolean("ok",false)) "PASS" else "NOT_VERIFIED"))
                if(rank(actual)<rank(worst)) worst=actual
                assessments.put(JSONObject().put("id",id).put("status",actual))
            }
            observedStatus[core.id]=worst
            when(worst) {
                "FAIL" -> failed++
                "WARNING" -> warning++
                "NOT_VERIFIED" -> unverified++
                else -> passed++
            }
            // Small scalar-only telemetry. No raw REST messages, identities,
            // full signal history, API keys or exception payloads.
            val telemetry=JSONObject()
            when(core.id) {
                "ANDROID_PROCESS","FOREGROUND_WORKER" -> {
                    val a=status.optJSONObject("android_runtime")
                    telemetry.put("foreground_service_active",a?.optBoolean("foreground_service_active"))
                        .put("worker_heartbeat_stale",a?.optBoolean("runtime_worker_heartbeat_stale"))
                        .put("worker_heartbeat_age_ms",a?.opt("runtime_worker_heartbeat_age_ms") ?: JSONObject.NULL)
                        .put("fatal_exception_type",a?.optJSONObject("fatal_java_crash")?.optString("exception_type","") ?: "")
                }
                "EXCHANGE_CLOCK" -> {
                    val c=status.optJSONObject("exchange_clock")
                    telemetry.put("fresh",c?.optBoolean("fresh"))
                        .put("signed_snapshot_age_ms",age(status.optLong("user_stream_signed_account_snapshot_at_ms"),
                            observedAtMs))
                }
                "PUBLIC_REST" -> telemetry.put("last_success_age_ms",
                    age(status.optLong("rest_last_success_ms"),observedAtMs))
                    .put("rest_market_ready",status.optBoolean("rest_market_ready"))
                "HEAP_PRESSURE" -> {
                    val mem=status.optJSONObject("android_runtime")?.optJSONObject("memory_health")
                    telemetry.put("java_heap_used_bytes",mem?.opt("java_heap_used_bytes") ?: JSONObject.NULL)
                        .put("java_heap_limit_bytes",mem?.opt("java_heap_limit_bytes") ?: JSONObject.NULL)
                }
                "NETWORK_GOVERNOR" -> {
                    val rate=status.optJSONObject("rate_limits")
                    telemetry.put("used_weight_1m",rate?.opt("used_weight_1m") ?: JSONObject.NULL)
                        .put("weight_limit_1m",rate?.opt("weight_limit_1m") ?: JSONObject.NULL)
                        .put("cooldown_ms",rate?.opt("cooldown_ms") ?: JSONObject.NULL)
                }
                "PUBLIC_STREAMS" -> telemetry.put("market_ws_connected",status.optBoolean("market_ws_connected"))
                    .put("public_ws_connected",status.optBoolean("public_ws_connected"))
                "SIGNED_ACCOUNT" -> telemetry.put("rest_account_ready",status.optBoolean("rest_account_ready"))
                    .put("reconciliation_running",coordinator.optBoolean("reconciliation_running"))
                    .put("signed_snapshot_age_ms",age(status.optLong("user_stream_signed_account_snapshot_at_ms"),
                        observedAtMs))
                "PRIVATE_STREAM" -> telemetry.put("user_ws_connected",status.optBoolean("user_ws_connected"))
                    .put("sync_required",status.optBoolean("user_stream_sync_required",true))
                    .put("last_event_age_ms",age(status.optLong("user_stream_last_event_ms"),observedAtMs))
                "H1_HISTORY" -> telemetry.put("history_ready",status.optBoolean("history_ready"))
                    .put("history_state",status.optString("history_state"))
                "SCANNER" -> telemetry.put("scanner_state",scanner.optString("scanner_state"))
                    .put("scanning",scanner.optBoolean("scanning"))
                    .put("processed",scanner.optInt("progress_symbols"))
                    .put("total",scanner.optInt("progress_total"))
                    .put("scan_age_ms",age(scanner.optLong("last_scan_at"),observedAtMs))
                "RISK_GATE" -> telemetry.put("reconcile_required",status.optBoolean("reconcile_required"))
                    .put("daily_baseline_source",status.optString("daily_risk_baseline_source"))
                    .put("kill_latched",status.optBoolean("kill_switch_latched"))
                "EXECUTION_DOOR" -> telemetry.put("execution_enabled_at_capture",
                    status.optBoolean("execution_enabled"))
                    .put("coordinator_ready_at_capture",coordinator.optBoolean("ready_for_new_entry"))
                    .put("non_atomic_capture",nonAtomic)
                "PENDING_ORDERS" -> telemetry.put("coordinator_state",coordinator.optString("state"))
                    .put("pending_intent_count",coordinator.optInt("pending_intents"))
                "POSITIONS","STOP_PROTECTION" -> telemetry.put("position_rows",
                    status.optJSONArray("futures_positions")?.length() ?: -1)
                    .put("protection_proof_rows",coordinator.optJSONArray("exchange_protection_observations")?.length() ?: -1)
                "FORENSIC_EXPORT" -> telemetry.put("complete_full_zip_required",true)
                    .put("observer_full_history_is_streamed",true)
                else -> {}
            }
            components.put(JSONObject()
                .put("id",core.id).put("name",core.title)
                .put("status",worst).put("check_count",present.size)
                .put("required_check_count",core.tests.size)
                .put("missing_check_ids",JSONArray(missing))
                .put("check_evidence",assessments)
                .put("requires",JSONArray(core.parents))
                .put("proof_needed",core.proof)
                .put("telemetry",telemetry)
                .put("component_pass_is_not_e2e_acceptance",true))
        }

        val edges=JSONArray()
        val hotspots=JSONArray()
        for(core in cores) {
            for(parent in core.parents) {
                val parentStatus=observedStatus[parent] ?: "NOT_VERIFIED"
                val targetStatus=observedStatus.getValue(core.id)
                val worst=listOf(parentStatus,targetStatus).minByOrNull(::rank) ?: "NOT_VERIFIED"
                edges.put(JSONObject().put("from",parent).put("to",core.id)
                    .put("endpoint_status",worst)
                    .put("end_to_end_exchange_verified",false)
                    .put("missing_proof",core.proof))
            }
            if(observedStatus.getValue(core.id)!="PASS") {
                hotspots.put(JSONObject().put("id",core.id)
                    .put("status",observedStatus.getValue(core.id))
                    .put("requires",JSONArray(core.parents)))
            }
        }

        val findings=JSONArray()
        fun finding(id:String,level:String,detail:String) {
            findings.put(JSONObject().put("id",id).put("severity",level).put("detail",detail))
        }
        if(collisions.isNotEmpty())
            finding("DUPLICATE_DIAGNOSTIC_TEST_IDS","WARNING",
                "More than one test reported the same ID; canonical first result kept.")
        val unmapped=byId.keys.filter { it !in mapped }
        if(unmapped.isNotEmpty())
            finding("UNMAPPED_DIAGNOSTIC_TEST_IDS","NOT_VERIFIED",
                "New/legacy checks need an explicit core mapping.")
        if(nonAtomic) finding("MIXED_TIME_COORDINATOR_SNAPSHOT","NOT_VERIFIED",
            "Coordinator moved while diagnostics were assembled. No new entry permission.")
        val signedAge=age(status.optLong("user_stream_signed_account_snapshot_at_ms"),observedAtMs)
        val liveSignedAge=(signedAge as? Long)
        if(status.optBoolean("execution_enabled") &&
            (liveSignedAge==null || liveSignedAge>90_000L)) {
            finding("EXECUTION_CLAIM_WITH_STALE_SIGNED_PROOF","FAIL",
                "Reported execution enabled without known <=90s signed account/STOP proof.")
        }
        if(coordinator.optString("state")=="RECONCILE_REQUIRED" &&
            coordinator.optBoolean("ready_for_new_entry")) {
            finding("COORDINATOR_READY_DURING_RECONCILE_REQUIRED","FAIL",
                "Coordinator contradictory readiness claims; do not infer permission to trade.")
        }
        if(nonAtomic && status.optBoolean("execution_enabled")) {
            finding("EXECUTION_CLAIM_IN_NONATOMIC_DIAGNOSTIC","WARNING",
                "Entry claim and mixed-time data must not be interpreted as an authorization.")
        }
        val livePositions=status.optJSONArray("futures_positions") ?: JSONArray()
        val knownLive=(0 until livePositions.length()).any { index ->
            val row=livePositions.optJSONObject(index) ?: return@any false
            val amount=row.optString("positionAmt").toDoubleOrNull()
            amount!=null && amount.isFinite() && amount!=0.0
        }
        if(knownLive && byId["EXE-13"]?.optString("severity")!="PASS") {
            finding("LIVE_POSITION_STOP_PROOF_NOT_VERIFIED","WARNING",
                "Signed active STOP/side/quantity/ownership proof is not PASS; never assume protected.")
        }

        val eventCounts=linkedMapOf<String,Int>()
        val lastEvents=JSONArray()
        val allowed= setOf("COCKPIT_EXECUTION","COCKPIT_RECONCILIATION","COCKPIT_ORDER_RESPONSE",
            "COCKPIT_SIGNAL_RISK","COCKPIT_USER_WS","COCKPIT_CONTROL","COCKPIT_UI")
        val compact=mutableListOf<JSONObject>()
        for(index in 0 until events.length()) {
            val row=events.optJSONObject(index) ?: continue
            val type=row.optString("e")
            if(type !in allowed) continue
            eventCounts[type]=(eventCounts[type] ?: 0)+1
            val at=row.optLong("E",0L)
            // Fixed known categories and timestamps ONLY. No raw 'detail'.
            compact.add(JSONObject().put("type",type)
                .put("at_ms",at)
                .put("age_ms",age(at,observedAtMs)))
        }
        compact.takeLast(16).forEach { lastEvents.put(it) }
        val counters=JSONObject()
        for((type,count) in eventCounts) counters.put(type,count)

        return JSONObject()
            .put("schema","WILLIAMS_ALL_CORE_CHAIN_AUDIT_V1")
            .put("observed_at_ms",observedAtMs)
            .put("read_only",true).put("can_place_orders",false)
            .put("can_clear_execution_gate",false)
            .put("e2e_demo_certified",false).put("independent_book_accuracy_certified",false)
            .put("snapshot_non_atomic",nonAtomic)
            .put("component_count",cores.size)
            .put("dependency_edge_count",edges.length())
            .put("summary",JSONObject().put("FAIL",failed).put("WARNING",warning)
                .put("NOT_VERIFIED",unverified).put("PASS",passed)
                .put("mapped_test_count",mapped.size).put("total_unique_tests",byId.size))
            .put("components",components).put("dependencies",edges)
            .put("hotspots",hotspots).put("cross_chain_findings",findings)
            .put("unmapped_check_ids",JSONArray(unmapped))
            .put("event_category_counts",counters)
            .put("last_events_metadata_only",lastEvents)
            .put("complete_evidence_sources",JSONArray(listOf(
                "test_results.json","futures_positions_signed_snapshot.json",
                "futures_exchange_stop_proofs.json","futures_intent_stop_lifecycle.json",
                "futures_exit_reconciliation.json","signal_decision_history.json",
                "stop_trail_decisions_full.json","observer_paper_full_history.ndjson")))
            .put("warning","Independent component PASS never verifies an end-to-end signed Demo STOP, " +
                "a boot cycle, or 24-hour background continuity.")
    }
}
