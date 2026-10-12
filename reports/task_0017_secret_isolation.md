# TASK 001.9 — BL-004 Secret Isolation

- Code/test SHA: `fd3276c5f041dd47f0941d5b07fff4726c8c9124`.
- Campaign Python test step [38306238359](https://github.com/19wolfalone92/Williams/actions/runs/38306238359): 259 passed, 4 warnings, including the temporary-SQLite synthetic sentinel test. Overall workflow was later cancelled during cleanup/concurrency.
- Branch HEAD before this report refresh: `1b051659dba73913ad3c2e0a39f8278042cf4729`.

## Tested

- A synthetic sentinel is injected into a transport exception after fake-exchange acceptance.
- Public RuntimeError is checked for sentinel absence and suppressed cause/context.
- Actual temporary SQLite contents are scanned after the ambiguity path.
- Existing Python redaction tests and selected Android credential/fingerprint tests remain in the suite.

## Remaining gaps

- Full matrix for API key, secret, signature, Authorization header and signed query parameters across all transport/recovery paths.
- Scanning every application log, Android/Python SQLite database, DecisionTrace, diagnostic output, crash/recovery output and CI artifact.
- The previously downloaded APK artifact is from SHA 51ca573 and is not proof of artifact-wide sentinel absence for the latest source.
- Exact final report-HEAD CI and Android tests remain pending.

Only synthetic sentinel values were used. No real credentials were read or emitted. P0 remains open.