package com.williamsbot

import org.json.JSONArray
import org.json.JSONObject

internal const val deviceWaveReplayModel="WILLIAMS_H1_ARCHIVE_W3_GLOBAL_LAG_V2"
internal const val deviceWaveReplayLimit=1800

internal val deviceWaveReplayClockSql="""SELECT open_time+3600000 FROM candles
    WHERE symbol=? AND interval='1h' AND open_time+3600000>?
      AND open_time+3600000<=? AND close_time<? AND close_time=open_time+3599999
    ORDER BY open_time LIMIT ?""".trimIndent()

internal val deviceWaveReplayCommitGuardSql="""SELECT 1 FROM wave_replay_state s
    WHERE s.symbol=? AND s.last_closed_ms=? AND s.model=? AND s.dirty_from_ms=0
      AND s.chain_sha256=? AND s.processed=? AND COALESCE(
        (SELECT r.revision FROM market_history_revision r WHERE r.symbol=s.symbol),0)=?""".trimIndent()

internal data class DeviceWaveReplayCursor(
    val lastClosedMs:Long=0L,val processed:Long=0L,val chain:String="",
    val records:Map<String,String> = emptyMap()
)
internal data class DeviceWaveReplayStep(
    val cursor:DeviceWaveReplayCursor,val stateHash:String,
    val journal:JSONObject,val checkpoint:JSONObject
)

/** Bounded historical working set, unbounded H1 timeline; no exchange API. */
internal fun buildDeviceWaveReplayStep(
    previous:DeviceWaveReplayCursor,history:Map<String,List<WilliamsWaveBar>>,asOf:Long
):DeviceWaveReplayStep {
    require(asOf>previous.lastClosedMs && asOf>0) { "NON_MONOTONIC_DEVICE_WAVE_REPLAY" }
    val report=buildAdaptiveHistoricalWaveReport(history,asOf,deviceWaveReplayLimit)
        .put("history_scope","SQLITE_CAUSAL_BOUNDED_WORKING_SET_FULL_H1_TIMELINE")
    val records=adaptiveReplayRecords(report)
    val changes=JSONArray()
    var revised=0
    for(id in records.keys.sorted()) {
        if(id !in previous.records) changes.put(JSONObject().put("id",id).put("event","FIRST_OBSERVED"))
        else if(records[id]!=previous.records[id]) revised++
    }
    for(id in (previous.records.keys-records.keys).sorted())
        changes.put(JSONObject().put("id",id).put("event","NO_LONGER_OBSERVED"))
    val hash=waveEvidenceSha256(canonicalWaveJson(report))
    val chain=waveEvidenceSha256("${previous.chain}\n$deviceWaveReplayModel\n$asOf\n$hash")
    // Full reports at every H1 close for seven years would consume gigabytes.
    // Keep every state commitment plus structural revisions, the latest full
    // checkpoint, and the original SQLite candles needed to reproduce any past state.
    val journal=JSONObject().put("as_of_closed_ms",asOf).put("state_sha256",hash)
        .put("chain_sha256",chain).put("previous_chain_sha256",previous.chain)
        .put("structural_revisions",changes).put("revised_records",revised)
        .put("clock_gap_observed",previous.lastClosedMs>0 && asOf-previous.lastClosedMs!=3_600_000L)
        .put("can_place_orders",false).put("can_close_positions",false)
    val checkpoint=JSONObject().put("model",deviceWaveReplayModel).put("as_of_closed_ms",asOf)
        .put("processed",previous.processed+1).put("chain_sha256",chain)
        .put("record_checkpoint",JSONObject(records)).put("report",report)
        .put("can_place_orders",false).put("can_close_positions",false)
    return DeviceWaveReplayStep(DeviceWaveReplayCursor(asOf,previous.processed+1,chain,records),
        hash,journal,checkpoint)
}

internal fun deviceWaveCursorFromJson(json:JSONObject):DeviceWaveReplayCursor {
    require(json.getString("model")==deviceWaveReplayModel) { "DEVICE_REPLAY_MODEL_CHANGED" }
    val records=json.getJSONObject("record_checkpoint")
    return DeviceWaveReplayCursor(json.getLong("as_of_closed_ms"),json.getLong("processed"),
        json.getString("chain_sha256"),records.keys().asSequence().associateWith { records.getString(it) })
}

