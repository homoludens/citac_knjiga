# PDF parser notices

The production PDF parser uses `com.tom-roush:pdfbox-android:2.0.27.0`, an
Android port of Apache PDFBox, under the Apache License 2.0.

PdfBox's runtime graph includes Bouncy Castle `bcprov-jdk15to18`,
`bcpkix-jdk15to18`, and `bcutil-jdk15to18`, all pinned to `1.72`. Bouncy Castle
is distributed under its MIT-like license. The license text is available at
<https://www.bouncycastle.org/licence.html>.

The checked-in artifact hashes, upstream references, and local-only resource
policy are in `pdfbox-source-closure.json`. The release bundle also includes
`app/src/main/assets/notices/PDFBOX-NOTICE.md`.
