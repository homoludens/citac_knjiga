## Context

The direct `document-epub` importer already has the correct broad shape: a SAF source is copied to app-private staging, `EpubSecurityValidator` checks the staged ZIP, `EpubDocumentParser` builds the existing document IR, preview happens before acceptance, and `AtomicArtifactStore` publishes private artifacts. This change should strengthen that path rather than introduce a second importer.

The current validator is not production-ready. Its defaults are fixture-sized, exact-limit values are rejected, all entries are collected before inspection, XML payloads are copied into byte arrays, one size and ratio policy covers unlike resources, and package media types do not participate in text classification. It also treats every doctype and `META-INF/encryption.xml` as a hard failure, does not consistently normalize package references against ZIP entry names, and exposes only a small diagnostic which the app currently collapses to a generic security message. The DOM parser then performs its own less explicit XML hardening and unbounded `readBytes()` calls.

The prior MVP design selected `java.util.zip.ZipFile` plus platform XML parsing to keep the importer small and auditable. That remains the implementation base. The existing hostile fixtures and staging cleanup tests remain useful, but their 40-entry, 8 KiB, and 128 KiB triggers are test artifacts rather than production policy.

## Goals / Non-Goals

**Goals:**

- Accept ordinary DRM-free EPUB 2 and EPUB 3 publications at every exact production limit in the capability spec.
- Reject the first value beyond a limit without loading the whole uncompressed archive into memory.
- Apply one path, ZIP, XML, external-resource, DRM, size, ratio, and nesting implementation in strict and compatibility analysis.
- Recover only the three specified compatibility cases in at most one retry and report each recovered issue.
- Carry safe, structured diagnostics through staging, preview, user messaging, and the accepted import warning report.
- Preserve deterministic private staging, cleanup, preview, and publication behavior.
- Evolve `EpubSecurityValidator`, `EpubDocumentParser`, and their current result models with the fewest practical new types.

**Non-Goals:**

- DRM decryption, fetching network resources, broad malformed-publication repair, configurable or user-disabled limits, or a general EPUB reader.
- Decoding obfuscated fonts that are not needed to extract narration, metadata, navigation, or the selected cover.
- Replacing the direct ZIP/XML importer, the document IR, Room schema, SAF boundary, or atomic artifact store.

## Decisions

### 1. Use one immutable production limits profile

Replace the configurable default `EpubSecurityLimits` production surface with one internal immutable `EpubProductionLimits` object. It contains exactly:

| Rule | Inclusive maximum |
|---|---:|
| `archive.source-bytes` | 512 MiB (536,870,912 bytes) |
| `archive.entry-count` | 4,096 central-directory records, including directories |
| `archive.total-uncompressed-bytes` | 1 GiB (1,073,741,824 bytes) |
| `entry.uncompressed-bytes` | 128 MiB (134,217,728 bytes) |
| `resource.xml-text-bytes` | 8 MiB (8,388,608 bytes) |
| `resource.xml-text-total-bytes` | 32 MiB (33,554,432 bytes) |
| `resource.cover-bytes` | 32 MiB (33,554,432 bytes) retained for preview |
| `xml.nesting-depth` | 64 elements |
| `entry.compression-ratio` | 250:1 for each non-directory entry at least 1 MiB uncompressed |
| `archive.compression-ratio` | 100:1 across all non-directory entries |

MiB and GiB use the byte definitions in the spec. Every comparison is `observed > maximum`; equality is accepted. Ratios use integer cross-multiplication rather than rounded `Double` comparisons, with the specified zero rules: non-empty over zero compressed bytes is infinite and empty is zero. This makes any positive amount over 250:1 or 100:1 reject deterministically.

There is no settings entry, constructor parameter, or dependency-injection binding for a different production profile. Tests may feed small synthetic observations directly to internal counter/check functions, but production validation always references this one object. This removes the current risk that fixture thresholds accidentally become runtime defaults.

### 2. Extend the validator into a bounded archive analysis, not a new pipeline

Keep `EpubSecurityValidator.validate(File)` as the security boundary and keep `ZipFile` for entry streams. Internally, each analysis pass has four ordered phases:

