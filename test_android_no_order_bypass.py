from pathlib import Path
import re


ANDROID_RUNTIME = Path("app/src/main/java/com/williamsbot/StandaloneRuntime.kt")
ALLOWED_MUTATION_OWNERS = {
    "withCampaignMutation",
    "signedPost",
    "signedDelete",
    "signedCancelReplace",
}


def _function_at_line(lines, target_index):
    current = "<top-level>"
    brace_depth = 0
    function_depth = None

    for index, line in enumerate(lines):
        # Capture one-line/multiline Kotlin function declarations. The body
        # begins at the first '{' after the declaration.
        match = re.match(
            r"s*(?:private|public|internal|protected)?s*(?:suspends+)?"
            r"funs+([A-Za-z_][A-Za-z0-9_]*)s*(",
            line,
        )
        if match and function_depth is None:
            current = match.group(1)
            if "{" in line[line.find("("):]:
                function_depth = brace_depth
        opens = line.count("{")
        closes = line.count("}")

        if function_depth is None and match:
            # Function signature may continue over several lines; wait for body.
            current = match.group(1)
            if opens:
                function_depth = brace_depth

        brace_depth += opens - closes

        if function_depth is not None and brace_depth <= function_depth:
            function_depth = None
            current = "<top-level>"

        if index == target_index:
            return current

    return current


def test_android_runtime_has_no_direct_order_mutation_bypass():
    assert ANDROID_RUNTIME.exists(), str(ANDROID_RUNTIME)
    lines = ANDROID_RUNTIME.read_text(encoding="utf-8").splitlines()

    findings = []
    for index, line in enumerate(lines):
        if re.search(r"signed(?:Post|Delete)s*(", line):
            owner = _function_at_line(lines, index)
            if owner not in ALLOWED_MUTATION_OWNERS:
                findings.append(
                    f"{ANDROID_RUNTIME}:{index + 1}: "
                    f"signed mutation outside execution door: {owner}"
                )

    assert not findings, "
".join(findings)
