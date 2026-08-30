# Task 11.8 MVP Capability Matrix

Status: **blocked; task remains unchecked**

Run date: 2026-08-30. This is an audit of the `build-serbian-audiobook-mvp`
change, not a release approval. No model archive, audio, or generated test
report is stored here. The matrix contains no document text.

## Decision

**Do not declare an MVP release candidate.** The evidence supports the
implemented capability slices and the available test target, but required
release evidence is still missing for task 10.8 (two external Android audio
players) and task 11.4 (Android 11, Android 16, and physical Poco F3 sustained
generation/playback with vendor battery management). The verified model
package is intentionally external and the model/data legal gate is not closed
for public weight distribution. Natural Serbian AAC listening and Poco F3 AAC
qualification are also pending.

## Scope Scorecard

| Scope | Result |
|---|---|
| Requirements audited | 37/37 mapped to evidence or an explicit limitation |
| Scenarios audited | 43/43 named and assigned a result |
| Tasks 1.x through 11.x | 83/86 complete; 10.8, 11.4, and 11.8 remain unchecked |
| OpenSpec strict validation | Pass |
| Available JVM/fixture/connected gates | Pass, except the expected model-dependent app proof |
| Standard/F-Droid assemblies | Pass for debug and release |
| Release-candidate decision | No |

## Capability Matrix

Result meanings: `PASS` means the available evidence supports the scenario;
`CONDITIONAL` means the implementation/test is proven only under stated
fixture or device conditions; `BLOCKED` means the required evidence was not
available and was not substituted.

