from pathlib import Path


def test_native_position_management_isolates_symbol_failures():
    source = Path("app/src/main/java/com/williamsbot/StandaloneRuntime.kt").read_text()
    start = source.index("private fun manageVerifiedNativePositions(")
    end = source.index("private fun submitNativeFuturesAddition(", start)
    body = source[start:end]

    loop = body.index("for (i in 0 until positions.length())")
    guarded = body.index("try {", loop)
    caught = body.index("} catch (failure: Exception) {", guarded)
    catch_end = body.index("\n            }\n        }", caught)
    catch_body = body[caught:catch_end]

    assert loop < guarded < caught
    assert "Thread.currentThread().isInterrupted" in catch_body
    assert "POSITION_MANAGEMENT_SYMBOL_FAILED" in catch_body
    assert "POSITION_MANAGEMENT_SYMBOL" in catch_body
    assert "continue" in catch_body
    assert "POSITION_MANAGEMENT_SYMBOL_FAILURES" in body
