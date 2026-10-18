# Internal wave price grammar — 4.30.3

This change repairs demonstrated false exclusions and one false motive skeleton
in the Python and Android diagnostic wave evidence. It grants no order authority.

* A B retracement between 90% and 100% keeps both zigzag and flat alternatives;
  actual 5–3–5 versus 3–3–5 lower-frame endpoints must distinguish them. A
  typical retracement guideline is not an exclusive classifier.
* W3 must exceed W1 before a completed motive or its following ABC is claimed,
  including when W4 overlaps W1. The overlap exception does not waive W3.
* A three-part corrective price skeleton can have an expanded B or a running C.
  Requiring every child price to be between the initial/final endpoints wrongly
  excluded these configurations. Its real excursion is retained in evidence.
* An internal five can have a truncated fifth: W3 then bounds the price extreme,
  while the orthodox endpoint is W5. W2 origin, W4 non-overlap and the
  not-shortest-W3 checks remain mandatory for this ordinary impulse grammar.
* Diagonal subwaves remain restricted to zigzags under the three-part grammar;
  accepting a flat as a generic correction does not certify it in a diagonal.

`leg_patterns` identifies the observed **price skeleton at one child degree**.
It does not assert recursively proven subwaves at all deeper degrees, a unique
Elliott interpretation, or profitability. Missing endpoints, future confirmation,
an incomplete C and ambiguous degree remain unresolved. All complex pattern
evidence keeps `can_place_orders=false`.

Sources checked: Bill Williams, *Trading Chaos*, user PDF pp. 50–56 (especially
p. 55 on A's five/three distinction); Elliott Wave International's primary
[zigzag](https://www.elliottwave.com/waveopedia/zigzags/),
[flat](https://www.elliottwave.com/waveopedia/flats/) and
[diagonal](https://www.elliottwave.com/waveopedia/elliott-wave-pattern-diagonals/)
references. Extra Elliott taxonomy is an analytical extension, not a verbatim
Williams entry instruction. Numerical segmentation choices are engineering
formalizations.

Validation completed: 720 Python tests passed, two credential-dependent tests
skipped; 32 JUnit tests of the actual production Kotlin wave and trailing-stop
modules passed after compilation with the project's Kotlin 2.3.21 compiler.
These are local JVM checks, not full Android Gradle/lint or device acceptance.

The stored 120,000-candle USD-M sample across ten contracts was replayed:
20,410 hourly snapshots, 3,440 independent pivot-prefix comparisons, 860 full
hierarchy/future-poison checks, 5,000 containment links, and 27,448 invalidation
checks passed. Of 26,168 candidates, five have positive internal price proofs;
the other classes remain unresolved in this sample. One former positive
diagonal is excluded by the repaired W3 rule. New expanded/running child
geometries and truncated internal fifths have mirrored contract tests but no
new positive historical observation in this sample.

A separate before/after comparison at all actual parent confirmation events
in the full sample rejected 519 nonextending-W3 candidates and retained 38
deep-B zigzag alternatives. Its event window starts before the main replay's
40-day warm-up, so these counters describe a different observation schedule.
Six real historical counterexamples are shared by Python and Kotlin tests.

Remote CI and APK results are recorded separately in the build report for
the final main commit. The local audit results above do not replace full
Android Gradle/lint or physical-device Futures Demo acceptance.