| Spec / requirement | Scenarios and evidence | Result | Limitation and release impact |
|---|---|---|---|
| `model-runtime` / Versioned model package | Valid package import; invalid package import. `ModelPackageStoreTest`, `validate_model_package_manifest.py`, and 39 model-tool tests pass; external v2 archive hash verified. | CONDITIONAL | Package is not checked in or staged in the app by convention; model/data legal clearance remains a release gate. |
| `model-runtime` / Reference parity gate | Parity passes; parity fails. Committed `fp32-parity-v2-report` records 26/26; threshold and preprocessing validators pass. A fresh full parity rerun exceeded 10 minutes and is not counted as fresh pass evidence. | CONDITIONAL | Current reproducible evidence is the committed v2 report, not the timed-out rerun. |
| `model-runtime` / Offline Android inference | Offline generation. Historical Poco F3 task-4.10 proof generated and played 24 kHz mono PCM with networking disabled. | CONDITIONAL | Current emulator has no verified package; full app proof cannot run without intentionally unstaged package. |
| `model-runtime` / Generation provenance | Model package changes. `CrossComponentGenerationRecoveryAndroidTest` and provenance assertions pass. | PASS | No release blocker beyond model package/legal gate. |
| `model-runtime` / Device performance reporting | Reference-device measurement. Historical Poco benchmark and CPU/XNNPACK matrix are recorded in `DEPLOYMENT.md`. | CONDITIONAL | Informational measurements; current task-11.4 sustained qualification is still blocked. |
| `serbian-text-processing` / Reference-equivalent preprocessing | Golden vector validation. JVM and connected TTS tests cover all 26 vectors and all intermediate stages. | PASS | ARM64 execution was not available in this run; historical Poco parity evidence is retained. |
| `serbian-text-processing` / Serbian script support | Equivalent Latin and Cyrillic text. Golden preprocessing and cross-component tests pass. | PASS | No unresolved functional deviation found in available evidence. |
| `serbian-text-processing` / Narration normalization coverage | Unsupported or ambiguous construction. 26-vector corpus and preprocessing tests cover numbers, punctuation, protected spans, and diagnostics. | PASS | Unsupported input is represented or diagnosed by the tested contract; no broad linguistic quality claim. |
| `serbian-text-processing` / Linguistically safe chunking | Oversized paragraph. Chunker tests cover 506/507/508 boundaries, protected spans, and punctuation-free splitting. | PASS | Uses the declared 507-symbol operational cap. |
| `serbian-text-processing` / Versioned processing output | Processing rule update. Generation-key and cross-component invalidation tests pass. | PASS | No unresolved functional deviation found in available evidence. |
| `serbian-text-processing` / Inspectable test corpus | Continuous validation. Desktop contract/validation tests pass and report first divergent stage. | PASS | Android pronunciation resources remain device-qualified only where explicitly recorded. |
| `epub-import` / User-selected EPUB import | Source provider later disappears. `EpubSourceRepositoryTest` and `EpubSourceRecoveryAndroidTest` pass. | PASS | Uses private verified source copy; original SAF URI is provenance only. |
| `epub-import` / Publication structure preservation | Filenames disagree with spine order. `EpubDocumentParserTest`, fixture validation, and direct-parser tests pass. | PASS | Readium remains a non-production spike; direct parser is the recorded choice. |
| `epub-import` / Structured narration representation | Inspect imported chapter. Parser, canonical text, preview tests pass with typed blocks and locators. | PASS | No editing UI is in scope. |
| `epub-import` / Deterministic narration cleanup | Ambiguous content. `EpubCanonicalTextTest` preserves uncertain content and emits warnings. | PASS | Perfect preservation of every EPUB construct is explicitly out of scope. |
| `epub-import` / Untrusted archive protection | Malicious archive entry. `EpubAdversarialSecurityTest`, security tests, and 11-fixture validation pass for traversal, limits, XML, encryption, and external resources. | PASS | Bounded validation is not a claim to accept every malformed publication. |
| `epub-import` / Import diagnostics | Partially malformed EPUB. Parser/canonical/preview tests retain recoverable content and warnings. | PASS | No unresolved functional deviation found in available evidence. |
| `durable-generation` / Encoding fallback and raw-PCM lifecycle | Failed encode with existing ready segment; successful PCM fallback. JVM publisher/encoding tests and API 35 codec instrumentation pass. | CONDITIONAL | Codec evidence is emulator-only; Poco vendor AAC remains unqualified. |
| `durable-generation` / Persistent incremental generation | Process terminates during a segment. JVM fault-injection and Android recovery tests pass with persisted snapshots. | CONDITIONAL | Tests simulate termination; OS force-stop/kill was not run. |
| `durable-generation` / Atomic audio publication | Invalid model output. Output validator, atomic-store, and publisher tests reject invalid audio without replacing ready state. | PASS | No unresolved functional deviation found in available evidence. |
| `durable-generation` / User job controls | Pause requested. State, bounded-runner, notification, and Android compilation/tests pass. | CONDITIONAL | Sustained foreground behavior across required Android versions is task 11.4 and remains blocked. |
| `durable-generation` / Selective regeneration and caching | One narration block changes. Generation-key and cross-component recovery tests prove unaffected segments remain reusable. | PASS | No unresolved functional deviation found in available evidence. |
| `durable-generation` / Progress and failure visibility | Mixed successful and failed segments. State, retry, notification, and UI tests pass. | PASS | No unresolved functional deviation found in available evidence. |
| `durable-generation` / Storage safeguards | Insufficient storage before generation. Storage policy/runner tests and cross-component Android test pass. | CONDITIONAL | Real device ENOSPC was not executed; modeled capacity failures are covered. |
| `durable-generation` / Restart reconciliation | Device reboot. Room/file reconciliation tests pass for simulated reboot/update markers. | CONDITIONAL | Physical reboot, app update installation, and force-stop were not executed. |
| `audiobook-playback` / Segment playback with chapter grouping | Partial chapter available; AAC encoding unavailable. Playback availability, queue, fallback, and Room-source tests pass. | CONDITIONAL | Internal/fake-provider evidence does not qualify external portable playback. |
| `audiobook-playback` / Progressive offline playback | Generation continues during playback. `ProgressivePlaybackAndroidTest` passes on API 35 with real Media3 and deterministic audio. | CONDITIONAL | Synthetic generator, not production TTS; no physical-device qualification. |
| `audiobook-playback` / Audiobook navigation and controls | Chapter navigation. JVM controls and focused app instrumentation pass. | PASS | No unresolved functional deviation found in available evidence. |
| `audiobook-playback` / Persistent listening position | Resume after restart. Room close/reopen and position persistence tests pass. | CONDITIONAL | Device reboot portion remains covered only by simulated lifecycle evidence. |
| `audiobook-playback` / System media integration | Incoming audio interruption. Media-session/system integration tests and connected playback suite pass. | CONDITIONAL | Headset/Bluetooth and vendor behavior are not sustained-qualified across task-11.4 targets. |
| `audiobook-playback` / Missing or invalid audio handling | Next segment unavailable. Availability policy and regeneration-route tests pass. | PASS | No unresolved functional deviation found in available evidence. |
| `audiobook-export` / MVP audio policy | AAC encoder available; AAC encoder unavailable during export. AAC fixture benchmark and fallback tests pass. | CONDITIONAL | Synthetic API 35 x86_64 only; natural Serbian A/B listening and Poco AAC are pending. |
| `audiobook-export` / Portable chapter export | Complete book export; incomplete project export. SAF exporter, assembler, manifest, and connected export tests pass. | BLOCKED for release gate | No chapter file was opened in two external Android audio players. Task 10.8 remains unchecked. |
| `audiobook-export` / Exported metadata and cover | Source metadata absent. Manifest/metadata/cover tests pass with fallback fields. | PASS | External-player metadata observation is still part of blocked 10.8. |
| `audiobook-export` / User-selected destination | Name collision. SAF naming and overwrite-plan tests pass. | PASS | No unresolved functional deviation found in available evidence. |
| `audiobook-export` / Recoverable and atomic export | Export destination unavailable. Recovery and cross-component tests pass with deterministic provider loss. | CONDITIONAL | Real external provider/device loss was not run; fake provider cannot satisfy 10.8. |
| `audiobook-export` / Export storage validation | Insufficient destination capacity. Estimator and failure-isolation tests pass before provider mutation. | PASS | Capacity depends on provider reporting; unknown-capacity providers fail closed. |

