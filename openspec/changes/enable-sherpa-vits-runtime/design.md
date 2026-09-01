## Context

The repository already has an offline `tts-onnx` module, model package
integrity checks, Serbian preprocessing, Room generation provenance, and a
24 kHz mono audio contract. The prior Sherpa experiment only rejected using
Sherpa's Kokoro/Piper text frontend for the existing custom Kokoro graph. This
change evaluates the Coqui VITS model on its own Sherpa VITS interface.

## Design

1. **Runtime.** Pin a Sherpa-ONNX source revision and build its Android native
   library from source for the existing ABI policy. Keep desktop conversion
   dependencies out of Android and include Apache-2.0 notices and source
   closure records.
2. **Conversion.** Fetch only the pinned Hugging Face revision in a disposable
   workspace, convert the Coqui VITS checkpoint to the Sherpa VITS model file
   layout, and record model/tokens/config hashes. No checkpoint or generated
   audio is committed as an Android input unless it is explicitly declared as
   a release asset with attribution.
3. **Frontend.** Inspect the converted model's token vocabulary and implement
   the smallest adapter around the existing Serbian text policy. Do not call
   Sherpa's Kokoro frontend. Fail closed for unsupported model input rather than
   silently dropping symbols.
4. **Audio.** Validate Sherpa's native 22,050 Hz result, apply one versioned
   22,050-to-24,000 Hz resampling operation, then reuse the existing WAV/codec
   validation and atomic publication boundary.
5. **Selection.** Add an engine-qualified package slot and runtime boundary.
   Qualification state controls availability; Kokoro remains the default.
   Engine, model, speaker, frontend, resampler, and runtime identities are
   included in existing generation keys and audio provenance.
6. **Qualification.** Use Android 13/API 33 as the required device level,
   testing native `arm64-v8a` plus an equivalent API 33 development ABI where
   available. Record offline generation, valid 24 kHz output, memory, timing,
   interruption, and recovery. Missing evidence leaves VITS unavailable.

## Rejected Alternatives

- Sherpa's generic Kokoro/Piper frontend: it does not match this VITS model's
  tokenizer and Serbian preprocessing contract.
- Running PyTorch or a Coqui converter on Android: it expands the runtime and
  security surface and violates the offline package boundary.
- Lowering `minSdk` below 30: the application baseline remains unchanged.
- Replacing Kokoro or rewriting existing audio when VITS is selected: existing
  provenance and playback must remain stable.
