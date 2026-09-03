# Model-package compatibility contract

The application accepts only the versioned `serbian-model-package` schema v1.
This contract describes the checks performed before a package becomes active;
schema validity is not legal clearance.

## Identity and payload contract

| Field | Required value |
|---|---|
| Schema | `serbian-model-package:1` |
| Package identity | SHA-256 of package ID, package version and sorted artifact path/hash pairs; manifest is excluded from its own checksum |
| Model | Kokoro-82M family, ONNX, AI ONNX opset 18, IR 8 |
| Inputs | `input_ids` int64 `[1, seq_len]`, `ref_s` float32 `[1, 256]`, positive scalar `speed` |
| Outputs | `waveform` float32 mono 24,000 Hz and `pred_dur` int64 with matching sequence length |
| Input limits | 507 operational phoneme symbols; 510 hard limit; vocabulary size 178 |
| Voice | `dragana-sr`, float32 style table `[510, 1, 256]`; row `min(symbol_count, 509)` |
| Preprocessing | `kokoro-sr-ca5590d9`, contract v1, Serbian locale, exact eSpeak-NG IPA mode 3 command |
| Android runtime | ONNX Runtime Android 1.29.0, API 30+, `arm64-v8a`, CPU provider, threads 1/1 |
| Parity gate | `fp32-parity-v2`, all 26 required vectors and every declared metric pass |

## Direct model-download policy

The release and F-Droid variants declare `android.permission.INTERNET` for direct
model acquisition and `android.permission.ACCESS_NETWORK_STATE` for the
WorkManager connected-network constraint. The latter does not transfer data.
Cleartext traffic and arbitrary URLs are rejected. The allowlist is immutable
application configuration:

| Engine | Filename | Expected bytes | Outer SHA-256 | HTTPS asset |
|---|---|---:|---|---|
| `KOKORO` | `kokoro-serbian-dragana-v2.zip` | `338316574` | `58c031fd6e37a12cafe3575d26a057e10c45cdfe7c6c7605f6966e7e2406458b` | <https://github.com/homoludens/citac_knjiga/releases/download/kokoro-model-v1.0.0/kokoro-serbian-dragana-v2.zip> |
| `VITS` | `serbian-vits-1.0.0.zip` | `121971081` | `45aa231e12c8a317f0d093cfb56d54066e19b53561b4ac401661109f19abe5dc` | <https://github.com/homoludens/citac_knjiga/releases/download/vits-model-v1.0.0/serbian-vits-1.0.0.zip> |

Document import, generation, and runtime dependency acquisition remain offline.
The download transport uses only the allowlist above and preserves the existing
package on failure.

## Recorded checksums and versions

These are identities, not embedded payloads. The model archive, model bytes,
voice bytes and generated audio are not in this repository.

| Item | Version/identity | SHA-256 or status |
|---|---|---|
| Model graph | `epoch-005-onnx-fp32` | `f40e096e2e4112bc6f529160eda9a4ebdab5baf3fefbd584ec19c8f6592bbeb6` |
| Dragana voice/style | `dragana-epoch-005` | `0c16ae704368f69e5e1467a702594f56f11a5cfdd38e9ae43b708932c1d6fb8a` |
| Configuration/vocabulary | `config-5abb01e2` | `5abb01e2403b072bf03d04fde160443e209d7a0dad49a423be15196b9b43c17f` |
| Preprocessing contract | `serbian-preprocessing-contract:1` | `4b4991dda9e26d7edf9d35f41bce395fcd9215fa771c4bc453a190560a897213` |
| `kokoro` runtime | `semidark/kokoro` revision `b96fef95e6a746495f92443fac7c688f90fc57fc` | source revision pinned |
| `kokoro_sr` | revision `ca5590d9576f63b0763e51a73de0596d47f05425` | source files hash-pinned |
| eSpeak-NG | `1.52.0` / commit `4870adfa25b1a32b4361592f1be8a40337c58d6c` | source-built; no checked-in JNI library |
| ONNX Runtime AAR | `com.microsoft.onnxruntime:onnxruntime-android:1.29.0` | `e97540ca78fe36f6fe2013f82843414fb843b6c7681fb04644cba5e1406662dd` |
| Qualified local package archive | `kokoro-serbian-dragana@1.0.0` | `58c031fd6e37a12cafe3575d26a057e10c45cdfe7c6c7605f6966e7e2406458b`; external and uncommitted observation |
| Model-package identity fixture | `b5631e5f90947f968571cbca45ba39ef28572d83daa0c4984f0addc5f8bf3959` | declaration-only blocked fixture |

The seven-file eSpeak-NG data closure is hash-pinned by
`model-tools/native/espeak-data-manifest-v1.json` (7 files) and
its source/build closure by `model-tools/native/source-closure-v1.json`.
The golden corpus identity is `b35328514d1cca82d91d51c25aeacf5dd9e106f0b99b98803177c8720f096fa9`;
the active parity declaration is `fp32-parity-v2`.

## Import and qualification checks

1. Copy the selected archive to private temporary storage.
2. Parse the strict manifest and reject unknown/new core schema fields.
3. Require the model, voice, configuration, vocabulary and test-vector roles.
4. Verify package-relative paths, declared sizes and SHA-256 values.
5. Check runtime, ABI, preprocessing, sample-rate, tensor and parity versions.
6. Publish atomically only after validation; retain the previous valid package
   on failure and record an actionable failure code.

The desktop v2 report records 26/26 parity vectors with worst MAE
`0.0044169974`, maximum error `0.1190068983`, and minimum STFT cosine
`0.9992545506`. Historical Poco F3 ARM64 parity also passed 26/26, but the
current app intentionally has no staged package. Android 11/16 matrix coverage,
external-player coverage, and legal model distribution remain separate gates.

## Distribution status

The package legal status is **blocked**. Do not infer public redistribution from
checksums, parity, or schema validation. The model/voice/dataset attribution
and outstanding legal reviews are in `model-attribution.md` and the linked
legal inventory.
