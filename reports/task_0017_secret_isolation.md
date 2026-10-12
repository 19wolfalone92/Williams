# TASK 001.9 — BL-004 Secret Isolation

- Latest source/test HEAD at report preparation: `fd3276c5f041dd47f0941d5b07fff4726c8c9124`.
- Targeted Python Campaign run [38306130277](https://github.com/19wolfalone92/Williams/actions/runs/38306130277): 258 passed on prior SHA `5c6499a26a493ab4ff11ca44486850a79283d7ef`, including an end-to-end synthetic sentinel test against a temporary SQLite database and public exception output.
- Status: PARTIAL — P0 OPEN.

## Tested evidence on prior SHA

- Synthetic sentinel is injected through a submit timeout after fake-exchange acceptance.
- Public RuntimeError is checked for absence of the sentinel and for suppressed exception cause/context.
- The actual temporary SQLite database dump is scanned for the sentinel after the failure/reconciliation-required path.
- Python redaction tests exercise error handling and persistence; Android has selected credential/fingerprint/redaction unit tests.

## Remaining BL-004 gaps

- Full sentinel matrix for API key, API secret, signature, Authorization header and signed query parameters across every transport and recovery path.
- Scanning all captured application logs, Android SQLite, Python SQLite, DecisionTrace, diagnostic outputs, crash/recovery outputs and every CI artifact.
- The downloaded APK artifact from prior SHA contains the APK, checksum, provenance and signature text; it is not evidence that every diagnostic or test artifact was scanned for every sentinel.
- The new sentinel test and stricter recovery changes still need exact final-SHA CI evidence.

Only synthetic sentinel values were used. No real credentials were read or emitted. P0 remains open.