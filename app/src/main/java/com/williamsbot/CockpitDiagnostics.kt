package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/** Observations are read-only. This contract never authorizes an exchange mutation. */
internal object CockpitDiagnostics {
    const val VERSION = 9
    const val POSITION_TTL_MS = 15_000L
    const val QUOTE_TTL_MS = 60_000L

    fun rows(array: JSONArray?): List<JSONObject> =
        if (array == null) emptyList() else (0 until array.length()).mapNotNull { array.optJSONObject(it) }

    fun fresh(at: Long, now: Long, ttl: Long): Boolean = at > 0L && at <= now && now - at <= ttl

    fun positionsFresh(status: JSONObject, now: Long): Boolean =
        status.optBoolean("positions_snapshot_ok") &&
            fresh(status.optLong("positions_snapshot_at_ms"), now, POSITION_TTL_MS)

    fun readiness(status: JSONObject, coordinator: JSONObject, now: Long): JSONObject {
        val snapshotFresh = positionsFresh(status, now)
        val technical = status.optBoolean("running") && status.optBoolean("rest_market_ready") &&
            status.optBoolean("market_ws_connected") && status.optBoolean("user_ws_connected") &&
            !status.optBoolean("user_stream_sync_required", true) && status.optBoolean("history_ready")
        val coordinatorReady = coordinator.optBoolean("ready_for_new_entry")
        val scannerReady = status.optString("scanner_state") == "READY" &&
            fresh(status.optLong("scanner_last_scan_at"), now, 120_000L)
        val entries = technical && snapshotFresh && coordinatorReady && scannerReady &&
            status.optBoolean("execution_enabled") && !status.optBoolean("paused") &&
            !status.optBoolean("reconcile_required", true) && !status.optBoolean("kill_switch_latched")
        val positions = rows(status.optJSONArray("futures_positions"))
        val intents = rows(coordinator.optJSONArray("pending_intent_details"))
        val unprotected = positions.filter { p ->
            intents.none { i -> matchesPosition(i, p) && protectionProven(i, now) }
        }
        val monitoring = status.optBoolean("running") && snapshotFresh &&
            status.optBoolean("user_ws_connected") && !status.optBoolean("user_stream_sync_required", true)
        val reason = when {
            !status.optBoolean("running") -> "Движок остановлен"
            status.optBoolean("kill_switch_latched") -> "Аварийная блокировка новых операций"
            status.optBoolean("paused") -> "Новые входы приостановлены; сопровождение продолжается"
            !snapshotFresh -> "Снимок позиций Binance обновляется или устарел"
            !coordinatorReady -> blockerText(coordinator.optString("blocker"))
            !scannerReady -> "Сканер: " + status.optString("scanner_state", "NOT_RUN")
            status.optString("daily_risk_baseline_source", "UNKNOWN") in setOf("", "UNKNOWN", "null") ->
                "Не подтверждена суточная база риска Binance; новые входы заблокированы"
            !technical -> "Ожидание связи, истории или синхронизации"
            !entries -> "Торговый шлюз ограничивает новые входы"
            else -> "Новые входы разрешены при подтверждённом сигнале и проверке риска"
        }
        return JSONObject().put("observed_at_ms", now)
            .put("technical_ready", technical).put("new_entries_allowed", entries)
            .put("position_management_ready", monitoring && unprotected.isEmpty())
            .put("positions_snapshot_fresh", snapshotFresh)
            .put("unprotected_positions", JSONArray(unprotected))
            .put("pending_orders", intents.count { it.optString("campaign_status") in
                setOf("ENTRY_PENDING", "FILL_CONFIRMATION_PENDING", "EXIT_PENDING") })
            .put("new_entries_reason", reason)
            .put("protection_state", when {
                !snapshotFresh -> "NOT_VERIFIED"
                positions.isEmpty() -> "NO_OPEN_POSITIONS"
                unprotected.isNotEmpty() -> "FAIL"
                else -> "PASS"
            })
    }

    private fun matchesPosition(intent: JSONObject, position: JSONObject): Boolean =
        intent.optString("role", "ENTRY") == "ENTRY" &&
            intent.optString("symbol") == position.optString("symbol") &&
            intent.optString("direction") == position.optString("direction")

    fun protectionProven(intent: JSONObject, now: Long): Boolean =
        intent.optString("campaign_status") == "OPEN_PROTECTED" &&
            intent.optString("protection_status") == "PROTECTED" &&
            intent.optBoolean("strategy_ownership_verified", false) &&
            intent.optString("protection_client_id_suffix").isNotBlank() &&
            fresh(intent.optLong("last_reconciled_ms"), now, 120_000L)

