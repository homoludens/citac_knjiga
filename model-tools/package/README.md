# Model Package Schema v1

`model-package-v1.schema.json` defines the manifest for an independently
distributed Serbian Kokoro package. `model-package-v1.example.json` is a
blocked, declaration-only fixture for the current Dragana epoch-005 ONNX
candidate. It does not create the payload files or an archive.

## Contract

- `schema` is `serbian-model-package` version `1`; core properties are strict.
  Future additive data must use a namespaced root `extensions` key. A reader may
  ignore extensions, but must reject an unknown core property or newer schema
  version. Semantic changes require a new schema version; package patch/minor
  versions do not change the schema.
- `manifest.identity` is SHA-256 over canonical UTF-8 JSON with sorted keys and
  no whitespace containing `package_id`, `package_version`, and sorted
  `{path, sha256}` artifact pairs. The manifest file itself is not an artifact,
  avoiding a self-checksum cycle. Paths are package-relative and each payload
  artifact has a lowercase SHA-256 and byte size.
- `model`, `voice_style`, `vocabulary`, and `configuration` identify the ONNX
  tensor boundary, the 510-row/256-value style table, vocabulary behavior,
  speed/randomness, and 24 kHz mono output. `runtime` pins ONNX Runtime Android
  1.29.0, arm64 API 30+, CPU threads 1/1, and parity status separately from
  device qualification.
- `preprocessing` records the exact `kokoro_sr` revision, source-file hashes,
  eSpeak-NG command/version, normalization contract, vocabulary contract, and
  507 operational/510 hard phoneme limits. Android compatibility remains an
  explicit status, not an assumption.
- `test_vectors` references the machine-readable vector artifact and each
  required golden WAV artifact. It pins `fp32-parity-v1` and requires exact
  preprocessing references without adding the later corpus expansion tasks.
- Every artifact declares license and attribution references. The fixture
  records Dragana / Darko Milošević under CC BY 4.0, Južne vesti provenance
  under the declared CC BY-SA 4.0 terms, and synthetic-audio disclosure.

The required root sections are `schema`, `manifest`, `artifacts`, `model`,
`voice_style`, `vocabulary`, `configuration`, `preprocessing`, `runtime`,
`test_vectors`, `licenses`, `attribution`, and `legal`. Optional fields include
publisher/source provenance, runtime artifact provenance, preprocessing input
limits, artifact descriptions, license modification notes, and namespaced
extensions. Required artifact fields always include role, relative path, media
type, byte size, SHA-256, distribution status, and license/attribution refs.

## Legal Safety

The legal object is deliberately fail-closed. The fixture has
`status: blocked`, `model_distribution: blocked`, private-development-only
distribution, blocked model/voice/derived-audio artifacts, and outstanding
Južne vesti DUA/broadcast-rights reviews. A schema-valid manifest is **not** a
legal opinion, permission, or release approval. Setting `model_distribution` to
`allowed` additionally requires `status: cleared`, evidence text, and allowed
artifact declarations, but those fields still require human/legal review.

## Validation

Run from the repository root:

```sh
model-tools/.venv/bin/python model-tools/scripts/validate_model_package_manifest.py
model-tools/.venv/bin/pytest model-tools/tests/test_model_package_manifest.py
```

The validator checks JSON Schema, cross-references, required artifact roles,
legal gate consistency, unique package paths/IDs, and the manifest identity.
It does not hash files, create packages, import packages, or establish legal
clearance. Those behaviors belong to task 3.2 and later gates.
