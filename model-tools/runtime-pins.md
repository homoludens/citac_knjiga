# Pinned Runtime Record (task 1.1)

Immutable record of the exact Kokoro runtime and `kokoro_sr` source used for
Dragana CPU inference. Every path, commit, and SHA-256 below was verified on
this machine on 2026-08-26.

## 1. Kokoro inference runtime (the `kokoro` package)

- **Repository:** https://github.com/semidark/kokoro.git (fork of hexgrad/kokoro)
- **Pinned commit:** `b96fef95e6a746495f92443fac7c688f90fc57fc` (grafted shallow clone at this commit; commit verified present on GitHub remote via `git ls-remote` on 2026-08-26 — it is the remote's HEAD)
- **Package version string:** `0.9.4` (but NOT the PyPI `kokoro==0.9.4` wheel — see §5)
- **Location on this machine:**
  `/home/homoludens/projekti/kokoro_tts_srpski_2/workspace/kokoro-serbian/runtime/upstream/kokoro-training/`
  (the `kokoro/` subdirectory is the importable package)
- **Import mechanism:** the inference script does
  `sys.path.insert(0, <kokoro-training dir>)` **before** `import kokoro`.
- **Patch status:** UNPATCHED. The pinned tree is clean at the pinned commit
  (`git status` empty in the repo). The local `kokoro_sr.runtime_patch`
  module only patches StyleTTS2 training scripts — it never touches the
  `kokoro` package used for inference.

### Package file SHA-256 (inference-critical files)

| File | SHA-256 |
|---|---|
| `kokoro/__init__.py` | `9ef0313aea8ce55a6949240d71c5fd4371ad3245e4df4473fcf61ea24bb10a96` |
| `kokoro/model.py` | `301c55350a068c4ed3b4cbd1209d2afb848bebca72db5657405e47e9dfb604db` |
| `kokoro/modules.py` | `6e14dadc706efc9a8d90e8b4919d45d57342555b242c642cd26f26c65eedc085` |
| `kokoro/pipeline.py` | `74be126c6f3eb1bf144fc95b478218e7c5a9bcac5a31f1db61f87ed0dd19fb1c` |
| `kokoro/istftnet.py` | `66515cb3369d1c0bbb2015ad7293d019fb1f80fefcb2d56e70e73b6b146af11c` |
| `kokoro/custom_stft.py` | `94fed3f9ac22bba31135bf8c4ea795762c7ab3ff44f1984d11e7babf27462d69` |
| `kokoro/__main__.py` | `3f0e28f00674e0809f5feb1f307086176f737747a5e16417c90509a91321ba74` |

`kokoro/modules.py` line 3 is the weight-norm implementation marker:
`from torch.nn.utils.parametrizations import weight_norm`
(`speak.py` checks for this string and refuses to run with the PyPI wheel.)

## 2. `kokoro_sr` source (phonenumber + orchestration)

- **Repository:** https://github.com/homoludens/kokoro-serbian.git
  (local clone `/home/homoludens/projekti/kokoro_tts_srpski_2`)
- **Pinned commit:** `ca5590d9576f63b0763e51a73de0596d47f05425` (branch `main`, clean working tree except untracked `finished_voice/`, `artifacts_dragana_epoch005.tar.gz`, `sample-review.wav` — none of which affect code)
- **Inference-critical modules:**

| File | SHA-256 |
|---|---|
| `src/kokoro_sr/phonemes.py` | `be84544903e0657d8579e567f3dae1170a2a57f3ca221911ebc49bcb9525c267` |
| `src/kokoro_sr/core.py` | `f35342bf835b6b058ed1a30370f79183f473bd4e474a03bf79cc489b54ee53c6` |
| `src/kokoro_sr/constants.py` | `2eee068c822b25b777f66f3710735fb2bf38a2c868222268052c53dc140ce22f` |

- **Phonemizer call (exact):**
  `espeak-ng -q --ipa=3 -v sr --stdin` with text on stdin, then
  `normalize_ipa()` (NFC, strip zero-widths U+200B/C/D/2060/FEFF, strip tie
  bars U+0361/035C, drop syllabic mark U+0329, merge `tʃ→ʧ`, `dʒ→ʤ`,
  `tɕ→ʨ`, `dʑ→ʥ`, collapse whitespace), then vocabulary audit against the
  Kokoro v1 symbol set (115 valid symbols; anything else raises).
- **Local patches to the inference path:** NONE. `kokoro_sr` ships
  `runtime_patch.py` (`checkpoint-runtime-v5`) but it only rewrites StyleTTS2
  training scripts in the *training* workspace; it is not applied to, and not
  required by, `phonemes.py` or the `kokoro` package.

## 3. eSpeak-NG (phonenumber dependency)

- **Binary:** `/usr/bin/espeak-ng`, version `1.52.0`, data at
  `/usr/share/espeak-ng-data` (Serbian dictionary `sr.dict` present).
- eSpeak-NG is an **external process** dependency of `phonemize_serbian`.
  The exact espeak-ng version + data files are part of the reproducible
  inference environment; see `uv.lock`/DEPLOYMENT.md notes in task 1.2.
  (Implication for Android: phonemization is eSpeak-NG-backed — this is the
  key input to task 3.6's pure-Kotlin-vs-native decision.)

The complete build/runtime version contract is `gradle/toolchain.lock.json`.
It pins Python 3.11.14, uv 0.10.12, ONNX 1.22.0, ONNX Runtime 1.29.0,
ONNX Script 0.7.1, Torch 2.13.0, SoundFile 0.14.0, and the exact eSpeak-NG
version/source commit. `scripts/verify_toolchain.py` checks these values and
fails when the required local executable or lock entry is missing.

## 4. Voice bundle (model + voice tensors)

- **Bundle directory (this repo):** `kokoro_sr_dragana_voice/`
- **Identity:** `bundle-manifest.json` kind `tts-checkpoint-bundle`,
  run `dragana-stage1e5-fresh-b4`, checkpoint `epoch-005`
  (source: `epoch_2nd_00004.pth` of the training run), fingerprint
  `01111391f6223128872de2ccebfa0712d67e0c6e8e039b6ccf1a51328b852502`.
- **Files (SHA-256 verified against `checksums.sha256` and manifest on 2026-08-26):**

| File | SHA-256 |
|---|---|
| `kokoro_dragana_sr.pth` (model, 327,225,291 B) | `4e6d11053886acd15f4e2b873efef87b7d53885bcf80b3b5fe73f79dd253ca47` |
| `sr_dragana.pt` (voice, 523,739 B) | `0c16ae704368f69e5e1467a702594f56f11a5cfdd38e9ae43b708932c1d6fb8a` |
| `config.json` | `5abb01e2403b072bf03d04fde160443e209d7a0dad49a423be15196b9b43c17f` |
| `inference.json` | `f2630c182f2ccf889ed1948d4d333edde7bc21a4e61e0ff69e9de43e21173018` |

- **Note:** `python_voice_test/` holds an *older* export
  (`epoch_2nd_00002`, 2026-08-18, model `70214f8f...`, voice `bfbb24e3...`).
  The `kokoro_sr_dragana_voice/` epoch-005 bundle is the current known-good
  artifact and the one referenced by `speak.py` (it loads from
  `../kokoro_sr_dragana_voice/`). `speak_2.py` is an ad-hoc script pointing
  at the training repo's `finished_voice/epoch-005` (byte-identical model +
  voice to this repo's bundle — verified by SHA-256).

## 5. Compatibility warning (recorded, verified behavior)

The PyPI `kokoro==0.9.4` wheel has a different weight-norm implementation and
loads this checkpoint but **produces noise**. The pinned semidark revision is
mandatory. Guard: `speak.py` asserts
`"torch.nn.utils.parametrizations import weight_norm" in kokoro.modules.__init__ source`.

## 6. Other upstreams recorded (training workspace, for provenance only)

These are NOT part of the inference path but are recorded because the
training repo bootstraps them at pinned revisions:

| Upstream | URL | Pinned revision |
|---|---|---|
| kokoro (inference pkg, above) | https://github.com/semidark/kokoro.git | `b96fef95e6a746495f92443fac7c688f90fc57fc` |
| kokoro-training (repo hosting the kokoro pkg) | https://github.com/semidark/kokoro.git | `b96fef95e6a746495f92443fac7c688f90fc57fc` |
| hexgrad/kokoro (upstream lineage) | https://github.com/hexgrad/kokoro.git | `dfb907a` (present in training workspace; not the inference pin) |
| hexgrad/misaki | https://github.com/hexgrad/misaki.git | `49ddead` |
| kikiri-tts, StyleTTS2 | (see `kokoro_sr/constants.py` UPSTREAMS) | patched per `checkpoint-runtime-v5` |