1. Check the copied source byte count, then read central-directory and matching local-header metadata into a bounded catalog of at most 4,096 small records. The catalog contains normalized name, directory flag, flags, method, declared compressed/uncompressed sizes, CRC, and offsets, never entry payloads. Reject malformed headers, unknown or inconsistent sizes, unsupported methods, ZIP encryption flags, invalid paths, normalized duplicates, declared entry/total limits, and declared ratios before decompression.
2. Read only the bounded container, package document, and encryption metadata needed to identify the root OPF, manifest media types, selected cover, and encryption declarations. All reads use the same per-entry and XML/text guards and the secure XML path described below. Complete XML/text classification after the manifest is known.
3. Recheck declared XML/text aggregate and selected-cover limits, then stream every non-directory entry through one fixed-size buffer. Per-entry, archive-total, XML/text-total, and retained-cover counters advance with each decompressed chunk and fail as soon as a maximum is exceeded. At end of entry, observed bytes and CRC must agree with ZIP metadata; unknown or inconsistent values are `archive.malformed`. Recompute individual and aggregate ratios from observed uncompressed counts and declared compressed counts. XML and text scanners consume the stream incrementally. Only the one bounded XML document currently being mapped or the selected cover may become a byte array, never the archive as a whole.
4. Return acceptance, a hard rejection, or internal allowlisted compatibility findings. Entry processing follows central-directory order, and checks within a record have a fixed order, so the selected rejection and warning order are reproducible.

An entry is XML/text by the exact extension and manifest-media-type rules in the spec, case-insensitively. This includes `.css` and `.txt`, which the current validator omits. A resource that has multiple roles is checked against every applicable bound: for example, an SVG cover is subject to the 128 MiB entry limit, 8 MiB XML/text resource limit, 32 MiB XML/text aggregate limit, and 32 MiB retained-cover limit. A non-text image cover is subject to the entry and retained-cover limits.

The SAF copy itself uses a counting copy and stops on byte 536,870,913, returning `archive.source-bytes` for attempt 1 and deleting the partial artifact. This avoids first writing an arbitrarily large provider stream. The central-directory reader can be a small private helper in `EpubSecurityValidator`; adding a ZIP library or changing the artifact pipeline is unnecessary.

The existing `EpubSecurityValidation` sealed result gains an accepted value carrying recovered warnings and an internal compatibility-required value used only by the retry controller. `EpubSecurityDiagnostic` is reshaped as described below. Apart from a small private archive-record model and compatibility finding, no parallel validation model is needed.

### 3. Normalize ZIP paths and publication references through one resolver

Use one lexical archive-path function for ZIP names, OPF `full-path`, manifest `href`, spine/navigation targets, encryption `CipherReference`, XML attributes, and CSS references.

- Convert ZIP separators to `/` for comparison, reject empty and NUL-containing names, and reject absolute, `//`/UNC, drive-qualified, or parent-traversing names before use.
- Collapse empty segments and `.`, process `..` only while a prior segment remains, preserve case, and use the result as the catalog key. Two raw names with the same key are `archive.duplicate-entry`.
- For a publication reference, parse it as a URI reference without opening it, discard query and fragment only for archive lookup, percent-decode the path once, reject malformed encoding or backslash ambiguity, and resolve it relative to the normalized containing entry's directory.
- Resolving above the archive root, or producing an absolute, drive-qualified, UNC, or NUL-containing path, is `archive.entry-path`. A local target is looked up only in the normalized catalog; it is never mapped directly to a filesystem path.
- Schemes, authority, protocol-relative references, and resource-bearing external references are handled by the external-resource rules below, not by `File`, `URL`, or a network-capable resolver.

This replaces the separate `Paths`, `URI`, and string-prefix checks currently split between validator and parser. The parser receives normalized catalog names and uses the same resolver for container, manifest, cover, spine, navigation, and local hyperlink targets.

### 4. Parse XML fail-closed and scan all external-resource constructs

Use a namespace-aware SAX parser for streaming validation and configure secure processing, no XInclude, no external general entities, no external parameter entities, and no external DTD loading before parsing any untrusted XML. A resolver must never open a URI. If the platform cannot establish a required feature, validation fails closed as `xml.external-entity` with a safe `parser-hardening-unavailable` token rather than parsing with weaker behavior.

SAX lexical/declaration callbacks classify doctypes and reject every entity declaration, including internal declarations, before expansion. The content handler counts depth, rejects element 65 as `xml.nesting-depth`, recognizes XInclude, and inspects resource-bearing attributes. The retry mode may recognize an allowlisted doctype locally, but its external identifier is supplied an empty local input and is never resolved. Malformed input remains `xml.malformed` in both modes. The DOM builder used by `EpubDocumentParser` must use the same hardened factory/resolver policy; it does not rely solely on validation having run earlier.

Reference inspection distinguishes constructs rather than treating every `href` alike:

- External stylesheet links, `xml-stylesheet`, XInclude, and external `src`, `poster`, `data`, or `action` are hard `resource.external` failures.
- CSS is incrementally inspected for external `@import` and `url()` values. Local values go through the shared archive resolver; external values are hard failures.
- An `a[href]` local reference goes through the shared resolver. An absolute `http`, `https`, or `mailto` value is a compatibility finding; every other scheme, authority, or protocol-relative value is a hard failure.
- URI diagnostics retain only the normalized entry scope, construct such as `img[src]` or `css:url()`, and lower-case scheme or safe category. They never retain the complete URI.

