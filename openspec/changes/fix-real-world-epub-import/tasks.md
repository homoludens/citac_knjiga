## 1. Immutable Limits And Source Staging

- [ ] 1.1 Replace configurable production security defaults with one immutable `EpubProductionLimits` profile containing the specified inclusive source, entry-count, expansion, entry, XML/text, cover, nesting, and ratio maxima; verify a limits unit test asserts all values and no production constructor or settings path accepts an alternate profile.
- [ ] 1.2 Implement checked counters and integer ratio comparisons for declared and streamed bytes, including equality acceptance, first-over-limit rejection, the 1 MiB individual-ratio threshold, and zero-compressed-byte rules; verify `EpubProductionLimitsTest` covers exact and first-beyond-boundary observations without allocating large archives.
- [ ] 1.3 Make SAF staging copy through a counting stream that stops at the first byte beyond the source limit while calculating the fingerprint and deleting the partial source; verify `EpubSourceRepositoryTest` observes the source diagnostic, deleted temporary file, and unchanged publication state.

## 2. Bounded ZIP Catalog And Paths

- [ ] 2.1 Add a bounded central-directory/local-header catalog containing only normalized names and ZIP metadata, stopping at the inclusive record limit before retaining another record; verify `EpubSecurityValidatorTest` accepts 4,096 records and rejects record 4,097 without reading entry payloads.
- [ ] 2.2 Validate ZIP methods, flags, offsets, declared sizes, CRC metadata, and local-header consistency before decompression, reporting malformed archives and encrypted entries deterministically; verify generated malformed and encrypted archives produce `archive.malformed` or `archive.encrypted-entry` on attempt 1.
- [ ] 2.3 Implement one lexical archive/reference resolver for entry names and publication references, covering separator normalization, duplicate keys, traversal variants, percent decoding, query/fragment lookup handling, and rejection of absolute, drive, UNC, NUL, malformed, or above-root paths; verify resolver tests assert normalized local targets and `archive.entry-path` or `archive.duplicate-entry` diagnostics without filesystem resolution.
- [ ] 2.4 Stream catalog entries through a fixed buffer and enforce declared plus observed per-entry, archive-total, XML/text-total, and retained-cover counters, CRC agreement, and individual/aggregate ratios in central-directory order; verify validator tests stop on the first exceeded streamed bound and never materialize the archive.
- [ ] 2.5 Classify XML/text resources by the specified case-insensitive extensions and manifest media types, then identify the package, manifest, selected cover, and encryption metadata through bounded reads using the same guards; verify EPUB 2, EPUB 3, CSS, TXT, SVG, and mixed-role cover cases apply every applicable limit.

## 3. Secure XML, CSS, And Reference Validation

- [ ] 3.1 Configure the streaming XML validator with namespace awareness, secure processing, disabled external entities/DTDs/XInclude, and a resolver that cannot open URIs, failing closed when required hardening is unavailable; verify XML security tests observe no resolver invocation for hostile documents.
- [ ] 3.2 Scan declarations and element depth before expansion, reject entity declarations, disallowed doctypes, malformed XML, and depth 65 while accepting depth 64 for otherwise valid content; verify `EpubSecurityValidatorTest` asserts the stable XML rule identifiers and attempt 1 disposition.
- [ ] 3.3 Incrementally inspect CSS and XML references, resolve local references only through the shared catalog, and reject external stylesheets, XInclude, CSS imports/URLs, and resource-bearing external attributes while classifying hyperlink schemes separately; verify reference tests observe unopened external values and redacted URI diagnostics.

## 4. Strict Validation And One Compatibility Retry

- [ ] 4.1 Implement the validation controller so every import starts in strict mode, hard failures win over compatibility findings, and only zero or one retry can run with all limits and security checks reapplied; verify retry-control tests assert no retry for mixed hard failures and no third analysis pass or bypass configuration exists.
- [ ] 4.2 Implement the exact font-obfuscation compatibility predicate, requiring only the supported declarations and local manifest font targets while preserving obfuscated payloads without decoding them; verify IDPF, Adobe, mixed, missing-target, unsupported-font, and DRM-marker fixtures distinguish `compat.font-obfuscation` from `publication.drm`.
- [ ] 4.3 Implement the exact allowlisted doctype handling and `a[href]` external-hyperlink recovery, retaining hyperlinks as non-fetching references and treating all other doctypes, schemes, authorities, and external references as hard failures; verify one combined compatibility fixture succeeds on attempt 2 with one warning per issue and no external value is opened.