    fun checks(status: JSONObject, coordinator: JSONObject, scanner: JSONObject, now: Long): JSONArray {
        val result = JSONArray()
        fun check(id: String, domain: String, name: String, severity: String, detail: String) {
            require(severity in setOf("PASS", "WARNING", "FAIL", "NOT_VERIFIED"))
            result.put(JSONObject().put("id", id).put("name", name).put("domain", domain)
                .put("severity", severity).put("ok", severity == "PASS")
                .put("observed_at_ms", now).put("detail", detail))
        }
        val ready = readiness(status, coordinator, now)
        val quotes = rows(status.optJSONArray("quote_observations"))
        val badQuotes = quotes.filter { !fresh(it.optLong("at_ms"), now, QUOTE_TTL_MS) ||
            !it.optDouble("price").let { v -> v.isFinite() && v > 0.0 } }
        check("DATA-01", "binance", "Котировки десяти пар",
            if (quotes.size != 10 || quotes.map { it.optString("symbol") }.toSet() != APPROVED_FUTURES_SYMBOLS.toSet()) "NOT_VERIFIED" else if (badQuotes.isEmpty()) "PASS" else "WARNING",
            "Свежие котировки: ${quotes.size - badQuotes.size}/10; предел 60 с; " +
                badQuotes.joinToString { it.optString("symbol") })
        val history = status.optJSONObject("history")
        val historyRows = rows(history?.optJSONArray("items"))
        fun frameCheck(id: String, frame: String, minCount: Int, ttl: Long) {
            val frameRows = historyRows.filter { it.optString("interval") == frame }
            val duration = if (frame == "1h") 3_600_000L else 14_400_000L
            val invalid = frameRows.filter { it.optInt("count") < minCount || it.optLong("newest_open_time") > now - duration ||
                !fresh(it.optLong("newest_open_time"), now, ttl) }
            check(id, "database", "История $frame", when {
                frameRows.size != 10 || frameRows.map { it.optString("symbol") }.toSet() != APPROVED_FUTURES_SYMBOLS.toSet() -> "NOT_VERIFIED"
                invalid.isNotEmpty() -> "WARNING"
                else -> "PASS"
            }, "Закрытый рабочий набор $frame: ${frameRows.size - invalid.size}/10; " +
                "минимум $minCount свечей. Полная архивная история проверяется отдельно.")
        }
        frameCheck("DATA-02", "1h", 150, 7_230_000L)
        frameCheck("DATA-03", "4h", 45, 28_830_000L)
        check("DATA-04", "websocket", "Сверка REST и WebSocket", when {
            !status.optBoolean("user_ws_connected") -> "WARNING"
            status.optBoolean("user_stream_sync_required", true) -> "WARNING"
            !positionsFresh(status, now) -> "WARNING"
            else -> "PASS"
        }, "Сверка позиций=${positionsFresh(status, now)}; sync_required=" +
            status.optBoolean("user_stream_sync_required", true) +
            ". Проверяется синхронизация счёта; ценовой базис REST/WS требует отдельного измерения.")
        val strategyNames = listOf("Alligator", "Фракталы: причинность", "AO и сигналы", "AC и подтверждения", "Три Мудреца", "Контекст LONG/SHORT")
        val observations = rows(status.optJSONArray("strategy_audit"))
        strategyNames.take(4).forEachIndexed { index, name ->
            val id = "STR-0${index + 1}"
            val evidence = observations.mapNotNull { it.optJSONObject("checks")?.optJSONObject(id) }
            val verified = evidence.count { it.optString("severity") == "PASS" }
            val severity = when {
                evidence.any { it.optString("severity") == "FAIL" } -> "FAIL"
                observations.size == 10 && observations.map { it.optString("symbol") }.toSet() == APPROVED_FUTURES_SYMBOLS.toSet() && verified == 10 -> "PASS"
                else -> "NOT_VERIFIED"
            }
            check(id, "strategy", name, severity,
                "Независимый контроль значений / причинности по закрытым H1: $verified/10. " +
                    evidence.firstOrNull { it.optString("severity") != "PASS" }?.optString("detail").orEmpty() +
                    " Подробные наблюдения: status.strategy_audit; это не доказательство всех правил стратегии.")
        }
        val candidates = rows(scanner.optJSONArray("candidates"))
        val signals = candidates.flatMap { rows(it.optJSONArray("campaign_signals")) }
        val invalidSignals = signals.filter { signal ->
            val side = campaignSignalDirection(signal.optString("type"))
            val trigger = signal.optDouble("trigger_price")
            val stop = signal.optDouble("protective_price")
            val at = signal.optLong("signal_bar_time_ms")
            side == null || at <= 0 || at > now - 3_600_000L || !trigger.isFinite() || !stop.isFinite() ||
                trigger <= 0 || stop <= 0 || if (side == "LONG") stop >= trigger else stop <= trigger
        }
        check("STR-05", "strategy", strategyNames[4], when {
            signals.isEmpty() -> "NOT_VERIFIED"
            invalidSignals.isNotEmpty() -> "FAIL"
            else -> "PASS"
        }, "Фактических сигналов=${signals.size}; нарушений времени закрытия H1, направления и уровней=${invalidSignals.size}. Полная каноническая верификация паттернов требует replay.")
        val traces = candidates.mapNotNull { it.optJSONObject("directional_context_native") }
        val badTraces = traces.filter { trace ->
            listOf("long_rank_tiebreak", "short_rank_tiebreak").any { !trace.optDouble(it).let { rank -> rank.isFinite() && rank in -8.0..8.0 } } ||
                trace.optBoolean("can_place_orders", true)
        }
        check("STR-06", "strategy", strategyNames[5], when {
            badTraces.isNotEmpty() -> "FAIL"
            candidates.size != 10 || traces.size != 10 -> "NOT_VERIFIED"
            else -> "PASS"
        }, "Обе оценки LONG/SHORT присутствуют в ${traces.size}/10 результатах; эвристика не может самостоятельно разрешать ордера. Исполнение сторон проверяется отдельно.")
        val intents = rows(coordinator.optJSONArray("pending_intent_details"))
        val entries = intents.filter { it.optString("role", "ENTRY") == "ENTRY" }
        val pending = entries.filter { it.optString("campaign_status") == "ENTRY_PENDING" }
        val invalidEntries = pending.filter { i ->
            val entry = i.optDouble("entry_price")
            val stop = i.optDouble("persisted_protective_stop")
            !entry.isFinite() || entry <= 0 || !stop.isFinite() || stop <= 0 ||
                when (i.optString("direction")) {
                    "LONG" -> stop >= entry
                    "SHORT" -> stop <= entry
                    else -> true
                }
        }
        check("EXE-01", "execution", "Ожидающие входные заявки", when {
            pending.isEmpty() -> "NOT_VERIFIED"
            invalidEntries.isNotEmpty() -> "FAIL"
            pending.any { it.optString("order_status") !in setOf("NEW", "WORKING", "ACCEPTED", "TRIGGERED", "PARTIALLY_FILLED") } -> "WARNING"
            else -> "PASS"
        }, "Ожидающих входов: ${pending.size}; некорректных уровней: ${invalidEntries.size}. " +
            "До исполнения отсутствие защитного ордера допустимо.")
        val partial = entries.filter { it.optString("order_status") == "PARTIALLY_FILLED" }
        check("EXE-02", "execution", "Частичное исполнение", when {
            partial.isEmpty() -> "NOT_VERIFIED"
            partial.any { !protectionProven(it, now) } -> "FAIL"
            else -> "PASS"
        }, "Частично исполненных: ${partial.size}. Любая исполненная часть требует подтверждённой защиты.")
        val positions = rows(status.optJSONArray("futures_positions"))
        val unprotected = positions.filter { p -> entries.none { matchesPosition(it, p) && protectionProven(it, now) } }
        val filledUnprotected = entries.filter {
            (it.optString("order_status") in setOf("FILLED", "PARTIALLY_FILLED") ||
                it.optString("campaign_status") in setOf("POSITION_OPEN", "OPEN_PROTECTED")) && !protectionProven(it, now)
        }
        val protectionSeverity = when {
            filledUnprotected.isNotEmpty() -> "FAIL"
            !positionsFresh(status, now) -> "NOT_VERIFIED"
            unprotected.isNotEmpty() -> "FAIL"
            positions.isEmpty() -> "NOT_VERIFIED"
            else -> "PASS"
        }
        check("EXE-03", "execution", "Защита исполненной позиции", protectionSeverity,
            "Открытых=${positions.size}; без свежего подтверждения=${unprotected.size}; " +
                "исполненных кампаний без защиты=${filledUnprotected.size}.")
        check("EXE-04", "execution", "Подтверждение стопа Binance", protectionSeverity,
            "Требуются OPEN_PROTECTED, PROTECTED, принадлежность кампании и свежая сверка; запись цены стопа сама по себе не является защитой.")
        val trailed = entries.filter { it.optLong("trailing_at_ms") > 0L }
        check("EXE-05", "execution", "Перенос стопа", when {
            trailed.isEmpty() -> "NOT_VERIFIED"
            trailed.any { !protectionProven(it, now) || it.optBoolean("previous_protection_unresolved", true) } -> "FAIL"
            !positionsFresh(status, now) -> "NOT_VERIFIED"
            else -> "PASS"
        }, "Наблюдаемых подтверждённых переносов=${trailed.size}; проверяется текущая защита и отсутствие неразрешённого предшественника.")
        val closedTrades = rows(status.optJSONArray("trade_observations")).filter { it.optString("status") == "CLOSED" }
        check("EXE-06", "execution", "Выход из позиции", when {
            closedTrades.isEmpty() -> "NOT_VERIFIED"
            closedTrades.any { it.optString("trade_audit_source") != "BINANCE_USDM_USER_TRADES" || it.optInt("fill_count") <= 0 || !it.optBoolean("pnl_complete") } -> "NOT_VERIFIED"
            else -> "PASS"
        }, "Закрытых сделок с доказательствами обменных исполнений=${closedTrades.size}; это историческое подтверждение, не новый запрос о нулевой позиции сейчас.")
        check("EXE-07", "execution", "Восстановление связи", when {
            status.optBoolean("reconcile_required", true) -> "WARNING"
            status.optBoolean("user_stream_sync_required", true) -> "WARNING"
            !positionsFresh(status, now) -> "WARNING"
            else -> "PASS"
        }, "Текущее состояние сверки; это не результат принудительного теста разрыва связи.")
        check("RISK-01", "risk", "Фактический риск позиции", when {
            !positionsFresh(status, now) || positions.isEmpty() -> "NOT_VERIFIED"
            unprotected.isNotEmpty() -> "FAIL"
            positions.any { p -> entries.any { matchesPosition(it, p) && it.optString("actual_risk_audit_status") == "BLOCKED" } } -> "FAIL"
            positions.any { p -> entries.none { matchesPosition(it, p) &&
                it.optString("actual_risk_audit_status") in setOf("PASS", "PASS_RESERVATION_ADJUSTED") &&
                it.optDouble("actual_all_in_risk_pct").let { risk -> risk.isFinite() && risk >= 0 && risk <= 0.0025 + 1e-9 } &&
                fresh(it.optLong("actual_risk_audited_at_ms"), now, 120_000L) } } -> "NOT_VERIFIED"
            else -> "PASS"
        }, "Фактический риск берётся из аудита исполнения и свежей биржевой позиции; резерв не подменяет измерение.")
        val reserved = status.optDouble("reserved_risk_pct")
        val limit = status.optDouble("max_total_risk_pct")
        check("RISK-02", "risk", "Совокупный резерв риска", when {
            !reserved.isFinite() || !limit.isFinite() || limit <= 0 -> "NOT_VERIFIED"
            reserved < 0 || reserved > limit + 1e-9 -> "FAIL"
            else -> "PASS"
        }, "Резерв=$reserved; предел=$limit. Фактический риск открытых позиций проверяется RISK-01.")
        val baseline = status.optString("daily_risk_baseline_source", "UNKNOWN")
        val daily = status.optDouble("daily_loss_pct")
        check("RISK-03", "risk", "Суточные ограничения", when {
            baseline in setOf("", "null", "UNKNOWN") || !daily.isFinite() -> "NOT_VERIFIED"
            daily >= status.optDouble("max_daily_loss_pct", 0.03) && ready.optBoolean("new_entries_allowed") -> "FAIL"
            else -> "PASS"
        }, "База=$baseline; дневной убыток=$daily; превышение лимита должно блокировать вход.")
        val android = status.optJSONObject("android_runtime")
        check("AND-01", "runtime", "Работа в фоне", when {
            android == null -> "NOT_VERIFIED"
            !android.optBoolean("foreground_service_active") || android.optBoolean("background_restricted") || !android.optBoolean("battery_optimization_ignored") -> "WARNING"
            else -> "PASS"
        }, "Проверяются ограничения Android; длительный тест на устройстве отмечается отдельно.")
        check("AND-02", "runtime", "Восстановление после перезапуска", "NOT_VERIFIED",
            "Проверка требует фактического перезапуска процесса и сверки с Binance; наличие recovery-кода не считается PASS.")
        check("AND-03", "ui", "Согласованность интерфейса", if (positionsFresh(status, now)) "PASS" else "WARNING",
            "Экраны используют общий снимок; at_ms=$now; позиции свежие=${positionsFresh(status, now)}; " +
                "сканер=${status.optString("scanner_state")}; допуск новых входов=${ready.optBoolean("new_entries_allowed")}. " + ready.optString("new_entries_reason"))
        return result
    }

