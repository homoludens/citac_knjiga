# Benchmark report

These are recorded measurements, not acceptance gates. They contain device,
runtime and numeric observations only; no document text, generated audio or
local report path is included.

## Sustained production-path observation

Run date: 2026-08-28. Device: Xiaomi M2012K11AG / alioth, Android 13 API 33,
native ARM64. Runtime: ONNX Runtime Android 1.29.0, CPU sequential, threads
1/1. Workload: 203 inference calls and 902.45 generated audio seconds.

| Measurement | Result |
|---|---:|
| Model load | 2,964 ms |
| Workload wall time | 1,594.649 s |
| Real-time factor | 1.767 |
| Peak process PSS | 908,320,768 bytes |
| Process CPU | 114.108% average; 206.336% sampled peak |
| Battery | 52% to 50% |
| Battery temperature | 35.7 C to 37.0 C |
| Android thermal status | 0 throughout; throttling not observed |

## Runtime comparison

Each run targeted 15 seconds and generated 18.875 audio seconds on the same
Poco F3 ARM64/API 33 device. Peak memory is sampled total PSS, not portable
peak RSS.

| Configuration | Real-time factor | Peak process PSS |
|---|---:|---:|
| CPU, threads 1/1 | 1.379 | 869,524,480 bytes |
| CPU, threads 2/1 | 0.976 | 910,154,752 bytes |
| CPU, threads 4/1 | 0.603 | 895,176,704 bytes |
| XNNPACK, provider threads 1 | 1.722 | 901,241,856 bytes |
| XNNPACK, provider threads 2 | 1.680 | 909,795,328 bytes |
| XNNPACK, provider threads 4 | 1.698 | 886,729,728 bytes |

## AAC/M4A codec observation

Run date: 2026-08-30. Device: API 35 Google x86_64 emulator. Codec:
`c2.android.aac.encoder`. Fixture: four seconds of deterministic synthetic
Serbian-consonant windows at 24 kHz mono PCM16.

| Requested bitrate | M4A size | Encoded duration | Boundary gap | Boundary trim | Max drift |
|---:|---:|---:|---:|---:|---:|
| 64 kbps | 35,123 B | 3.968 s | 0 us | 245,336 us | 30,667 us |
| 80 kbps | 42,970 B | 3.968 s | 0 us | 245,336 us | 30,667 us |
| 96 kbps | 50,855 B | 3.968 s | 0 us | 245,336 us | 30,667 us |

## Exact limitations

- RTF and memory have no acceptance threshold and do not gate implementation.
- Total PSS is not portable peak RSS; process CPU lacks vendor scheduler detail;
  battery temperature is not SoC/skin temperature; thermal status lacks vendor
  zones; battery percentage is a rounded boundary sample.
- The sustained run is historical Poco F3 evidence. The current available
  emulator is not substituted for the required device qualification.
- The AAC fixture is synthetic. It cannot establish natural Serbian
  intelligibility, consonant quality, or a bitrate quality winner. Manual
  natural-speech A/B listening and Poco F3 ARM64 AAC qualification are pending.
- Task 11.4 remains blocked because Android 11, Android 16 and physical Poco
  F3 vendor battery-management runs are unavailable. Task 10.8 remains blocked
  because two external Android audio players were not available.
- A fresh full desktop parity rerun timed out; the committed parity report and
  passing validators remain the evidence. No new result is inferred here.

Reproduction wrappers are `scripts/run_android_benchmark.sh`,
`scripts/run_android_runtime_matrix.sh` and
`scripts/run_android_aac_benchmark.sh`. Keep model packages, reports and audio
outside the repository.
