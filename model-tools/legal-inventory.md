# Legal Inventory — Training Data & Weight Redistribution (tasks 1.5, 1.6, 1.8)

Scope: whether the Dragana fine-tuned Kokoro weights can be publicly
redistributed, and what attribution / license terms attach. This is a *release
gate*, not a blocker for local development. Findings recorded 2026-08-26.

## 1.5 — Dragana dataset (single-speaker fine-tune source)

- **Dataset:** "Serbian Common Voice Style TTS Dataset"
- **Hugging Face:** `daremc86/serbian_common_voice`
  (https://huggingface.co/datasets/daremc86/serbian_common_voice)
- **Created by:** Darko Milošević (`daremc86`). **Spoken by:** Dragana.
- **License:** **CC BY 4.0** (confirmed from the live dataset card, `tags:
  license:cc-by-4.0`, 2026-08-26).
- **Speaker permission (verbatim from the dataset card):**
  > "The speaker voluntarily provided explicit permission for the recordings
  > to be used, processed, and publicly released for research and open-source
  > TTS purposes."
  > "The speaker has provided permission for public distribution and use of
  > the recordings for open-source speech synthesis research and development."
- **Attribution required (CC BY 4.0):** name the creator (Darko Milošević),
  the speaker (Dragana), the source URL, the license, and a note of any
  modifications to the data/weights.
- **Implication for the Dragana fine-tuned weights:** The checkpoint was
  fine-tuned on CC BY 4.0 audio. Redistribution of the *weights* is defensible
  under CC BY 4.0 **provided** the attribution above is preserved in the model
  package / release notes. The dataset card explicitly anticipates public
  distribution and open-source TTS use. This is the more favorable of the two
  datasets.

## 1.6 — Južne vesti corpus (base adaptation source)

- **Dataset:** "JuzneVesti-SR v1.0"
- **Authors:** Peter Rupnik, Nikola Ljubešić — Jožef Stefan Institute /
  CLARIN.SI.
- **Distribution handle:** `11356/1679` (a CLARIN.SI / handle.net identifier,
  not a plain public Hugging Face repo path). The HF API for `11356/1679`
  returns an authentication error — the corpus is **not** openly downloadable
  via HF and likely requires a CLARIN.SI account / DUA. *(Verification of the
  exact DUA was not completed — the handle.net resolver call was interrupted
  before it returned.)*
- **License:** **CC BY-SA 4.0** (per the training repo's
  `THIRD_PARTY_NOTICES.md`: "Prepared transcripts/audio and redistributed
  derivatives remain subject to attribution and ShareAlike requirements").
- **Provenance of the underlying audio:** the corpus was assembled by crawling
  Južne vesti content (text) plus YouTube video audio (see the dataset's
  `data/raw/README.md`, project `5roop/task13`). The audio originates from
  broadcast/video material — the *original* recordings carry their own
  copyright held by Južne vesti / the performers, which the CC BY-SA 4.0
  dataset release re-licenses but does not erase.
- **Implication for the weights:** This is the **higher-risk** source.
  - **ShareAlike (BY-SA):** any derivative — including model weights trained
    on the corpus — that is *redistributed* is generally expected to carry the
    same (or a compatible) license, with attribution. This can conflict with
    releasing the app/weights under a permissive license (e.g. MIT/Apache).
  - **Underlying broadcast rights:** the JV content's original copyright is
    separate from the dataset's CC BY-SA grant. A public redistribution of
    weights that "encode" JV speech characteristics may require permission
    beyond the dataset license.
  - **Net:** public redistribution of the *base-adapted* weights (and by
    extension the final Dragana checkpoint, which stacks on top) should be
    treated as **not cleared** until a defensible legal review of the JV
    ShareAlike + broadcast-rights position is completed.

## 1.8 — Legal release gate (decision)

Until 1.5 and 1.6 have a written, defensible outcome:

1. **Application code and model weights are distributed separately.** The
   open-source app (Kotlin/Compose) may be published without the weights; users
   import a checksummed model package themselves.
2. **No official public release of the model weights** (Dragana checkpoint,
   base adaptation, or derived ONNX) until the Južne vesti ShareAlike +
   broadcast-rights question is resolved in writing.
3. **Attribution is mandatory in every model package** regardless of the
   outcome: Dragana / Darko Milošević (CC BY 4.0) and, if/when the JV
   clearance permits, the Rupnik & Ljubešić / CLARIN.SI credit (CC BY-SA 4.0).
4. **Synthetic-audio disclosure:** any released audio/weights must note that
   the voice reproduces characteristics of real people (Dragana, and JV
   speakers); no use for impersonation/fraud (mirrors the training repo's
   `THIRD_PARTY_NOTICES.md`).
5. **Open question to close before any weight release:** obtain (a) the exact
   CLARIN.SI DUA / terms for handle `11356/1679`, and (b) a written opinion on
   whether the ShareAlike clause obligates the *weights* to be released
   BY-SA-compatible, or whether the app can remain permissively licensed with
   the weights under a separate ShareAlike license.

## Evidence captured

- Dragana dataset card (license + permission text): fetched 2026-08-26 from
  `https://huggingface.co/api/datasets/daremc86/serbian_common_voice` and
  `.../raw/main/README.md`.
- Južne vesti license + authors: `kokoro-serbian/THIRD_PARTY_NOTICES.md` and
  `kokoro-serbian/data/raw/README.md` (local, training repo commit
  `ca5590d`).
- Južne vesti HF/handle availability: `11356/1679` → auth error (not openly
  downloadable). Handle.net resolver lookup pending.