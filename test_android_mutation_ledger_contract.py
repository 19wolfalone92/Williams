from pathlib import Path


def test_android_order_mutations_use_durable_write_ahead_ledger():
    source = (
        Path(__file__).parent
        / "app"
        / "src"
        / "main"
        / "java"
        / "com"
        / "williamsbot"
        / "StandaloneRuntime.kt"
    ).read_text(encoding="utf-8")

    assert '): JSONObject = durableMutation("POST", path, params)' in source
    assert '): JSONObject = durableMutation("DELETE", path, params)' in source

    start = source.index("private fun durableMutation(")
    end = source.index("private fun updateMutationIntent(", start)
    mutation = source[start:end]
    write_ahead = mutation.index('record.put("status", "PENDING")')
    persist = mutation.index('putString(mutationIntentPrefsKey, ledger.toString()).commit()', write_ahead)
    request = mutation.index("signedRequest(verb, path, params)")
    assert write_ahead < persist < request
    assert 'updateMutationIntent(intentKey, "UNKNOWN"' in mutation or (
        'updateMutationIntent(' in mutation and '"UNKNOWN"' in mutation
    )
    assert "Mutation identity already exists; reconcile before retry" in mutation
    assert "could not be persisted" in mutation.lower()


def test_android_start_and_explicit_recovery_reconcile_mutation_ledger_first():
    source = (
        Path(__file__).parent
        / "app"
        / "src"
        / "main"
        / "java"
        / "com"
        / "williamsbot"
        / "StandaloneRuntime.kt"
    ).read_text(encoding="utf-8")

    start = source.index("fun start(): JSONObject")
    recover = source.index("fun recover(): JSONObject")
    start_block = source[start:source.index("fun stop()", start)] if "fun stop()" in source[start:] else source[start:start + 5000]
    recover_block = source[recover:recover + 1800]
    assert start_block.index("reconcileDurableMutationLedger()") < start_block.index("recoverPendingEntries()")
    assert recover_block.index("reconcileDurableMutationLedger()") < recover_block.index("clearReconcileRequired()")