No parser, validator mode, preview mapper, or narrator opens an external URI.

### 5. Run strict analysis, then zero or one allowlisted compatibility pass

`validate` is the retry controller. Attempt 1 always uses strict mode. It records an internal finding when it sees an exact allowlisted case, continues scanning safely so a later hard failure wins, and otherwise applies all hard checks. If there are no findings it accepts immediately. If every finding is allowlisted and no hard failure exists, it runs attempt 2 once over the same staged file.

Attempt 2 calls the same metadata, counter, path, XML, resource, and DRM functions with only three narrowly different branches:

1. Font obfuscation is accepted only when every declaration uses `http://www.idpf.org/2008/embedding` or `http://ns.adobe.com/pdf/enc#RC`, every cipher reference resolves through the shared resolver to an existing local `.otf` or `.ttf` manifest item with media type `application/vnd.ms-opentype`, `application/font-sfnt`, `font/otf`, or `font/ttf`, and there is no `META-INF/rights.xml` or other encryption declaration. A mixture with any unsupported declaration is `publication.drm` on attempt 1, not a compatibility finding.
2. A doctype is accepted only with no internal subset or entity declaration and with root `html` and no identifier, or one exact pair: `-//W3C//DTD XHTML 1.0 Strict//EN` with `http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd`; `-//W3C//DTD XHTML 1.0 Transitional//EN` with `http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd`; `-//W3C//DTD XHTML 1.1//EN` with `http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd`; or `-//NISO//DTD ncx 2005-1//EN` with `http://www.daisy.org/z3986/2005/ncx-2005-1.dtd` and root `ncx`. Whitespace and quote syntax may vary; identifier values may not.
3. An external hyperlink is accepted only for absolute `http`, `https`, or `mailto` on `a[href]`, remains a reference in imported content, and is never fetched by import or narration.

All findings may be handled in that one pass. A changed, new, or non-allowlisted issue rejects; there is no third pass. Every production limit and hard check runs again on attempt 2. A hard failure on attempt 1 reports attempt 1 and prevents retry; a hard failure on attempt 2 reports attempt 2 and prevents further retry.

For supported font obfuscation, validation needs only encryption metadata, manifest metadata, normalized paths, and entry existence. The narration importer does not render with embedded fonts, so it leaves font payloads obfuscated and does not read or decode them beyond the ordinary streaming size/CRC pass. This avoids unnecessary memory and cryptographic code while satisfying the allowlist.

For a recovered external hyperlink, the parser preserves the original absolute `href` and source locator as a non-fetching reference attached to the existing document content. This needs at most one small hyperlink value type and an optional list on the relevant narration block; diagnostics receive only scheme and construct. Canonical preview may render the link reference, while narration consumes its visible anchor text only and no import or narration code dereferences the URI.

The accepted validation result is tied to the staged source fingerprint and carries attempt-2 warnings. `StagedEpubSource` carries that result into `EpubDocumentParser`, so one preview/import operation does not independently launch another retry sequence. The parser verifies private location, size, and fingerprint before consuming it. A later independent parse of an already imported source may run a fresh bounded validation, but each invocation still has exactly one strict attempt and at most one compatibility attempt.

### 6. Make diagnostics structured and map them once to user messages

Reshape `EpubSecurityDiagnostic` to contain:

- scope: `publication` or normalized archive entry name;
- stable rule identifier;
- safe observed value plus unit, or safe categorical fields;
- exact allowed numeric limit or allowlisted condition;
- disposition `NON_RETRYABLE_SECURITY_REJECTION` or `RECOVERED_COMPATIBILITY_WARNING`;
- attempt `1` or `2`;
- for ratios, uncompressed bytes, compressed bytes, and computed ratio;
- for URI findings, construct and scheme/category without the full URI.

Use the exact limit and non-limit rule identifiers listed in the spec. Internal enums may expose those strings, but persistence and UI tests assert the stable string values. Numeric values are kept as numbers plus units rather than preformatted text. Compatibility findings from attempt 1 are internal only; after attempt 2 succeeds, one `RECOVERED_COMPATIBILITY_WARNING` with attempt 2 is emitted per recovered finding.

Keep `EpubImportError` as the coarse workflow error. Add the diagnostic or warning list to the existing stage, parse, and preview results rather than introducing a second error hierarchy. A single formatter in the app maps rule plus disposition to localized Serbian and English text, then appends the safe scope/observed/allowed details. For example, `resource.xml-text-bytes` explains that `OPS/chapter.xhtml` is 8,388,609 bytes and the limit is 8,388,608 bytes. Recovered warnings appear in preview and are appended to the existing accepted import warning report. The formatter never displays source text or complete URIs.