## 5. Structured Diagnostics And UI Mapping

- [ ] 5.1 Reshape `EpubSecurityDiagnostic` to carry normalized scope, stable rule, numeric or categorical observed value, exact limit or allowlist condition, disposition, attempt, ratio fields, and redacted URI construct/scheme fields; verify diagnostic serialization/unit tests cover every required rule identifier and reject complete URI or source-text leakage.
- [ ] 5.2 Carry rejection diagnostics and recovered warnings through source staging, parse, and preview results, converting internal attempt-1 compatibility findings into attempt-2 warnings in deterministic order; verify preview/result tests assert warning order, attempt/disposition values, and no warning report on rejection.
- [ ] 5.3 Add the single Serbian/English formatter mapping stable rules and dispositions to actionable messages with safe scope, observed, and allowed details; verify app unit tests render the XML-size example and compatibility warning without exposing source content or a complete URI.

## 6. Parser Integration And Publication Cleanup

- [ ] 6.1 Update `EpubDocumentParser` to consume the accepted staged validation result, normalized catalog references, bounded XML documents, and selected cover while using the same fail-closed DOM parser policy; verify parser tests accept valid spine/package references and reject an independently malformed or hardened-parser-invalid document.
- [ ] 6.2 Preserve recovered absolute hyperlinks and their source locator in the existing document representation while narration consumes only visible anchor text; verify parser and canonical-text tests observe the reference value and no URI dereference during import or narration preparation.
- [ ] 6.3 Bind accepted validation to the staged source size and fingerprint so preview and final import reuse one strict-plus-optional-retry result, while a separate parse validates its own source invocation; verify source integration tests detect replacement or fingerprint mismatch before ZIP consumption.
- [ ] 6.4 Preserve owner-scoped cleanup for rejection, exceptions, cancellation, duplicates, discard, and process interruption, and publish only newly named project artifacts before the final Room transaction; verify repository integration tests find no staged/source/canonical/cover/report/row leftovers and confirm existing projects and outside files are unchanged.

## 7. Fixtures And Boundary Coverage

- [ ] 7.1 Add deterministic redistributable EPUB 2 and EPUB 3 text-and-image fixtures with realistic entries, multi-megabyte images, CSS, navigation, nested paths, and mixed manifest media types; verify `EpubSecurityValidatorTest` and parser tests accept both and preserve declared spine order.
- [ ] 7.2 Add focused compatibility fixtures for every allowlisted doctype pair, IDPF/Adobe font obfuscation, multiple findings in one retry, and retained HTTP/HTTPS/mailto hyperlinks; verify each fixture produces the expected attempt-2 warning set and no external value is opened.
- [ ] 7.3 Extend hostile fixtures for normalized duplicates, encoded traversal, malformed/unknown/inconsistent ZIP metadata, encrypted flags, unsupported DRM, entity declarations, expansion, external payloads, and allowlisted-plus-hard-failure combinations; verify adversarial tests reject the hard rule on attempt 1 and publish nothing.
- [ ] 7.4 Add small generated boundary tests for every exact and first-beyond limit, including source bytes, directory-inclusive count, declared/streamed totals, XML/text and cover roles, depth, ratio threshold, aggregate ratio, empty entries, and non-empty zero-compressed entries; verify the boundary test suite asserts equality acceptance and deterministic first failure.
- [ ] 7.5 Run the complete JVM EPUB validation, parser, preview, canonical-text, and source-repository suites together with the new fixtures; verify `./gradlew :document-epub:testDebugUnitTest` passes and the resulting diagnostics and cleanup assertions cover the integrated path.

## 8. Android Instrumentation And Release Checks

- [ ] 8.1 Extend document-EPUB instrumentation to copy representative and hostile assets through SAF, validate private staging, verify cleanup and no publication after rejection, and parse accepted chapters on a device; verify `./gradlew :document-epub:connectedDebugAndroidTest` passes with outside-file and temporary-directory assertions.
- [ ] 8.2 Add app instrumentation for preview acceptance, localized diagnostic mapping, recovered-warning display, and final atomic publication without changing existing project state on failure; verify `./gradlew :app:connectedStandardDebugAndroidTest` passes for the EPUB import scenarios.
- [ ] 8.3 Run the release gate for affected modules, including unit tests, lint, debug compilation of instrumentation tests, and release assembly; verify `./gradlew :document-epub:testDebugUnitTest :document-epub:lintDebug :document-epub:assembleRelease :app:lintStandardDebug :app:assembleRelease` completes successfully with no security-disable or network-loading surface introduced.
