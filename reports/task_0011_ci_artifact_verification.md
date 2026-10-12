# TASK 001.1 — CI and artifact verification
**Status: INCOMPLETE — no final-SHA green CI evidence.**

## Baseline evidence
- Baseline Campaign CI: run `38236578665` — previously reported successful for baseline `11746c202fd2b7eb2126292d3e9978a2f238169b`.
- Baseline backend CI: run `38236578664` — previously reported successful for that baseline.
- These baseline results are not evidence for the implementation SHA.

## Implementation runs observed
- SHA `946f12be166fdd590fb7254f60ddfc2f240b60d3`: Campaign CI `38237422144` (Android build/unit tests and Python tests were in progress at last inspection); backend `38237422162` (dependency advisory scan in progress at last inspection); standalone APK workflow `38237423151` ended with `startup_failure`.
- Latest code SHA: `05d26e34cb34af66472124621a95c118ec69a81a`. The connector did not return PR-triggered workflow runs for this exact SHA at report preparation time. Therefore no CI result is attributed to the latest SHA.

## Release/security gates
- Standalone Android `startup_failure` is an explicit unresolved CI blocker.
- SCA/dependency advisory, release trust, branch protection and administrative security controls must be checked against actual workflow/repository evidence before closure.
- Workflow permissions were not changed to bypass the failure.