## Task Coverage Ledger

The task ranges below expand directly to every task label in `tasks.md`; no
task in 1.x through 11.x is omitted.

| Tasks | Audit result | Evidence / deviation |
|---|---|---|
| 1.1-1.8 | PASS with legal limitation | Runtime pins, desktop lock, reference vectors, legal/dependency inventory. Južne vesti DUA lookup is pending; weights remain separate. |
| 2.1-2.8 | PASS with parity rerun limitation | Export wrapper, ONNX graph validation, v1/v2 thresholds, desktop parity decision, Sherpa rejection, Android runtime decision. |
| 3.1-3.8 | PASS with qualification limitation | Package schema/packager, 26-vector corpus, native eSpeak-NG decision, preprocessing contract, fail-closed mismatch tests. |
| 4.1-4.10 | PASS as recorded, conditional on device/package | Android foundation, x86_64 connected 26-vector gate, tensor/output checks, historical Poco 26/26 parity and offline typed-text proof. Current verified package is not staged. |
| 5.1-5.2 | PASS, informational | Historical Poco sustained benchmark and CPU/XNNPACK matrix. No acceptance threshold. |
| 6.1-6.7 | PASS | Room schema/migrations, private storage, atomic artifacts, keys, reconciliation, JVM and connected integration tests. |
| 7.1-7.9 | PASS as recorded | Fixtures, Readium/direct-parser spike, secure SAF import, structured IR/Markdown/preview, historical Poco one-chapter offline proof. |
| 8.1-8.10 | PASS as recorded, conditional on lifecycle qualification | Chunking, state/runner/WorkManager/notification, retry/storage/recovery, progressive resume proof. Physical lifecycle operations are not claimed. |
| 9.1-9.8 | PASS as recorded, conditional on device qualification | Media3, library/UI, controls, persistence, system integration, dynamic queue, invalid audio, progressive playback. |
| 10.1-10.7 | PASS as recorded, conditional on AAC device/listening evidence | AAC benchmark/policy, verified encoding, manifest, SAF export/recovery/storage. Emulator synthetic codec evidence only. |
| 10.8 | BLOCKED, unchecked | `check_external_audio_players.sh` found no qualifying handlers; no second player APK/device. |
| 11.1-11.3 | PASS | Malicious EPUB, privacy/manifests, and cross-component recovery suites pass. |
| 11.4 | BLOCKED, unchecked | No Android 11/API 30 target, Android 16/API 36 target, or physical Poco F3 battery-management run. |
| 11.5-11.7 | PASS | Accessibility/localization, diagnostics/about, dependency/license audit and bundled notices pass. Full app connected suite still requires a staged model for the typed-text proof. |
| 11.8 | BLOCKED, unchecked | This audit records deviations; release-candidate evidence is insufficient. |

