# Baseline logs — NOT RUN

No baseline test logs are included because the audit environment did not have a trusted Git bundle or complete local checkout. GitHub API content/metadata is not a substitute for local Git object verification and execution on the exact HEAD.

Do not create synthetic logs or copy CI status as local baseline evidence.

To complete this directory, capture for each command:
- exact command and working directory;
- source commit SHA and clean/dirty state;
- OS/architecture;
- Python, JDK, Android SDK, Gradle, Kotlin and Android Gradle Plugin versions as applicable;
- dependency resolution/lock state;
- stdout and stderr;
- process exit code and timestamp.

Required minimum checks include `git bundle verify` and `git fsck --full` after trusted-source delivery, Python test suite, `full_safety_audit.py`, `test_production_architecture.py`, and the documented Android build tasks. Device-runtime validation is a separate gate.
