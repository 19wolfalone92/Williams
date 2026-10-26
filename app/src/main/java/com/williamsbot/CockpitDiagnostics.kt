package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

/** Observations are read-only. This contract never authorizes an exchange mutation. */
internal object CockpitDiagnostics {
    const val VERSION = 15
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
        // The available NEW exposure budget is independent of the daily loss reset.
        // Accepted STOP entries reserve capital risk even before a position opens;
        // a healthy execution door is necessary but not sufficient for a new order.
        val reservedRisk = status.optDouble("reserved_risk_pct", Double.NaN)
        val totalRiskLimit = status.optDouble("max_total_risk_pct", Double.NaN)
        val riskBudgetKnown = reservedRisk.isFinite() && totalRiskLimit.isFinite() &&
            reservedRisk >= 0.0 && totalRiskLimit > 0.0
        val riskBudgetExhausted = riskBudgetKnown && reservedRisk >= totalRiskLimit - 1e-9
        val riskBudgetRemaining = if (riskBudgetKnown) (totalRiskLimit - reservedRisk).coerceAtLeast(0.0)
            else Double.NaN
        // The 15s cached positions envelope belongs to UI display ONLY.
        // Execution_enabled is decided independently by the native coordinator,
        // durable Entry Door and fresh signed account/exchange risk checks.
        // An asynchronous UI refresh must not create a fictitious order ban.
        val entries = technical && coordinatorReady && scannerReady &&
            status.optBoolean("execution_enabled") && !status.optBoolean("paused") &&
            !status.optBoolean("reconcile_required", true) && !status.optBoolean("kill_switch_latched") &&
            !riskBudgetExhausted
        val positions = rows(status.optJSONArray("futures_positions"))
        val intents = rows(coordinator.optJSONArray("pending_intent_details"))
        val unprotected = positions.filter { p ->
            !positionProtectionProven(p, coordinator, now)
        }
        val protectionRefreshPending = unprotected.isNotEmpty() &&
            unprotected.all { protectionAwaitingExchangeRefresh(it,coordinator,now) }
        val unmanaged=positions.filter { p ->
            intents.none { i -> matchesPosition(i,p) && protectionProven(i,now) } &&
                !operatorManagementProven(p,coordinator,now)
        }
        val monitoring = status.optBoolean("running") && snapshotFresh &&
            status.optBoolean("user_ws_connected") && !status.optBoolean("user_stream_sync_required", true)
        val dailyBaselineMissing = status.optString("daily_risk_baseline_source", "UNKNOWN") in
            setOf("", "UNKNOWN", "null")
        val pendingCount=intents.count { it.optString("campaign_status") in
            setOf("ENTRY_PENDING","FILL_CONFIRMATION_PENDING","EXIT_PENDING") }
        val resetExplanation=russianLossResetExplanation(
            consecutiveLosses=status.optInt("consecutive_losses",0),
            utcClockReady=status.optBoolean("consecutive_losses_available",false),
            baselineReady=!dailyBaselineMissing,
            pendingEntries=pendingCount
        )
        val unresolvedBookOrders=rows(coordinator.optJSONArray("external_open_orders"))
            .filter { it.optString("client_id").startsWith("W2H_") }
        val unresolvedSymbols=unresolvedBookOrders.map { it.optString("symbol") }
            .filter { it.isNotBlank() }.distinct().joinToString(", ")
        val reason = when {
            !status.optBoolean("running") -> "Движок остановлен"
            status.optBoolean("kill_switch_latched") -> "Аварийная блокировка новых операций"
            dailyBaselineMissing -> "Не подтверждена суточная база риска Binance. " + resetExplanation
            status.optBoolean("paused") -> "Новые входы приостановлены; сопровождение продолжается"
            unresolvedBookOrders.isNotEmpty() -> "Найдена неподтверждённая Binance H1-заявка" +
                (if(unresolvedSymbols.isBlank()) "" else " по $unresolvedSymbols") +
                ". Новые входы заблокированы: проверьте действующие заявки в Binance, " +
                "не отменяйте стоп открытой позиции. Автоматическая отмена запрещена."
            !coordinatorReady && coordinator.optString("blocker") == "exchange_reconciliation_in_progress" ->
                "Binance: выполняется текущая сверка позиций и заявок; новые входы временно ожидают проверки"
            !coordinatorReady -> russianExecutionBlocker(coordinator.optString("blocker"))
            !scannerReady -> "Сканер: " + russianStatus(status.optString("scanner_state", "NOT_RUN"))
            !technical -> "Ожидание связи, истории или синхронизации"
            riskBudgetExhausted -> "Свободный резерв риска исчерпан: " +
                "${String.format(java.util.Locale.US, "%.2f", reservedRisk * 100)}% из " +
                "${String.format(java.util.Locale.US, "%.2f", totalRiskLimit * 100)}%. " +
                "Ожидающие заявки Binance ($pendingCount) также резервируют риск. " +
                "Суточный лимит убытков здесь ни при чём; защита открытых сделок продолжает работать."
            !entries -> "Торговый шлюз ограничивает новые входы"
            !snapshotFresh ->
                "Новые входы разрешены шлюзом; снимок позиций для экрана пока обновляется"
            else -> "Новые входы разрешены при подтверждённом сигнале и проверке риска"
        }
        return JSONObject().put("observed_at_ms", now)
            .put("technical_ready", technical).put("new_entries_allowed", entries)
            .put("execution_door_ready", status.optBoolean("execution_enabled") && coordinatorReady)
            .put("entry_risk_budget_known", riskBudgetKnown)
            .put("entry_risk_budget_exhausted", riskBudgetExhausted)
            .put("entry_reserved_risk_pct", if (riskBudgetKnown) reservedRisk else JSONObject.NULL)
            .put("entry_max_total_risk_pct", if (riskBudgetKnown) totalRiskLimit else JSONObject.NULL)
            .put("entry_risk_budget_remaining_pct", if (riskBudgetKnown) riskBudgetRemaining else JSONObject.NULL)
            .put("position_management_ready", monitoring && unprotected.isEmpty() && unmanaged.isEmpty())
            .put("stop_only_management_ready", status.optBoolean("running") && snapshotFresh &&
                positions.any { operatorManagementProven(it,coordinator,now) } &&
                unprotected.isEmpty() && unmanaged.isEmpty())
            .put("positions_snapshot_fresh", snapshotFresh)
            .put("unprotected_positions", JSONArray(unprotected))
            .put("stop_proof_refresh_in_progress",protectionRefreshPending)
            .put("unmanaged_positions",JSONArray(unmanaged))
            .put("pending_orders", intents.count { it.optString("campaign_status") in
                setOf("ENTRY_PENDING", "FILL_CONFIRMATION_PENDING", "EXIT_PENDING") })
            .put("new_entries_reason", reason)
            .put("utc_loss_reset_explanation", resetExplanation)
            .put("utc_day_start_ms",status.optLong("consecutive_loss_utc_day_start_ms",0L))
            .put("utc_loss_streak_count",status.optInt("consecutive_losses",0))
            .put("daily_risk_baseline_known",!dailyBaselineMissing)
            .put("protection_state", when {
                !snapshotFresh -> "NOT_VERIFIED"
                positions.isEmpty() -> "NO_OPEN_POSITIONS"
                unprotected.isNotEmpty() && protectionRefreshPending -> "NOT_VERIFIED"
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

    private fun matchingProtectionObservation(
        position:JSONObject, coordinator:JSONObject, now:Long, ttlMs:Long=POSITION_TTL_MS
    ):JSONObject? {
        val quantity=position.optString("qty",position.optString("positionAmt"))
            .toBigDecimalOrNull()?.abs()?.takeIf { it.signum()>0 } ?: return null
        val stop=position.optString("stop").toBigDecimalOrNull()?.takeIf { it.signum()>0 } ?: return null
        return rows(coordinator.optJSONArray("exchange_protection_observations")).singleOrNull { o ->
            o.optString("symbol")==position.optString("symbol") &&
                o.optString("direction")==position.optString("direction") &&
                o.optString("stop_proof") in setOf("OWNED_STOP_SEEN","EXTERNAL_PROTECTED",
                    "NATIVE_REPLACEMENT_PROTECTION_SEEN") &&
                o.optString("protection_client_id").isNotBlank() &&
                o.optString("quantity").toBigDecimalOrNull()?.abs()?.compareTo(quantity)==0 &&
                o.optString("stop").toBigDecimalOrNull()?.compareTo(stop)==0 &&
                fresh(o.optLong("observed_at_ms"),now,ttlMs)
        }
    }

    /** A stale *previous* signed STOP seen while REST is actively refreshing is
     * unknown, not a proven absent stop. No trading or management permission
     * is granted until strict 15-second independent exchange evidence returns.
     * A missing/mismatched proof or any stopped reconciliation remains FAIL.
     */
    private fun protectionAwaitingExchangeRefresh(
        position:JSONObject, coordinator:JSONObject, now:Long
    ):Boolean =
        coordinator.optBoolean("reconciliation_running") &&
            coordinator.optString("blocker")=="exchange_reconciliation_in_progress" &&
            position.optString("stop_verification_status") in
                setOf("OWNED_STOP_SEEN","EXTERNAL_PROTECTED","NATIVE_REPLACEMENT_PROTECTION_SEEN") &&
            matchingProtectionObservation(position,coordinator,now,120_000L)!=null

    private fun positionProtectionProven(
        position:JSONObject,coordinator:JSONObject,now:Long
    ):Boolean {
        // Persisted OPEN_PROTECTED is ownership history, not current protection.
        // Require an exact signed exchange STOP observation. In particular a
        // working STOP canceled during a swap cannot be resurrected by a journal.
        return matchingProtectionObservation(position,coordinator,now)!=null
    }

    private fun operatorManagementProven(position:JSONObject,coordinator:JSONObject,now:Long):Boolean {
        val observation=matchingProtectionObservation(position,coordinator,now) ?: return false
        if(!observation.optBoolean("automatic_trailing_authorized") ||
            observation.optString("management_mode")!="OPERATOR_APPROVED_STOP_ONLY") return false
        return rows(coordinator.optJSONArray("operator_supervised_positions")).any { r ->
            r.optString("symbol")==position.optString("symbol") &&
                r.optString("state")=="ACTIVE" &&
                r.optString("protection_client_id")==observation.optString("protection_client_id") &&
                r.optString("last_evaluation_status") in setOf("WAITING_FOR_FIRST_CLOSED_H1",
                    "WAITING_FOR_STRUCTURE","TRAILED","RECOVERED") &&
                fresh(r.optLong("last_evaluated_at_ms"),now,120_000L)
        }
    }

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
        // Shape and signed side are independent of order/strategy journals.
        // An incomplete row or duplicate symbol must never be diagnosed as an
        // empty, healthy exchange account just because JSONArray.optJSONObject
        // silently skipped malformed members.
        val positionArray = status.optJSONArray("futures_positions")
        val positionRows = rows(positionArray)
        val invalidPositionRows = positionArray == null || positionRows.size != positionArray.length() ||
            positionRows.map { it.optString("symbol") }.distinct().size != positionRows.size ||
            positionRows.any { p ->
                val qty = p.optString("positionAmt").toBigDecimalOrNull()
                val absQty = p.optString("qty").toBigDecimalOrNull()
                val direction = p.optString("direction")
                p.optString("symbol") !in APPROVED_FUTURES_SYMBOLS ||
                    direction !in setOf("LONG", "SHORT") ||
                    qty == null || qty.signum() == 0 || absQty == null || absQty.signum() <= 0 ||
                    qty.abs().compareTo(absQty) != 0 ||
                    (qty.signum() > 0) != (direction == "LONG")
            }
        check("DATA-05", "binance", "Полнота и направление биржевых позиций", when {
            !positionsFresh(status,now) -> "NOT_VERIFIED"
            invalidPositionRows -> "FAIL"
            else -> "PASS"
        }, "Сверка формы и signed quantity: строк=${positionRows.size}; " +
            "ожидаемые Binance one-way направления LONG/SHORT; ошибочный/дублированный символ не может означать FLAT.")
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
        // The profiles are NOT interchangeable: TC1 Zero Point/stop-and-reverse,
        // NTD dimension sequencing and TC2 Wise Men use different management
        // contracts. Course remains an observer, never a source of new exposure.
        val unauthorizedNewExposure = rows(coordinator.optJSONArray("pending_intent_details"))
            .filter { it.optString("role", "ENTRY") in setOf("ENTRY", "ADD_ON") &&
                it.optString("campaign_status") in setOf("ENTRY_PENDING","FILL_CONFIRMATION_PENDING") &&
                !williamsVerifiedNewRiskSource(it.optString("strategy_profile")) }
        check("STR-10", "strategy", "Запрет ордеров неподтверждённых источников", when {
            unauthorizedNewExposure.isNotEmpty() -> "FAIL"
            coordinator.optJSONArray("pending_intent_details") == null ||
                coordinator.optString("engine") !in setOf("USD_M_FUTURES_TESTNET", "USD_M_FUTURES") ->
                "NOT_VERIFIED"
            else -> "PASS"
        }, "Запрещённых активных новых заявок=${unauthorizedNewExposure.size}; " +
            "подтверждённая область координатора=${coordinator.optString("engine")}; " +
            "Новые входы разрешены только TC2; NTD и Course наблюдают. Уже открытые чужие кампании сохраняют собственное подтверждённое защитное управление.")
        val causalTraces = candidates.flatMap { candidate ->
            rows(candidate.optJSONObject("decision_trace")?.optJSONArray("signals"))
                .map { candidate.optString("symbol") to it }
        }
        val causalViolations = causalTraces.filter { (_, signal) ->
            val barAt = signal.optLong("signal_bar_time_ms")
            val formationAt = signal.optLong("formation_at_ms")
            val knownAt = signal.optLong("known_at_ms")
            val normalizedType = signal.optString("type").removePrefix("LONG_").removePrefix("SHORT_")
            val isFractal = normalizedType == "FRACTAL"
            barAt <= 0L || barAt > now-3_600_000L ||
                formationAt <= 0L || formationAt > barAt ||
                knownAt != barAt+3_600_000L || knownAt>now ||
                (isFractal && barAt-formationAt < 2*3_600_000L)
        }
        check("STR-13", "strategy", "Причинное подтверждение H1 и фракталов t+2", when {
            causalViolations.isNotEmpty() -> "FAIL"
            candidates.size != APPROVED_FUTURES_SYMBOLS.size ||
                candidates.any { it.optJSONObject("decision_trace")?.optJSONArray("signals") == null } ->
                "NOT_VERIFIED"
            causalTraces.isEmpty() -> "NOT_VERIFIED"
            else -> "PASS"
        }, "Сигналов с подтверждённым доступным временем=${causalTraces.size}; " +
            "нарушений времени сигнала/t+2=${causalViolations.size}; " +
            "проблемные пары=${causalViolations.map { it.first }.distinct().joinToString()}; " +
            "формирующаяся свеча не считается источником входа.")
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
        // Journal NEW means a conditional order, NOT a filled position or
        // proof that an old exchange observation is still current. Diagnostic
        // (3) legitimately had two confirmed pending STOP entries and FLAT
        // signed positions. Only a FRESH completed reconciliation can label
        // their pending order state as observed; partial fills are addressed
        // separately by EXE-02 and never get a green pending-order badge.
        val stalePending=pending.filter {
            !fresh(it.optLong("last_reconciled_ms"),now,60_000L)
        }
        val ambiguousPending=pending.filter {
            it.optString("order_status") !in setOf("NEW","WORKING","ACCEPTED","TRIGGERED","PARTIALLY_FILLED")
        }
        val partiallyFilled=pending.filter { it.optString("order_status")=="PARTIALLY_FILLED" }
        check("EXE-01", "execution", "Ожидающие входные заявки", when {
            pending.isEmpty() -> "NOT_VERIFIED"
            invalidEntries.isNotEmpty() -> "FAIL"
            ambiguousPending.isNotEmpty() -> "WARNING"
            stalePending.isNotEmpty() || coordinator.optBoolean("reconciliation_running") -> "NOT_VERIFIED"
            partiallyFilled.isNotEmpty() -> "WARNING"
            !status.optBoolean("running") || status.optBoolean("paused") -> "WARNING"
            else -> "PASS"
        }, "Ожидающих входов: ${pending.size}; некорректных уровней: ${invalidEntries.size}; " +
            "сверка старше 60с/неизвестна: ${stalePending.size}; " +
            "неоднозначный статус: ${ambiguousPending.size}; частичных исполнений: ${partiallyFilled.size}. " +
            "PASS означает только свежую проверку состояния ОЖИДАЮЩЕЙ заявки; " +
            "это не исполненная позиция и не подтверждение защитного SL. " +
            "Движок работает=${status.optBoolean("running")}; пауза=${status.optBoolean("paused")}. " +
            "При выключенном телефоне биржевая условная заявка может исполниться.")
        val partial = entries.filter { it.optString("order_status") == "PARTIALLY_FILLED" }
        check("EXE-02", "execution", "Частичное исполнение", when {
            partial.isEmpty() -> "NOT_VERIFIED"
            partial.any { !protectionProven(it, now) } -> "FAIL"
            else -> "PASS"
        }, "Частично исполненных: ${partial.size}. Любая исполненная часть требует подтверждённой защиты.")
        val positions = rows(status.optJSONArray("futures_positions"))
        val unprotected = positions.filter { p -> !positionProtectionProven(p,coordinator,now) }
        val rawSignedStops=rows(coordinator.optJSONArray("exchange_protection_observations"))
        val signedPerSymbol=positions.map { p ->
            val symbol=p.optString("symbol")
            val side=p.optString("direction")
            val candidates=rawSignedStops.filter { it.optString("symbol")==symbol &&
                it.optString("direction")==side }
            val proof=candidates.singleOrNull()
            val age=proof?.optLong("observed_at_ms",0L)?.let { at ->
                if(at>0L && now>=at) now-at else -1L
            } ?: -1L
            val exact=matchingProtectionObservation(p,coordinator,now)
            val assessment=when {
                exact!=null -> "SIGNED_STOP_FRESH"
                candidates.isEmpty() -> "SIGNED_STOP_NOT_OBSERVED"
                proof==null -> "AMBIGUOUS_SIGNED_STOP_PROOFS"
                age<0L -> "INVALID_SIGNED_STOP_TIME"
                age>POSITION_TTL_MS -> "STALE_SIGNED_STOP_PROOF"
                else -> "SIGNED_STOP_IDENTITY_OR_COVERAGE_MISMATCH"
            }
            Triple(symbol,assessment,age)
        }
        check("EXE-13","execution","Актуальная Binance STOP-защита по каждой позиции",when {
            !positionsFresh(status,now) || coordinator.optJSONArray("exchange_protection_observations")==null -> "NOT_VERIFIED"
            signedPerSymbol.isEmpty() -> "NOT_VERIFIED"
            signedPerSymbol.any { it.second in setOf(
                "AMBIGUOUS_SIGNED_STOP_PROOFS",
                "SIGNED_STOP_IDENTITY_OR_COVERAGE_MISMATCH"
            ) } -> "FAIL"
            signedPerSymbol.all { it.second=="SIGNED_STOP_FRESH" } -> "PASS"
            // Staleness or absence of a signed proof is NOT an exchange-corroborated
            // missing STOP; it cannot be upgraded to PASS nor asserted absent.
            else -> "NOT_VERIFIED"
        }, "Только текущие подписанные Binance STOP (срок 15 секунд), без принятия strategy ownership: " +
            signedPerSymbol.joinToString("; ") { (symbol,state,age) ->
                "$symbol=$state (${if(age>=0) age.toString()+"мс" else "время неизвестно"})"
            }.ifEmpty { "открытых позиций не наблюдается" } +
            ". Устаревший или недоступный proof НЕ означает отмену стопа, но не разрешает перенос SL или новые входы.")

        val exchangeStopRefreshPending=unprotected.isNotEmpty() &&
            unprotected.all { protectionAwaitingExchangeRefresh(it,coordinator,now) }
        val filledUnprotected = entries.filter {
            (it.optString("order_status") in setOf("FILLED", "PARTIALLY_FILLED") ||
                it.optString("campaign_status") in setOf("POSITION_OPEN", "OPEN_PROTECTED")) && !protectionProven(it, now)
        }
        // While a new exchange snapshot is loading, a persisted POSITION_OPEN
        // plus PROTECTED journal is *not* independent evidence that Binance
        // rejected protection. Mark it NOT_VERIFIED, never PASS. Explicitly
        // unprotected filled exposure must still FAIL even before the next read.
        // Diagnostics (8): Binance can report working full-position stops for
        // recovered/orphan exposure while the original strategy-owned entry
        // ledger is unavailable. A STOP reduces risk; it does NOT grant a right
        // to trail, add, reverse, close or create a new position. Surface this
        // as a separate management failure rather than mislabelling every
        // emergency-protected position as a definitively missing stop.
        val unownedRecoveredPositions = entries.filter { intent ->
            intent.optString("kind") == "RECOVERED_EXCHANGE_POSITION" &&
                !intent.optBoolean("strategy_ownership_verified", false) &&
                intent.optString("campaign_status") in setOf("POSITION_OPEN", "OPEN_PROTECTED", "REPLACEMENT_UNRESOLVED")
        }
        // Diagnostics (9): all SIX exchange-protected SHORTs exist in the
        // signed positions snapshot while pending_intent_details is EMPTY.
        // The old EXE-12 counted only recovered local journal intents and
        // misleadingly printed "0" even though every position explicitly
        // says ownership_verified=false. Never turn a missing local journal
        // into evidence that all exchange positions belong to Williams.
        val unownedSignedPositions = positions.filter { position ->
            position.has("strategy_ownership_verified") &&
                !position.optBoolean("strategy_ownership_verified", false)
        }
        val unownedSymbols=(unownedRecoveredPositions.map { it.optString("symbol") } +
            unownedSignedPositions.map { it.optString("symbol") })
            .filter { it.isNotBlank() }.distinct()
        val signedOwnershipObserved=positions.all { it.has("strategy_ownership_verified") }
        check("EXE-12", "execution", "Восстановленные позиции без подтверждённого владельца стратегии", when {
            !positionsFresh(status,now) || coordinator.optBoolean("reconciliation_running") ->
                "NOT_VERIFIED"
            unownedSymbols.isNotEmpty() -> "FAIL"
            coordinator.optJSONArray("pending_intent_details") == null ||
                status.optJSONArray("futures_positions") == null || !signedOwnershipObserved ->
                "NOT_VERIFIED"
            else -> "PASS"
        }, "Позиции без доказанного исходного ENTRY по последнему снимку: " +
            "${unownedSymbols.size}; из signed positions=${unownedSignedPositions.size}; " +
            "из восстановленных журналов=${unownedRecoveredPositions.size}; символы=" +
            unownedSymbols.joinToString(", ").ifBlank { "нет" } +
            ". Защитный STOP может существовать, но это не разрешение автоматического переноса SL. " +
            "НЕ создавать повторный вход, не считать такие позиции управляемыми; " +
            "требуется проверка точных исполнений/клиентских ID на Binance. " +
            "Проверка диагностическая: ни одна биржевая заявка не изменяется.")
        val explicitlyUnprotected = entries.any { intent ->
            (intent.optString("order_status") in setOf("FILLED", "PARTIALLY_FILLED") ||
                intent.optString("campaign_status") in setOf("POSITION_OPEN", "OPEN_PROTECTED")) &&
                (intent.optString("protection_status") != "PROTECTED" ||
                    intent.optString("actual_risk_audit_status") == "BLOCKED")
        }
        val protectionSeverity = when {
            explicitlyUnprotected -> "FAIL"
            !positionsFresh(status, now) -> "NOT_VERIFIED"
            filledUnprotected.isNotEmpty() -> "FAIL"
            unprotected.isNotEmpty() && exchangeStopRefreshPending -> "NOT_VERIFIED"
            unprotected.isNotEmpty() -> "FAIL"
            positions.isEmpty() -> "NOT_VERIFIED"
            else -> "PASS"
        }
        check("EXE-03", "execution", "Защита исполненной позиции", protectionSeverity,
            "Открытых=${positions.size}; без свежего подтверждения=${unprotected.size}; " +
                "исполненных кампаний без защиты=${filledUnprotected.size}; " +
                "сверка свежего STOP Binance выполняется=$exchangeStopRefreshPending. " +
                "NOT_VERIFIED не означает защищённость и не разрешает новую сделку.")
        check("EXE-04", "execution", "Подтверждение стопа Binance", protectionSeverity,
            "Проверяются свежая биржевая защита, символ, направление, количество и уровень SL. " +
                "Принадлежность стратегии и разрешение сопровождения проверяются отдельно; цена стопа сама по себе не доказывает защиту.")
        val trailed = entries.filter { it.optLong("trailing_at_ms") > 0L }
        check("EXE-05", "execution", "Перенос стопа", when {
            trailed.isEmpty() -> "NOT_VERIFIED"
            trailed.any { it.optString("protection_status") != "PROTECTED" } -> "FAIL"
            !positionsFresh(status, now) -> "NOT_VERIFIED"
            trailed.any { !protectionProven(it, now) || it.optBoolean("previous_protection_unresolved", true) } -> "FAIL"
            else -> "PASS"
        }, "Наблюдаемых подтверждённых переносов=${trailed.size}; проверяется текущая защита и отсутствие неразрешённого предшественника.")
        val livePositions = rows(status.optJSONArray("futures_positions"))
        val observedProofs = rows(coordinator.optJSONArray("exchange_protection_observations"))
        // A later SIGNED exchange observation is not contradictory to an
        // older 15-second dashboard snapshot. Diagnostics (26): ETH had the
        // same symbol/side/qty/STOP, but the signed REST proof was ~8 seconds
        // newer than the UI, which incorrectly created EXE-08=FAIL. Never
        // rewrite timestamps, retroactively trust a journal, or call it PASS:
        // record the UI race as WARNING until a dashboard refresh catches up.
        val displayAt=status.optLong("positions_snapshot_at_ms")
        fun proofAheadOfDisplay(p:JSONObject,proof:JSONObject):Boolean {
            val observed=proof.optLong("observed_at_ms")
            val cachedConfirmed=p.optLong("stop_confirmed_at_ms")
            return displayAt>0L && displayAt<observed &&
                cachedConfirmed>0L && cachedConfirmed<observed
        }
        val currentProofConflicts = livePositions.filter { p ->
            val matching = matchingProtectionObservation(p,coordinator,now) ?: return@filter false
            val owner = entries.lastOrNull { intent ->
                matchesPosition(intent,p) && intent.optBoolean("strategy_ownership_verified") &&
                    intent.optString("campaign_status") == "OPEN_PROTECTED" &&
                    intent.isNull("native_stop_replacement")
            }
            val expectedSuffix = owner?.optString("protection_client_id_suffix").orEmpty()
            val ownedIdentityMismatch=expectedSuffix.isNotBlank() &&
                !matching.optString("protection_client_id").endsWith(expectedSuffix)
            val staleUI=proofAheadOfDisplay(p,matching)
            ownedIdentityMismatch || (!staleUI && (
                (p.has("stop_verification_status") &&
                    p.optString("stop_verification_status") != matching.optString("stop_proof")) ||
                (p.has("stop_confirmed_at_ms") && !p.isNull("stop_confirmed_at_ms") &&
                    p.optLong("stop_confirmed_at_ms") != matching.optLong("observed_at_ms"))))
        }
        val newerSignedStopThanUI=livePositions.filter { p ->
            val matching=matchingProtectionObservation(p,coordinator,now) ?: return@filter false
            proofAheadOfDisplay(p,matching)
        }.map { it.optString("symbol") }
        val duplicateProofs = observedProofs.groupBy { it.optString("symbol") }
            .filter { (symbol, items) ->
                symbol.isNotBlank() && items.count {
                    it.optString("stop_proof") in setOf(
                        "OWNED_STOP_SEEN","EXTERNAL_PROTECTED","NATIVE_REPLACEMENT_PROTECTION_SEEN")
                } > 1
            }.keys
        check("EXE-08", "execution", "Согласованность Binance SL, журнала и экрана", when {
            !positionsFresh(status,now) -> "NOT_VERIFIED"
            currentProofConflicts.isNotEmpty() || duplicateProofs.isNotEmpty() -> "FAIL"
            livePositions.isEmpty() || livePositions.any {
                matchingProtectionObservation(it,coordinator,now) == null
            } -> "NOT_VERIFIED"
            newerSignedStopThanUI.isNotEmpty() -> "WARNING"
            else -> "PASS"
        }, "Подтверждённых конфликтов=${currentProofConflicts.size}; " +
            "новых signed SL относительно UI=${newerSignedStopThanUI.joinToString().ifBlank { "нет" }}; " +
            "дублированных подтверждений=${duplicateProofs.size}; " +
            "новый Binance proof НЕ подтверждает свежесть более старого экрана.")

        // Diagnostics (26): a W2H_* Binance conditional order can survive
        // locally missing campaign journals. Even when ENTRY_PENDING=0,
        // a live app-originated orphan may fill and create exposure while
        // execution_enabled=false. Treat it as unresolved exchange risk,
        // never assume it is cancelled; do not mutate or delete it here.
        val openAlgoOrders=rows(coordinator.optJSONArray("external_open_orders"))
        // The coordinator's EXTERNAL_ALGO_ORDER set ALREADY excludes all
        // currently verified owned order identities. A historical CLOSED
        // intent with the same clientId cannot legitimize a live Binance
        // order that survived its durable campaign.
        val orphanBookH1Orders=openAlgoOrders.filter { order ->
            order.optString("client_id").startsWith("W2H_")
        }
        check("EXE-11","execution","Биржевые условные заявки без подтверждённого владельца",when {
            coordinator.optJSONArray("external_open_orders")==null -> "NOT_VERIFIED"
            orphanBookH1Orders.isNotEmpty() -> "FAIL"
            !positionsFresh(status,now) -> "NOT_VERIFIED"
            else -> "PASS"
        },"Открытых H1-ордеров W2H_* без действующей записи кампании=" +
            "${orphanBookH1Orders.size}; символы=" +
            orphanBookH1Orders.map { it.optString("symbol") }.distinct().joinToString().ifBlank { "нет" } +
            "; проверять в Binance вручную; диагностике ЗАПРЕЩЕНО автоматически отменять ордера.")

        val wrongSideStops = livePositions.filter { p ->
            val proof = matchingProtectionObservation(p,coordinator,now) ?: return@filter false
            val mark = p.optString("mark_price").toDoubleOrNull()
            val stop = proof.optString("stop").toDoubleOrNull()
            mark != null && mark.isFinite() && mark>0.0 && stop!=null && stop.isFinite() &&
                (if(p.optString("direction")=="LONG") stop>=mark else stop<=mark)
        }
        check("EXE-09", "risk", "Действующий STOP относительно цены MARK", when {
            !positionsFresh(status,now) -> "NOT_VERIFIED"
            wrongSideStops.isNotEmpty() -> "FAIL"
            livePositions.isEmpty() || livePositions.any { p ->
                matchingProtectionObservation(p,coordinator,now)==null ||
                    p.optString("mark_price").toDoubleOrNull()?.let { it.isFinite() && it>0.0 } != true
            } -> "NOT_VERIFIED"
            else -> "PASS"
        }, "STOP уже пересечён ценой MARK у ${wrongSideStops.joinToString { it.optString("symbol") }}; " +
            "перед торговым действием нужна НОВАЯ проверка Binance; это предупреждение, не команда закрытия.")
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
            positions.any { p -> entries.any { matchesPosition(it, p) && it.optString("actual_risk_audit_status") == "BLOCKED" } } -> "FAIL"
            unprotected.isNotEmpty() && exchangeStopRefreshPending -> "NOT_VERIFIED"
            unprotected.isNotEmpty() -> "FAIL"
            positions.any { p -> entries.none { matchesPosition(it, p) &&
                it.optString("actual_risk_audit_status") in setOf("PASS", "PASS_RESERVATION_ADJUSTED") &&
                it.optDouble("actual_all_in_risk_pct").let { risk -> risk.isFinite() && risk >= 0 && risk <= 0.0025 + 1e-9 } &&
                fresh(it.optLong("actual_risk_audited_at_ms"), now, 120_000L) } } -> "NOT_VERIFIED"
            else -> "PASS"
        }, "Фактический риск берётся из аудита исполнения и свежей биржевой позиции; резерв не подменяет измерение.")
        check("RISK-04", "risk", "Стоп до ликвидации", when {
            !positionsFresh(status, now) || positions.isEmpty() -> "NOT_VERIFIED"
            positions.any { p -> entries.any { matchesPosition(it, p) && it.optString("liquidation_guard_status") == "UNSAFE" } } -> "FAIL"
            positions.any { p -> entries.none { matchesPosition(it, p) &&
                it.optString("liquidation_guard_status") == "PASS" &&
                fresh(it.optLong("liquidation_guard_audited_at_ms"), now, 120_000L) } } -> "NOT_VERIFIED"
            else -> "PASS"
        }, "Проверяется фактическая цена ликвидации Binance и запас до структурного стопа; ноль или отсутствующая цена не считаются PASS.")
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
            status.optBoolean("running") && (!android.optBoolean("cpu_wake_lock_held") ||
                android.optLong("service_guard_age_ms",Long.MAX_VALUE)>90_000L) -> "WARNING"
            else -> "NOT_VERIFIED"
        }, "Сервис=${android?.optBoolean("foreground_service_active")}; CPU=${android?.optBoolean("cpu_wake_lock_held")}; " +
            "контроль=${android?.optLong("service_guard_age_ms",-1)} мс. Эти признаки не доказывают непрерывную работу с выключенным экраном.")
        val bootWitness=if(android!=null)
            AndroidRebootRecoveryEvidence.assess(android.optJSONObject("continuity") ?: JSONObject(),
                android,status,now) else null
        check("AND-02", "runtime", "Восстановление после перезагрузки Android", when {
            bootWitness==null || !bootWitness.optBoolean("reboot_observed") -> "NOT_VERIFIED"
            bootWitness.optBoolean("recovery_technical_proven") -> "PASS"
            bootWitness.optLong("opt_in_verified_at_ms")<=0L -> "NOT_VERIFIED"
            now-bootWitness.optLong("boot_at_ms")>120_000L -> "WARNING"
            else -> "NOT_VERIFIED"
        }, "Последняя наблюдаемая перезагрузка: ${bootWitness?.optLong("boot_at_ms")}; " +
            "opt-in: ${bootWitness?.optLong("opt_in_verified_at_ms")}; " +
            "FGS: ${bootWitness?.optLong("fgs_created_at_ms")}; " +
            "worker: ${bootWitness?.optBoolean("worker_live_now")}; " +
            "post-boot signed REST sync: ${bootWitness?.optBoolean("signed_sync_after_boot")}; " +
            "код: ${bootWitness?.optString("status")}. PASS доказывает только последовательность boot->FGS->worker->signed sync, " +
            "но НЕ непрерывный 24-часовой фон, полноту защиты STOP или готовность к новым входам.")
        val lifecycle=android?.optJSONObject("lifecycle_evidence")
        check("AND-04", "runtime", "Остановки Android, перезапуски и запросы запуска", when {
            lifecycle == null -> "NOT_VERIFIED"
            lifecycle.optInt("abnormal_process_exit_count")>0 ||
                lifecycle.optInt("service_autostart_failure_count")>0 ||
                lifecycle.optInt("worker_heartbeat_stale_count")>0 -> "WARNING"
            // Normal Android service destruction or a system exit without a
            // known reason warrants review but is not proof of lost protection.
            lifecycle.optInt("observed_process_exit_count")>0 ||
                lifecycle.optInt("service_destroyed_count")>0 -> "WARNING"
            else -> "NOT_VERIFIED"
        }, "FGS создан: ${lifecycle?.optInt("foreground_service_created_count",-1)}; " +
            "реальные завершения процесса из истории Android: ${lifecycle?.optInt("observed_process_exit_count",-1)}; " +
            "аномальные завершения: ${lifecycle?.optInt("abnormal_process_exit_count",-1)}; " +
            "уничтожения сервиса: ${lifecycle?.optInt("service_destroyed_count",-1)}; " +
            "ошибки автозапуска: ${lifecycle?.optInt("service_autostart_failure_count",-1)}; " +
            "зависания heartbeat: ${lifecycle?.optInt("worker_heartbeat_stale_count",-1)}; " +
            "старые SERVICE_START_ATTEMPT: ${lifecycle?.optInt("service_start_attempt_count",-1)} " +
            "(это НЕ перезапуски); статус истории ОС=${lifecycle?.optString("system_exit_history_status")}. " +
            "Показана только сохранённая часть последних 24 часов; отсутствие событий НЕ доказывает 24-часовой фон.")
        // Diagnostics (9): full Binance signed fills are an independent GET-only
        // forensic source. An observed BNB BUY is not an automatic proof that
        // the old SHORT campaign closed or that all commission/funding is known.
        val fillAudit=status.optJSONObject("signed_exchange_fill_forensics")
        val fillAuditRows=rows(fillAudit?.optJSONArray("symbols"))
        val bnbFillAudit=fillAuditRows.singleOrNull { it.optString("symbol")=="BNBUSDT" }
        val bnbFillStatus=bnbFillAudit?.optString("status") ?: "NOT_REQUESTED"
        check("HIST-02","database","Подписанные исполнения Binance для закрытия BNB",when {
            fillAudit?.optString("state")=="SIGNED_GET_FAILED" -> "WARNING"
            bnbFillStatus=="SIGNED_TRADE_SHAPE_UNVERIFIED" -> "WARNING"
            bnbFillStatus=="RECENT_SIGNED_FILLS_NO_CAMPAIGN_BINDING" -> "WARNING"
            else -> "NOT_VERIFIED"
        },"Состояние GET-only: ${fillAudit?.optString("state") ?: "NOT_REQUESTED"}; " +
            "BNB: $bnbFillStatus; BUY qty=${bnbFillAudit?.optString("buy_qty")}; " +
            "SELL qty=${bnbFillAudit?.optString("sell_qty")}; " +
            "биржевые orderIds=${bnbFillAudit?.optInt("exchange_order_count")}; " +
            "источник подписан, но запись закрытия BNB с доказанным исходным ENTRY пока НЕ получена. " +
            "Это не разрешение нового входа и не подтверждение полного PnL.")
        val closedTradeRows=rows(status.optJSONArray("trade_observations"))
        val unverifiedCloses=closedTradeRows.filter { it.optString("status")=="CLOSED_UNVERIFIED" ||
            it.optString("outcome")=="CLOSED_UNVERIFIED" }
        val observedClosures=closedTradeRows.filter {
            it.optString("status").startsWith("CLOSED") || it.optLong("closed_at_ms")>0L
        }
        val missingPnl=observedClosures.filter { !it.optBoolean("pnl_complete",false) }
        // Two audit rows can legitimately describe ONE exchange position (native
        // recovery plus operator stop supervision). Do not silently erase either
        // source, infer an exit price, or double-count them as two completed trades.
        val uncertainSymbols=unverifiedCloses.map { it.optString("symbol") }.filter { it.isNotBlank() }.distinct()
        check("HIST-01", "database", "Восстановление закрытий, комиссий и PnL", when {
            !status.has("trade_observations") -> "NOT_VERIFIED"
            unverifiedCloses.isNotEmpty() || missingPnl.isNotEmpty() -> "WARNING"
            observedClosures.isEmpty() -> "NOT_VERIFIED"
            else -> "PASS"
        }, "Подтверждённых записей закрытия в снимке: ${observedClosures.size}; " +
            "неподтверждённых записей закрытия: ${unverifiedCloses.size}; " +
            "записи без полного PnL: ${missingPnl.size}; " +
            "уникальные пары с неподтверждённым закрытием: ${uncertainSymbols.joinToString(",").ifEmpty { "нет" }}. " +
            "Несколько источников одной позиции НЕ означают несколько сделок; " +
            "пока Binance не подтвердил исполнения, комиссии и funding, не рассчитывать прибыль по цене SL.")
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
        val role = intent.optString("intent_role", "ENTRY").uppercase(java.util.Locale.US)
        val lifecycle = campaignLifecycleStatus(intent)
        val order = intent.optString("order_status").ifBlank { intent.optString("exchange_status") }
            .uppercase(java.util.Locale.US)
        val exchangeStatus = intent.optString("exchange_status").uppercase(java.util.Locale.US)
        val protection = intent.optString("protection_status")
        val risk = intent.optString("actual_risk_audit_status")
        // EXIT intents are exchange-side reduce-only orders and must not carry
        // the ENTRY campaign's structural stop/protection/risk snapshot. Applying
        // the ENTRY invariant to EXIT FILLED orders generated spurious FAIL events
        // and incident records despite Binance-confirmed flat positions.
        val severity = if (role == "EXIT") {
            when {
                exchangeStatus == "EXIT_CONFIRMED" -> "PASS"
                // FILLED while Binance still sees exposure (possibly a newer
                // campaign on the same symbol) is NOT proof of a flat account.
                exchangeStatus == "EXIT_TERMINAL_POSITION_OPEN" -> "WARNING"
                exchangeStatus in setOf("SUBMISSION_UNKNOWN", "CANCEL_SUBMISSION_UNKNOWN") ->
                    "WARNING"
                else -> "NOT_VERIFIED"
            }
        } else if (role == "ENTRY") {
            val exposed = order in setOf("FILLED", "PARTIALLY_FILLED") ||
                lifecycle in setOf("POSITION_OPEN", "OPEN_PROTECTED")
            val protected = protection == "PROTECTED" &&
                intent.optString("protection_client_id").isNotBlank()
            when {
                risk == "BLOCKED" -> "FAIL"
                exposed && !protected -> "FAIL"
                lifecycle == "CLOSED_OR_STOPPED" -> "PASS"
                exposed && risk !in setOf("PASS", "PASS_RESERVATION_ADJUSTED") -> "NOT_VERIFIED"
                order in setOf("SUBMISSION_UNKNOWN", "CANCEL_SUBMISSION_UNKNOWN") -> "WARNING"
                exposed -> "PASS"
                else -> "NOT_VERIFIED"
            }
        } else {
            // Unknown role cannot be treated as a protected entry or confirmed exit.
            "FAIL"
        }
        return JSONObject().put("severity", severity).put("intent_role", role)
            .put("campaign_status", lifecycle).put("exchange_status", exchangeStatus)
            .put("order_status", order).put("protection_status", protection).put("risk_audit", risk)
            .put("scope", "Role-aware persisted exchange evidence and lifecycle invariants; not a fresh independent exchange query")
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

