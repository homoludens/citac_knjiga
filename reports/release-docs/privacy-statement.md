# Privacy and offline statement

`citac-knjiga` is designed to process DRM-free EPUB input and generate Serbian
audio locally. In the documented application path, text, tokens, model data and
audio are not uploaded. The release and F-Droid manifests are checked for no
routine network permission, and no analytics or proprietary service is part of
the audited runtime graph.

## Data handling

- The Android document picker supplies a user-selected EPUB or model package;
  the app copies it into private storage before using it.
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
that network for narration. Privacy checks are static and test-based, not a
privacy certification or a guarantee against future code changes.
