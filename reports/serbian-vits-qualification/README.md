# Serbian VITS Qualification

The canonical candidate is `daremc86/sr-cv-vits` at commit
`83dc1e1b95d85b9f5602dc94909706fc83dfbc6c`, speaker Dragana (`0`), native
22,050 Hz, and downstream 24,000 Hz mono.

The recorded outcome remains **REJECTED**, but the legal/source gate is now
**ALLOWED** based on project-maintainer confirmation, and the pinned conversion
and graph inspection are **PASS**. The overall result remains rejected because
production Android Sherpa generation, parity, Serbian quality, and the API 33
device evidence are still pending. The validated VITS package remains external;
no checkpoint, generated audio, source text, or executable qualification payload
is stored here. The existing Kokoro package contract, default, preference, Room
schema, audio, and provenance remain unchanged.

Validation from the repository root:

```sh
model-tools/.venv/bin/python model-tools/scripts/validate_serbian_vits_qualification.py
model-tools/.venv/bin/python model-tools/scripts/check_serbian_vits_evidence.py
model-tools/.venv/bin/python -m pytest model-tools/tests/test_serbian_vits_qualification.py
```

The exact revision has been fetched into a disposable desktop directory and
converted to the separate `serbian-vits-model-package:1` package after legal
clearance. Android installation is offline and fail-closed; raw checkpoints,
PyTorch files, converter sources, scripts, sidecars, and undeclared package
entries are forbidden. Failed imports or generation retain the last valid
Kokoro package and publish no audio. The remaining full device qualification
gate must run on API
33 native `arm64-v8a` before VITS is exposed as usable.
