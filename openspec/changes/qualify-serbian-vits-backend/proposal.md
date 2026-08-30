## Why

The pinned Hugging Face model `daremc86/sr-cv-vits` revision `83dc1e1b95d85b9f5602dc94909706fc83dfbc6c` is a possible Serbian backend, but its legal provenance, safe conversion, output quality, runtime parity, and Android resource cost are not yet qualified. Its model card declares CC BY 4.0, one Serbian speaker (Dragana, id `0`), 22,050 Hz output, roughly 347 MB of artifacts, best results with Cyrillic, and no number support; these constraints require evidence before any production integration.

## What Changes

- Establish sequential, fail-closed qualification gates for the exact pinned revision: legal and source closure; trusted-desktop conversion to checksum-pinned, non-executable ONNX/package artifacts; deterministic desktop-to-Android parity; Serbian corpus quality; and representative Android speed, memory, stability, and thermal behavior. A failed or unresolved gate stops later production integration.
- Require the Serbian quality corpus to cover Cyrillic, numbers, abbreviations, and defined Latin-to-Cyrillic handling rather than treating the model card's preferred input or unsupported numbers as solved.
- Require a versioned, deterministic policy that reconciles native 22,050 Hz output with existing 24 kHz downstream contracts; the exact resampling mechanism is deferred to design.
- Permit qualification to reject the candidate, producing evidence and no production VITS backend.
- Only if every gate passes, add the minimum selectable VITS backend and persisted engine preference while retaining Kokoro. Existing generated audio and its provenance remain unchanged when the preference changes; new or explicitly regenerated audio uses the selected engine and records engine-specific provenance.
- Preserve the no-network runtime and prohibit Android from executing arbitrary PyTorch, checkpoint, converter, or other executable model content. Do not make size or speed claims relative to Kokoro without separate evidence.

## Capabilities

### New Capabilities

- `serbian-vits-backend-qualification`: Staged acceptance or rejection of the pinned Serbian VITS candidate, including legal/source closure, safe deterministic packaging, desktop/Android parity, Serbian input quality, representative-device resource gates, and strictly conditional backend selection behavior.

### Modified Capabilities

None. There are currently no canonical capability specs under `openspec/specs` to modify.

## Impact

- Qualification affects desktop model inspection/conversion and package tooling, legal and attribution records, reproducible evidence, Serbian corpus fixtures, and Android ONNX benchmark/parity harnesses.
- A passing outcome additionally affects the Android TTS boundary, package compatibility validation, engine preference UI/storage, generation keys and provenance, and regeneration routing; Kokoro and previously generated audio remain available.
- Any accepted package remains independently distributed, integrity checked, app-private, and usable without network access. Raw checkpoints and trusted conversion dependencies remain desktop-only.
- Release scope is unchanged unless all gates pass; rejection is a complete valid outcome and adds no production runtime, package, or user preference.
