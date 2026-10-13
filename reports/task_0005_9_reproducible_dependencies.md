# TASK 000.5.9 — Reproducible dependencies and SCA

## Python dependencies
The inspected `requirements.txt` uses lower bounds rather than exact pins, for example `pandas>=2.2`, `numpy>=1.26`, `requests>=2.33.0`, and `pytest>=8.3`. This is not a fully locked, reproducible dependency set. No complete lock file was verified in the current evidence. Python vulnerability SCA was not run in an isolated checkout.

## Android/Gradle dependencies
A Gradle version catalog at `gradle/libs.versions.toml` was not found at the requested path. This does not prove there is no dependency declaration elsewhere. Prior evidence says GradleDependency lint is disabled in `app/build.gradle.kts`; lint therefore cannot substitute for dependency analysis. No complete resolved dependency graph or Android vulnerability scan was available.

## Reproducibility and completeness
The connected GitHub API is not a build environment. No local dependency install, lock regeneration, package hash validation, full Python/Android test suite, or vulnerability scan was executed during this task. Do not mark SCA PASS based on incomplete manifests or absent scanner output.

## Status
**OPEN — FIX NOT VERIFIED** for dependency pinning/SCA, with incomplete evidence for the full dependency inventory.