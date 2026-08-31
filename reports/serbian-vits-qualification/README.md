# Serbian VITS Qualification

The canonical candidate is `daremc86/sr-cv-vits` at commit
`83dc1e1b95d85b9f5602dc94909706fc83dfbc6c`, speaker Dragana (`0`), native
22,050 Hz, and downstream 24,000 Hz mono.

The recorded outcome is **REJECTED**. The legal/source record is `BLOCKED`,
conversion and parity evidence are `UNRESOLVED`, and API 30, API 35
`arm64-v8a`, and API 36 production targets are unavailable. No VITS package,
checkpoint, generated audio, source text, or executable qualification payload
is stored here. The existing Kokoro package contract, default, preference, Room
schema, audio, and provenance remain unchanged.

Validation from the repository root:

```sh
model-tools/.venv/bin/python model-tools/scripts/validate_serbian_vits_qualification.py
model-tools/.venv/bin/python model-tools/scripts/check_serbian_vits_evidence.py
model-tools/.venv/bin/python -m pytest model-tools/tests/test_serbian_vits_qualification.py
```

Future work must fetch the exact revision into a disposable desktop directory,
produce the separate `serbian-vits-model-package:1` package only after legal
clearance, and rerun every gate in order. Android installation is offline and
fail-closed; raw checkpoints, PyTorch files, converter sources, scripts,
sidecars, and undeclared package entries are forbidden. Failed imports or
generation retain the last valid Kokoro package and publish no audio.
