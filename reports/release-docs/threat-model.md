# Threat model

This is an engineering threat model for the documented MVP boundaries. It is
not a security certification. The main assets are private source text,
canonical narration, model packages, generated audio, playback state, export
destinations and diagnostic records.

| Untrusted input or failure | Threat | Implemented control | Residual limitation |
|---|---|---|---|
| EPUB archive selected through SAF | Zip Slip, absolute paths, duplicate/encrypted entries, oversized expansion or compression bombs | Copy to private temporary storage; canonical containment, entry/size/ratio limits, encryption checks and no extraction before validation | Bounded validation cannot prove acceptance of every malformed EPUB. |
| EPUB XML/HTML content | DTD/entity expansion and external resource access | Reject DTD/entities and external URI references; bounded XML inspection and resolver; no network fetch | Parser implementation or future format support still needs review. |
| SAF source provider or disappearing URI | Provider returns partial/corrupt data or later becomes unavailable | Stream to private storage, fingerprint and validate before publication; retain private source copy; never turn a URI into a filesystem path | The Android OS and provider remain outside the app trust boundary. |
| User-imported model package | Corrupt, incomplete, incompatible or tampered package replaces a valid package | Temporary copy, manifest/schema checks, declared size/SHA-256 checks, compatibility checks and rollback to the last valid package | Model ZIP resource-exhaustion limits are not a separately qualified security boundary; do not import untrusted packages as cleared. |
| Model inference/output | NaN, infinity, silence, clipping, wrong duration or corrupt audio becomes ready | Output validation, checksum, atomic publication, Room READY checkpoint and retry/failure state | A passing numerical contract does not establish speech quality or harmless model behavior. |
| Generated audio and playback | Missing, stale or corrupt files cause unsafe queue behavior or data loss | Reconcile provenance and checksums; queue only verified private audio; stop/skip and regeneration routes | External players and vendor media behavior are not fully qualified. |
| SAF export destination | Partial writes, collisions, low capacity or provider loss damage the project | Preflight capacity checks, `.incomplete` writes, read-back verification, safe finalization, collision handling and persisted checkpoints | Real provider/device loss and two-player playback evidence remain incomplete. |
| Process death, reboot, update or storage failure | Completed work is regenerated or a partial file is marked ready | Room state machine, bounded segment checkpoints, synced temporary writes, atomic publication and startup reconciliation | Physical force-stop/reboot/update and the required Android-version matrix remain unqualified. |
| Diagnostics or logs | Document text, URI, path, exception or secret leaks | Safe-token messages, constrained IDs/hashes/numbers and redacted export; no free-form payload logging | OS logs and user/device compromise are outside this component's control. |
| Network or remote services | Accidental upload, arbitrary fetch, or routine tracking | `INTERNET` is limited by the pinned GitHub Release asset policy; cleartext, routine network clients, analytics, and proprietary services are rejected; document import and generation are local | This is not a firewall: other installed software and user export destinations may use network access. The download transport remains a later task. |

Release interpretation: unresolved limitations are recorded, not silently
accepted. The legal model gate, missing external-player evidence and incomplete
Android qualification remain blockers for a release-candidate decision.