## Verification Runs

| Command / evidence | Result |
|---|---|
| `openspec validate build-serbian-audiobook-mvp --strict` | Pass. |
| `model-tools/.venv/bin/python -m pytest model-tools/tests` | 39 passed. |
| `python3 document-epub/src/test/resources/fixtures/fixture_tool.py validate` | 11 fixtures validated. |
| `python3 -m unittest discover -s epub-direct-spike -p 'test_*.py'` | 4 passed. |
| `validate_preprocessing_contract.py`, `validate_preprocessing.py`, `validate_parity_thresholds.py` | All passed. |
| `validate_model_package_manifest.py` plus external archive SHA-256 check | Passed; archive hash `58c031fd...6458b`. |
| `validate_onnx.py` against local graph and manifest | Pass; ORT 1.29.0, declared limits and runtime probes valid. |
| `./gradlew test --no-daemon --max-workers=1` | Pass. |
| All module lint tasks plus `app:verifyOfflineReleaseManifests` | Pass; standard/F-Droid release manifests have no routine network permission. |
| Core, TTS, EPUB, and playback/export connected suites | Pass: 19, 21, 2, and 17 tests respectively on API 35 x86_64. |
| Focused app accessibility, diagnostics, player-controls, and multi-chapter suite | Pass: 9 tests on API 35 x86_64. |
| Full `:app:connectedStandardDebugAndroidTest` | One expected failure: typed-text proof has no verified package; opt-in model/AAC proofs are skipped without flags. |
| Standard/F-Droid debug and release assemblies | Pass. |
| `python3 scripts/audit_dependencies.py` | Pass; 149 Android components audited, notices written. |
| `scripts/run_android_aac_benchmark.sh` with API 35 emulator | Pass for synthetic AAC fixture; not natural-speech or Poco evidence. |
| `scripts/check_external_audio_players.sh` | Blocked, exit 3; zero external local-audio handlers. |
| `scripts/run_android_qualification_matrix.sh` with verified package | Inventory only; API 30/API 36 images and physical Poco unavailable. |
| `scripts/run_android_benchmark.sh` on API 35 emulator | Blocked before execution by the intentional Poco F3 guard. |
| Fresh full desktop parity rerun | Timed out after 10 minutes; not counted as a pass. Committed v2 26/26 report remains the evidence. |
| `git diff --check` | Reports pre-existing trailing whitespace in the user-modified `AGENTS.md`; audit files introduce no such line. |

## Open Deviations

1. Task 10.8 has no two-player external playback evidence.
2. Task 11.4 lacks Android 11/API 30, Android 16/API 36, and physical Poco F3 vendor battery qualification.
3. Current app connected production TTS proof cannot run without the intentionally unstaged verified model package.
4. Natural Serbian AAC listening and Poco F3 ARM64 AAC qualification remain pending.
5. Public model-weight release remains gated by the legal inventory, including the unresolved Južne vesti DUA status.
6. The current fresh desktop parity rerun timed out; the audit relies on the committed v2 report and passing validators rather than inventing a new result.

## Source Records

- Capability requirements and all scenario names: `openspec/changes/build-serbian-audiobook-mvp/specs/*/spec.md`
- Task checkboxes and task evidence notes: `openspec/changes/build-serbian-audiobook-mvp/tasks.md`
- Reproducible deployment commands and historical evidence: `DEPLOYMENT.md`
- Qualification inventory and blockers: `reports/task-11-4-android-qualification.md`
- Project status and technical decisions: `AGENT_README.md`
