from pathlib import Path


ANDROID_RUNTIME = Path(
    "app/src/main/java/com/williamsbot/StandaloneRuntime.kt"
)


def test_android_signed_order_mutations_have_runtime_execution_door():
    assert ANDROID_RUNTIME.exists(), str(ANDROID_RUNTIME)
    source = ANDROID_RUNTIME.read_text(encoding="utf-8")

    assert "private fun requireMutationDoor(path: String)" in source
    assert "EXECUTION_DOOR_BYPASS_BLOCKED" in source

    signed_post = source[
        source.index("private fun signedPost("):
        source.index("private fun signedCancelReplace(", source.index("private fun signedPost("))
    ]
    assert "requireMutationDoor(path)" in signed_post
    assert 'signedRequest("POST", path, params)' in signed_post

    signed_delete = source[
        source.index("private fun signedDelete("):
    ]
    assert "requireMutationDoor(path)" in signed_delete
    assert 'signedRequest("DELETE", path, params)' in signed_delete

    barrier = source[
        source.index("private fun <T> withCampaignMutation("):
        source.index("private fun signedGet(", source.index("private fun <T> withCampaignMutation("))
    ]
    assert "mutationDoorDepth += 1" in barrier
    assert "mutationDoorDepth = max(0, mutationDoorDepth - 1)" in barrier
    assert 'putBoolean("execution_mutation_lock", true)' in barrier
    assert 'putBoolean("execution_mutation_lock", false)' in barrier


def test_android_legacy_order_intent_cannot_execute():
    source = ANDROID_RUNTIME.read_text(encoding="utf-8")
    marker = "private fun submitOrderIntent(candidate: BaseAnalysis)"
    start = source.index(marker)
    end = source.index("private fun", start + len(marker))
    legacy_body = source[start:end]
    assert "signedPost(" not in legacy_body
    assert "withCampaignMutation(" not in legacy_body
    assert "LEGACY_ORDER_PATH_BLOCKED" in legacy_body or "legacy" in legacy_body.lower()
