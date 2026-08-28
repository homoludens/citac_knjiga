# Legal Inventory — Training Data & Weight Redistribution (tasks 1.5, 1.6, 1.8)

Scope: whether the Dragana fine-tuned Kokoro weights can be publicly
redistributed, and what attribution / license terms attach. This is a *release
record*, not legal advice or an independent legal opinion. Findings and
project-owner confirmation recorded 2026-08-28.

## 1.5 — Dragana dataset (single-speaker fine-tune source)

- **Dataset:** "Serbian Common Voice Style TTS Dataset"
- **Authoritative source:**
  https://huggingface.co/datasets/daremc86/serbian_common_voice
- **Created by:** Darko Milošević (`daremc86`). **Spoken by:** Dragana.
- **License:** **CC BY 4.0**, confirmed by the dataset card (`license:cc-by-4.0`).
  License text: https://creativecommons.org/licenses/by/4.0/
- **Speaker permission (verbatim from the dataset card):**
  > "The speaker voluntarily provided explicit permission for the recordings
  > to be used, processed, and publicly released for research and open-source
  > TTS purposes."
  > "The speaker has provided permission for public distribution and use of
  > the recordings for open-source speech synthesis research and development."
- **Attribution required (CC BY 4.0):** name the creator (Darko Milošević),
  the speaker (Dragana), the source URL, the license, and a note of any
  modifications to the data/weights.
- **Project package treatment:** the derived checkpoint, ONNX graph, voice/style
  data, and derived test audio carry Dragana attribution and a modification note
  in the package. The project-owner confirmation permits their public use and
  distribution as CC BY-SA 4.0 because the Južne vesti source is also part of
  the derivation. This is a project release declaration, not a legal conclusion.

## 1.6 — Južne vesti corpus (base adaptation source)

- **Dataset:** "JuzneVesti-SR v1.0"
- **Authors:** Peter Rupnik, Nikola Ljubešić — Jožef Stefan Institute /
  CLARIN.SI.
- **Authoritative source:**
  https://www.clarin.si/repository/xmlui/handle/11356/1679
- **License:** **CC BY-SA 4.0**, confirmed for this project record.
  License text: https://creativecommons.org/licenses/by-sa/4.0/
- **Provenance of the underlying audio:** the corpus was assembled by crawling
  Južne vesti content (text) plus YouTube video audio (see the dataset's
  `data/raw/README.md`, project `5roop/task13`). The audio originates from
  broadcast/video material — the *original* recordings carry their own
  copyright held by Južne vesti / the performers, which the CC BY-SA 4.0
  dataset release re-licenses but does not erase.
- **Project package treatment:** the base adaptation and final Dragana-derived
  checkpoint, ONNX graph, voice/style data, and derived test audio are treated
  as CC BY-SA 4.0 for public distribution. Required attribution identifies the
  corpus, Peter Rupnik, Nikola Ljubešić, Jožef Stefan Institute, CLARIN.SI, and
  the source URL. Modifications must identify that the corpus contributed to the
  derived model/package. This records the confirmed project treatment and does
  not assert facts beyond the supplied source/license record.

## 1.8 — Legal release gate (decision)

The confirmed project release treatment is:

1. **Application code and model weights are distributed separately.** The
   open-source app (Kotlin/Compose) may be published without the weights; users
   import a checksummed model package themselves.
2. **Public derived-package treatment:** the model, ONNX graph, voice/style
   package, and derived test audio may be publicly distributed under CC BY-SA
   4.0, with the required attribution and modification notices above. The
   `prepare_public_manifest.py` path records this treatment without changing
   the blocked negative-test fixture.
3. **Attribution is mandatory in every model package:** Dragana / Darko
   Milošević (CC BY 4.0), the JuzneVesti-SR authors and CLARIN.SI provenance
   (CC BY-SA 4.0), and the source URLs must be retained.
4. **Synthetic-audio disclosure:** any released audio/weights must note that
   the voice reproduces characteristics of real people (Dragana, and JV
   speakers); no use for impersonation/fraud (mirrors the training repo's
   `THIRD_PARTY_NOTICES.md`).
5. **Release engineering condition:** final public manifests and packages must
   be generated from the complete local payload so exact SHA-256 values and
   sizes are recorded. No weights, package archive, or generated raw audio is
   committed to this repository.

## Evidence captured

- Dragana dataset card and license:
  `https://huggingface.co/datasets/daremc86/serbian_common_voice`.
- JuzneVesti-SR repository record and license:
  `https://www.clarin.si/repository/xmlui/handle/11356/1679`.
- License texts: `https://creativecommons.org/licenses/by/4.0/` and
  `https://creativecommons.org/licenses/by-sa/4.0/`.
- Project-owner confirmation: derived model, ONNX, and voice package may be
  used and publicly distributed as CC BY-SA 4.0 with required attribution.
