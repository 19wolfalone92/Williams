# TASK 002.2 — APK Provenance

**Status: PARTIAL BYTE VERIFICATION / SOURCE-TO-ARTIFACT PROVENANCE BLOCKED**

See [APK_PROVENANCE.md](APK_PROVENANCE.md) for the complete artifact inventory, hashes, metadata, signing limitations and source-SHA distinction.

Latest independently downloaded artifact evidence belongs to Android workflow run `38142664769`; its actual checkout SHA was merge commit `0330466f75d17cec1a13cd931563f402e394dbb0`, despite artifact workflow metadata pointing at branch head `0189f85f3268dc96f4950f700628376d71cd57d4`.

- Debug APK SHA-256: `9f7c0ad5dba71fb8a2761727902b2ddeade99b09b19424760a956d8753e7ae55`
- Unsigned release APK SHA-256: `340a1eeccd3dae45685de0fde4a1191e3a483912e5e7996a35fcf318094e5a3e`
- Artifact ZIP digests match GitHub metadata; APK hashes match the checksum files inside the ZIPs.
- CI reports debug APK Signature Scheme v2 verification success and checks its certificate against the configured persistent debug keystore; independent local certificate verification was not available.
- Release artifact is unsigned.
- New exact-head workflow correction was committed as `b109a3b1351e8e7862c42aab056f164bd1916570`; its Android run `38144417385` was queued at the last check.

Do not label either older APK as built from isolated branch SHA `0189f85...`. Do not mark release provenance PASS until a successful exact-head build and independent artifact verification are available.
