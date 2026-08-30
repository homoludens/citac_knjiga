# PDF qualification consumer

This is a disposable, stdlib-only qualification consumer. It is deliberately
not included in `settings.gradle.kts` and has no AndroidX PDF or PDFBox
dependency. The candidate records document the adapters that must be tested;
the platform `PdfRenderer` candidate is rejected because it renders pixels and
does not provide text spans or block geometry.

Run from the repository root:

```sh
python3 pdf-qualification/qualify.py
python3 scripts/check_pdf_qualification.py
```

The checked-in report is binary `no-pass`. API 35 is recorded as an executed
negative gate; API 30 and API 36 are recorded as unavailable in this checkout.
No candidate is selected, and `document-pdf` therefore exposes only the
fail-closed unavailable adapter. Generated fixture files and matrix output are
written outside the repository unless `--output` is explicitly supplied.