/** Shared SQL is exercised by real SQLite transaction tests, not Android mocks. */
internal val deviceWaveReplaySchema=listOf(
    """CREATE TABLE IF NOT EXISTS market_history_revision(
        symbol TEXT PRIMARY KEY, revision INTEGER NOT NULL DEFAULT 0)""".trimIndent(),
    """CREATE TABLE IF NOT EXISTS wave_replay_state(
        symbol TEXT PRIMARY KEY, model TEXT NOT NULL, last_closed_ms INTEGER NOT NULL DEFAULT 0,
        processed INTEGER NOT NULL DEFAULT 0, chain_sha256 TEXT NOT NULL DEFAULT '',
        checkpoint BLOB, dirty_from_ms INTEGER NOT NULL DEFAULT 0,
        journal_bytes INTEGER NOT NULL DEFAULT 0, status TEXT NOT NULL DEFAULT 'NEW',
        last_error TEXT, updated_at INTEGER NOT NULL DEFAULT 0)""".trimIndent(),
    """CREATE TABLE IF NOT EXISTS wave_replay_journal(
        symbol TEXT NOT NULL, closed_ms INTEGER NOT NULL, state_sha256 TEXT NOT NULL,
        chain_sha256 TEXT NOT NULL, payload BLOB NOT NULL,
        PRIMARY KEY(symbol,closed_ms))""".trimIndent(),
    // An identical tail refresh must not invalidate years of completed replay.
    // RAISE(IGNORE) applies to this single candle insert, not the surrounding batch.
    """CREATE TRIGGER IF NOT EXISTS candle_identical_replay_guard BEFORE INSERT ON candles
        WHEN EXISTS(SELECT 1 FROM candles c WHERE c.symbol=NEW.symbol AND c.interval=NEW.interval
          AND c.open_time=NEW.open_time AND c.close_time=NEW.close_time
          AND c.open=NEW.open AND c.high=NEW.high AND c.low=NEW.low
          AND c.close=NEW.close AND c.volume=NEW.volume)
        BEGIN SELECT RAISE(IGNORE); END""".trimIndent(),
    """CREATE TRIGGER IF NOT EXISTS candle_insert_replay_revision AFTER INSERT ON candles
        BEGIN
          INSERT INTO market_history_revision(symbol,revision)
            SELECT NEW.symbol,0 WHERE NOT EXISTS(SELECT 1 FROM market_history_revision WHERE symbol=NEW.symbol);
          UPDATE market_history_revision SET revision=revision+1 WHERE symbol=NEW.symbol;
          UPDATE wave_replay_state SET dirty_from_ms=CASE
            WHEN dirty_from_ms=0 THEN NEW.close_time+1 ELSE MIN(dirty_from_ms,NEW.close_time+1) END,
            status='SOURCE_CHANGED_RESTART_REQUIRED'
            WHERE symbol=NEW.symbol AND NEW.close_time<last_closed_ms;
        END""".trimIndent(),
    """CREATE TRIGGER IF NOT EXISTS candle_replace_replay_invalidation BEFORE INSERT ON candles
        WHEN EXISTS(SELECT 1 FROM candles c WHERE c.symbol=NEW.symbol AND c.interval=NEW.interval
          AND c.open_time=NEW.open_time AND (c.close_time!=NEW.close_time OR c.open!=NEW.open
          OR c.high!=NEW.high OR c.low!=NEW.low OR c.close!=NEW.close OR c.volume!=NEW.volume))
        BEGIN
          UPDATE wave_replay_state SET dirty_from_ms=CASE WHEN dirty_from_ms=0 THEN
            (SELECT MIN(c.close_time,NEW.close_time)+1 FROM candles c WHERE c.symbol=NEW.symbol
              AND c.interval=NEW.interval AND c.open_time=NEW.open_time)
            ELSE MIN(dirty_from_ms,(SELECT MIN(c.close_time,NEW.close_time)+1 FROM candles c
              WHERE c.symbol=NEW.symbol AND c.interval=NEW.interval AND c.open_time=NEW.open_time)) END,
            status='SOURCE_CHANGED_RESTART_REQUIRED'
            WHERE symbol=NEW.symbol AND (SELECT MIN(c.close_time,NEW.close_time) FROM candles c
              WHERE c.symbol=NEW.symbol AND c.interval=NEW.interval AND c.open_time=NEW.open_time)<last_closed_ms;
        END""".trimIndent(),
    """CREATE TRIGGER IF NOT EXISTS candle_update_replay_revision AFTER UPDATE ON candles
        WHEN OLD.open!=NEW.open OR OLD.high!=NEW.high OR OLD.low!=NEW.low OR OLD.close!=NEW.close
          OR OLD.volume!=NEW.volume OR OLD.close_time!=NEW.close_time
          OR OLD.open_time!=NEW.open_time OR OLD.interval!=NEW.interval OR OLD.symbol!=NEW.symbol
        BEGIN
          INSERT INTO market_history_revision(symbol,revision)
            SELECT OLD.symbol,0 WHERE NOT EXISTS(SELECT 1 FROM market_history_revision WHERE symbol=OLD.symbol);
          INSERT INTO market_history_revision(symbol,revision)
            SELECT NEW.symbol,0 WHERE NOT EXISTS(SELECT 1 FROM market_history_revision WHERE symbol=NEW.symbol);
          UPDATE market_history_revision SET revision=revision+1 WHERE symbol IN(OLD.symbol,NEW.symbol);
          UPDATE wave_replay_state SET dirty_from_ms=CASE
            WHEN dirty_from_ms=0 THEN MIN(OLD.close_time,NEW.close_time)+1
            ELSE MIN(dirty_from_ms,MIN(OLD.close_time,NEW.close_time)+1) END,
            status='SOURCE_CHANGED_RESTART_REQUIRED'
            WHERE symbol IN(OLD.symbol,NEW.symbol) AND MIN(OLD.close_time,NEW.close_time)<last_closed_ms;
        END""".trimIndent(),
    """CREATE TRIGGER IF NOT EXISTS candle_delete_replay_revision AFTER DELETE ON candles
        BEGIN
          INSERT INTO market_history_revision(symbol,revision)
            SELECT OLD.symbol,0 WHERE NOT EXISTS(SELECT 1 FROM market_history_revision WHERE symbol=OLD.symbol);
          UPDATE market_history_revision SET revision=revision+1 WHERE symbol=OLD.symbol;
          UPDATE wave_replay_state SET dirty_from_ms=CASE
            WHEN dirty_from_ms=0 THEN OLD.close_time+1 ELSE MIN(dirty_from_ms,OLD.close_time+1) END,
            status='SOURCE_CHANGED_RESTART_REQUIRED'
            WHERE symbol=OLD.symbol AND OLD.close_time<last_closed_ms;
        END""".trimIndent()
)
