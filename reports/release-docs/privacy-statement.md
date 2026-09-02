# Privacy and offline statement

`citac-knjiga` is designed to process DRM-free EPUB input and generate Serbian
audio locally. In the documented application path, text, tokens, model data and
audio are not uploaded. Both release variants declare `INTERNET` only for the
configured model-asset download boundary; no analytics or proprietary service is
part of the audited runtime graph.

## Data handling

- The Android document picker supplies a user-selected EPUB or model package;
  the app copies it into private storage before using it. Document import is
  local and does not use the network.
- Direct model acquisition is limited to the two pinned HTTPS GitHub Release
  assets below. It does not accept arbitrary URLs or download documents, code,
  telemetry, or runtime dependencies.
- Generation reads private imported text and installed model artifacts locally;
  it remains offline. Runtime dependency acquisition is a build-time concern
  and remains outside the app runtime.
- The original SAF URI is provenance only. Later EPUB work uses the verified
  private source copy, so a provider disappearing does not require the original
  URI.
- Canonical text, model packages, generated audio, playback state and
  diagnostics are app-private artifacts. Ready files are checksum-verified and
  temporary files are cleaned only by the documented reconciliation policies.
- Export is user initiated to a user-selected SAF destination. The destination
  is outside the app-private boundary and is not treated as private after the
  user exports content.
- Diagnostics and text export use the central redactor. Free-form document
  text, raw exceptions, paths and URIs are not included by default.

## Boundaries and limitations

Offline behavior does not protect data from the Android OS, a compromised
device, root access, backups, or a user-selected external export/player. The
app cannot make claims about other applications after export. A device may
still have network access for unrelated software; the application does not use
that network for document import or narration. The download transport is not
implemented in this task; task 4.3 must retain this allowlist. Privacy checks are
static and test-based, not a privacy certification or a guarantee against future
code changes.