    fun summary(tests: JSONArray): JSONObject {
        val rows = rows(tests)
        val fail = rows.count { it.optString("severity") == "FAIL" }
        val warning = rows.count { it.optString("severity") in setOf("WARNING", "WARN") }
        val unverified = rows.count { it.optString("severity") in setOf("NOT_VERIFIED", "UNKNOWN") }
        return JSONObject().put("overall", when {
            fail > 0 -> "FAIL"
            warning > 0 -> "WARNING"
            unverified > 0 -> "NOT_VERIFIED"
            rows.isEmpty() -> "NOT_VERIFIED"
            else -> "PASS"
        }).put("failed_test_count", fail).put("warning_test_count", warning)
            .put("not_verified_test_count", unverified).put("passed_test_count", rows.count { it.optString("severity") == "PASS" })
    }

    fun blockerText(value: String): String = when (value) {
        "exchange_confirmed_pending_entry_or_fill_not_yet_resolved" ->
            "Ожидается исполнение входной заявки или подтверждение исполнения; новые входы временно ограничены координатором"
        "exchange_reconciliation_in_progress" -> "Выполняется сверка с Binance"
        "", "null" -> "Координатор ещё не подтвердил разрешение новых входов"
        else -> value
    }

    /** Runs after each durable lifecycle change, including partial fill and stop replacement. */
    fun lifecycleAudit(intent: JSONObject): JSONObject {
        val lifecycle = campaignLifecycleStatus(intent)
        val order = intent.optString("order_status").ifBlank { intent.optString("exchange_status") }
        val protection = intent.optString("protection_status")
        val exposed = order in setOf("FILLED", "PARTIALLY_FILLED") ||
            lifecycle in setOf("POSITION_OPEN", "OPEN_PROTECTED")
        val protected = protection == "PROTECTED" && intent.optString("protection_client_id").isNotBlank()
        val risk = intent.optString("actual_risk_audit_status")
        val severity = when {
            risk == "BLOCKED" -> "FAIL"
            exposed && !protected -> "FAIL"
            lifecycle == "CLOSED_OR_STOPPED" -> "PASS"
            exposed && risk !in setOf("PASS", "PASS_RESERVATION_ADJUSTED") -> "NOT_VERIFIED"
            order in setOf("SUBMISSION_UNKNOWN", "CANCEL_SUBMISSION_UNKNOWN") -> "WARNING"
            exposed -> "PASS"
            else -> "NOT_VERIFIED"
        }
        return JSONObject().put("severity", severity).put("campaign_status", lifecycle)
            .put("order_status", order).put("protection_status", protection).put("risk_audit", risk)
            .put("scope", "Persisted exchange evidence and lifecycle invariants; not a fresh independent exchange query")
    }
}

/** Applied to every user-facing event. Raw requests, credentials and listen keys are never stored. */
internal fun safeDiagnosticText(value: String): String = value
    .replace(Regex("(?i)(api[_-]?key|api[_-]?secret|secret|signature|listenkey|token|password)([\\s\"':=]+)[^\\s&,\"}]+"), "$1=[redacted]")
    .take(800)

/** A fill racing cancellation must prevent shutdown; malformed exchange rows fail closed. */
internal fun canShutdownFuturesRuntime(cancellationConfirmed: Boolean, positions: JSONArray, intents: JSONArray): Boolean =
    cancellationConfirmed && CockpitDiagnostics.rows(positions).size == positions.length() &&
        CockpitDiagnostics.rows(positions).all { row ->
            row.optString("positionAmt").toDoubleOrNull()?.let { it.isFinite() && it == 0.0 } == true
        } && CockpitDiagnostics.rows(intents).size == intents.length() &&
        CockpitDiagnostics.rows(intents).none { isEntryCampaignReserved(it) ||
            (it.optString("intent_role", "ENTRY") == "EXIT" && it.optString("exchange_status") != "EXIT_CONFIRMED") }

