"""P0 contract: deleting Binance credentials must NOT erase account evidence.

A failed runtime stop or old-account signed preflight must leave the original
encrypted keys and historical SQLite intents untouched.
"""
from pathlib import Path

def test_credential_clear_rejects_false_stop_and_preserves_forensic_intents():
    source=(Path(__file__).resolve().parents[1]/
        "app/src/main/java/com/williamsbot/StandaloneRuntime.kt").read_text()
    block=source.split("    @Synchronized\n    fun clear(): JSONObject {",1)[1].split(
        "    @Synchronized\n    fun start(): JSONObject {",1)[0]
    assert "val stopped=stop()" in block
    assert 'check(stopped.optBoolean("stopped",false))' in block
    assert block.count("verifyOldAccountFlat()") >= 3  # 1 local function + 2 calls
    assert "adapter.allOpenOrders()" in block
    assert "adapter.allOpenAlgoOrders()" in block
    assert "futuresOldAccountHasNoExposure(before,standard,conditional,after)" in block
    assert 'executionStore.writeState("futures_campaign_intents_v2", "[]")' not in block
    assert "CREDENTIAL_CLEAR_MISSING_OLD_KEY_WITH_UNSCOPED_HISTORY" in block
    assert 'remove("api_key").remove("api_secret")' in block
    assert block.index('check(stopped.optBoolean("stopped",false))') < block.index(
        'remove("api_key").remove("api_secret")')