### 7. Preserve deterministic staging, cleanup, and publication

All source copying, both validation attempts, package parsing, canonical preview generation, and diagnostic generation happen under the existing owner-specific app-private staging path. The behavior is:

1. Copy with the source counter and fingerprint calculation.
2. Validate once, including the optional compatibility pass, and bind acceptance to the fingerprint.
3. Parse and build the preview from the accepted staged source.
4. On reject, exception, cancellation, duplicate detection, or explicit discard, close ZIP/XML streams and delete all files for that staging owner. Do not create a source document, canonical file, cover, warning report, or Room row.
5. On acceptance, prepare and validate source, canonical text, selected cover, and diagnostics as temporary artifacts; atomically publish only to new project-ID paths; then record the ready projection in one Room transaction. If any step fails, delete only newly created files for that project and leave existing projects untouched. Staging is deleted after success or terminal failure.

No archive entry is extracted to a path derived from its name. Generated project IDs continue to define all filesystem destinations. Process-death reconciliation may delete unreferenced owner staging and incomplete new-project artifacts, but never an existing ready project or a file outside app-private storage.

### 8. Qualify behavior with representative fixtures and exact boundaries

Keep the compact MVP and hostile fixtures, and add deterministic, redistributable fixtures representing normal production books: EPUB 2 and EPUB 3 text-and-image publications with realistic entry counts, multi-megabyte images, CSS, navigation, nested local paths, and mixed manifest media types. Add focused compatibility fixtures for each exact doctype pair, multiple allowlisted issues in one retry, IDPF/Adobe font obfuscation, and retained external hyperlinks. Add mixed fixtures proving that an allowlisted issue plus traversal, entity declaration, unsupported encryption, external payload, or expansion failure never retries successfully.

Boundary tests cover every production maximum exactly and the first value beyond it, including source bytes, directory-inclusive record count, declared and streamed entry/aggregate bytes, separate XML/text and cover bounds, depth 64/65, the 1 MiB individual-ratio threshold, 250:1 plus any positive amount, aggregate 100:1 plus any positive amount, empty entries, and non-empty entries with zero compressed bytes. Small generated metadata/counter tests exercise large numeric observations without committing gigabyte archives; integration fixtures still pass through `ZipFile`, streaming XML/CSS inspection, staging cleanup, preview, and Android instrumentation. Tests also assert normalized duplicates and traversal variants, percent-encoded reference traversal, malformed/unknown/inconsistent ZIP sizes, encrypted flags, no resolver/network calls, URI redaction, diagnostic attempt/disposition, warning order, and no publication after rejection.

## Risks / Trade-offs

- [A small ZIP metadata reader adds low-level code] -> Limit it to bounded central-directory/local-header facts that `ZipEntry` does not expose reliably, especially encryption flags and consistency, and keep decompression in `ZipFile`.
- [A compatibility retry can decompress a valid archive twice] -> It occurs only for three allowlisted legacy cases; each pass independently enforces all finite limits and uses fixed buffers.
- [DOM mapping still retains one XML document at a time] -> The 8 MiB per-resource and 32 MiB aggregate text limits bound this existing behavior; validation itself remains streaming and no whole archive is retained.
- [A 512 MiB source and 1 GiB expansion can still take meaningful time and storage on a phone] -> Stop at the first exceeded streaming counter, close resources promptly, retain no extracted archive tree, and keep preview work cancellable under the existing staging owner.
- [Conservative CSS and URI handling may reject obscure but benign publications] -> Keep accepted compatibility exactly to the spec rather than adding repair heuristics or network-capable parsers.
- [Publishing several files cannot be one filesystem transaction] -> Publish only new project-ID paths, keep Room visibility as the final transaction, and deterministically reconcile or delete orphaned new-project files.

## Migration Plan

1. Replace fixture defaults and old diagnostic fields in place while retaining the existing SAF, preview, parser, IR, storage, and Room boundaries.
2. Run old hostile/recovery tests, new representative fixtures, exact-boundary tests, and Android instrumentation before release.
3. No persisted-data or Room migration is expected. Existing accepted source files, canonical text, projects, and fingerprints keep their formats.
4. On first run, ordinary stale-staging reconciliation may remove abandoned temporary files from interrupted imports; ready projects are not revalidated or rewritten automatically.
5. Rollback is an application-code rollback. Because no schema or persisted format changes, the prior version can continue to open existing projects; it may again reject newly selected real-world EPUBs, but it does not need data conversion. Incomplete staging from either version remains safe to delete.
