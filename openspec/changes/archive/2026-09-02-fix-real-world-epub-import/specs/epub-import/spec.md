## Purpose

Define safe production import of ordinary DRM-free EPUB publications with finite mobile-friendly resource bounds, actionable diagnostics, and narrowly controlled compatibility recovery.

## ADDED Requirements

### Requirement: Bounded production acceptance profile
The importer SHALL accept an otherwise valid EPUB at every exact limit below and MUST reject it when any limit is exceeded:

- source ZIP size: 512 MiB;
- ZIP central-directory records, including directory records: 4,096;
- sum of declared and actually streamed uncompressed entry bytes: 1 GiB;
- one entry's uncompressed bytes: 128 MiB;
- one XML/text resource's uncompressed bytes: 8 MiB;
- aggregate XML/text resource bytes: 32 MiB;
- selected cover bytes retained for preview: 32 MiB; and
- XML element nesting depth: 64.

One MiB SHALL equal 1,048,576 bytes and one GiB SHALL equal 1,073,741,824 bytes. An entry SHALL count as XML/text when its case-insensitive name ends in `.xml`, `.opf`, `.ncx`, `.xhtml`, `.html`, `.htm`, `.svg`, `.css`, or `.txt`, or its manifest media type starts with `text/`, ends with `+xml`, or equals `application/xml`, `application/xhtml+xml`, `application/oebps-package+xml`, or `application/x-dtbncx+xml`. Both declared ZIP sizes and bytes observed while decompressing MUST be checked, unknown or inconsistent sizes MUST be rejected as malformed, and the importer MUST NOT materialize the whole uncompressed archive in memory.

For every non-directory entry of at least 1 MiB uncompressed, the uncompressed-to-compressed ratio MUST NOT exceed 250:1. Across all non-directory entries, the ratio of total uncompressed bytes to total compressed bytes MUST NOT exceed 100:1. A non-empty entry with zero compressed bytes has an infinite ratio; an empty entry has ratio zero.

#### Scenario: Ordinary text-and-image EPUB remains within the profile
- **WHEN** an otherwise valid EPUB has values at or below every production limit, including values exactly equal to a limit
- **THEN** the importer accepts it without a size, count, nesting, or compression-limit diagnostic

#### Scenario: First value beyond a production limit is rejected
- **WHEN** declared metadata or streamed data exceeds any production limit by one byte, one record, one nesting level, or any positive ratio amount
- **THEN** import stops with the diagnostic for that specific exceeded rule and no source or project is published

### Requirement: Mandatory archive and publication security rejection
In every import attempt the importer MUST reject absolute, drive-qualified, UNC, NUL-containing, or parent-traversing ZIP entry names; duplicate normalized entry names; publication references that resolve outside the archive root; malformed ZIP metadata; ZIP-encrypted entries; unsupported encryption or DRM; and decompression beyond any production limit. `META-INF/rights.xml` and encryption declarations MUST be treated as unsupported DRM except for the exact supported font-obfuscation case defined below. These failures MUST be non-retryable, temporary data MUST be removed, and no existing project or file outside app-private staging may be changed.

#### Scenario: Traversal remains a hard failure
- **WHEN** an EPUB contains `../outside`, `nested/../../outside`, an absolute or drive-qualified entry, or an internal reference that resolves above the archive root
- **THEN** import is rejected as `archive.entry-path` with disposition `NON_RETRYABLE_SECURITY_REJECTION` and no compatibility retry occurs

#### Scenario: Encryption or expansion remains a hard failure
- **WHEN** an entry is ZIP-encrypted, DRM-protected, expands past an applicable byte limit, or exceeds an applicable compression ratio
- **THEN** import is rejected under the matching encryption, DRM, size, or ratio rule and no compatibility retry occurs

### Requirement: XML and external-resource isolation
The importer MUST parse XML without resolving external entities or fetching any URI. Any `<!ENTITY` declaration, unlisted doctype, external entity, XInclude, external stylesheet, external CSS `@import` or `url()`, or external resource-bearing reference such as `src`, `poster`, `data`, or `action` MUST cause a non-retryable security rejection. Malformed XML and XML deeper than 64 elements MUST also be rejected. No mode or retry may globally disable entity, network, path, size, ratio, or nesting protections.

#### Scenario: External entity is never resolved
- **WHEN** an XML resource declares an external entity or a doctype other than the compatibility allowlist
- **THEN** import is rejected as `xml.external-entity` or `xml.doctype` before any local file or network access occurs

#### Scenario: External payload reference is never fetched
- **WHEN** publication content references an external payload through a resource-bearing attribute, stylesheet, XInclude, or CSS construct
- **THEN** import is rejected as `resource.external` and the referenced URI is never opened

### Requirement: Single bounded compatibility retry
After the initial strict attempt, the importer SHALL perform zero or one compatibility retry, and only when every triggering issue belongs to the following allowlist. The retry MUST reapply every production limit and mandatory security check.

- Font obfuscation is allowed only when every encryption declaration uses `http://www.idpf.org/2008/embedding` or `http://ns.adobe.com/pdf/enc#RC`, every cipher reference resolves to an existing local `.otf` or `.ttf` manifest font with media type `application/vnd.ms-opentype`, `application/font-sfnt`, `font/otf`, or `font/ttf`, and no `META-INF/rights.xml` or other encryption declaration exists.
- A doctype is allowed only with no internal subset and no entity declaration, and only when its root is `html` with no identifier or it uses one exact public/system pair: `-//W3C//DTD XHTML 1.0 Strict//EN` and `http://www.w3.org/TR/xhtml1/DTD/xhtml1-strict.dtd`; `-//W3C//DTD XHTML 1.0 Transitional//EN` and `http://www.w3.org/TR/xhtml1/DTD/xhtml1-transitional.dtd`; `-//W3C//DTD XHTML 1.1//EN` and `http://www.w3.org/TR/xhtml11/DTD/xhtml11.dtd`; or `-//NISO//DTD ncx 2005-1//EN` and `http://www.daisy.org/z3986/2005/ncx-2005-1.dtd` with root `ncx`. XML whitespace and quote style MAY vary, but identifier values MUST match exactly. The external identifier MUST be recognized locally and never resolved or fetched.
- An external hyperlink is allowed only as an `href` on an `a` element with an absolute `http`, `https`, or `mailto` URI. It SHALL remain in imported content as a reference but MUST never be fetched during import or narration.

All other encryption, doctypes, URI schemes, protocol-relative references, and external references MUST remain hard failures. Multiple allowlisted issues MAY be handled together in the single retry; the importer MUST NOT start a second retry or expose a validation-disable control.

#### Scenario: Allowlisted legacy publication succeeds once
- **WHEN** the initial attempt encounters only one or more allowlisted font-obfuscation, doctype, or external-hyperlink issues and the publication passes all checks during one compatibility retry
- **THEN** import succeeds with one `RECOVERED_COMPATIBILITY_WARNING` diagnostic per recovered issue and no external resource is fetched

#### Scenario: Compatibility cannot bypass a hard failure
- **WHEN** an allowlisted issue appears together with traversal, an external entity, unsupported encryption, decompression beyond a limit, or another non-allowlisted issue
- **THEN** import is rejected with the hard-failure diagnostic without a further retry

### Requirement: Precise import diagnostics
Every import rejection and recovered compatibility warning SHALL provide a structured diagnostic containing: affected scope as `publication` or the normalized archive entry name; a stable rule identifier; the observed value and unit or a safe categorical token; the exact allowed limit or allowlisted condition; disposition `NON_RETRYABLE_SECURITY_REJECTION` or `RECOVERED_COMPATIBILITY_WARNING`; and attempt number `1` or `2`. Ratio diagnostics MUST include uncompressed and compressed byte counts as well as the computed ratio. URI diagnostics MUST identify the element/attribute or CSS construct and scheme without exposing the complete URI. Limit rule identifiers SHALL distinguish `archive.source-bytes`, `archive.entry-count`, `archive.total-uncompressed-bytes`, `entry.uncompressed-bytes`, `resource.xml-text-bytes`, `resource.xml-text-total-bytes`, `resource.cover-bytes`, `entry.compression-ratio`, `archive.compression-ratio`, and `xml.nesting-depth`. Other required identifiers SHALL be `archive.entry-path`, `archive.duplicate-entry`, `archive.malformed`, `archive.encrypted-entry`, `publication.drm`, `xml.malformed`, `xml.external-entity`, `xml.doctype`, `resource.external`, `compat.font-obfuscation`, `compat.doctype`, and `compat.external-hyperlink`.

#### Scenario: User receives an actionable limit failure
- **WHEN** `OPS/chapter.xhtml` expands to 8,388,609 bytes
- **THEN** the diagnostic identifies `OPS/chapter.xhtml`, rule `resource.xml-text-bytes`, observed `8388609 bytes`, allowed `8388608 bytes`, disposition `NON_RETRYABLE_SECURITY_REJECTION`, and attempt `1`

#### Scenario: Recovered hyperlink is reported without leaking its URI
- **WHEN** an allowlisted HTTPS hyperlink is retained after the compatibility retry
- **THEN** its warning identifies the affected entry, rule `compat.external-hyperlink`, observed scheme `https` and construct `a[href]`, the allowlisted schemes, disposition `RECOVERED_COMPATIBILITY_WARNING`, and attempt `2` without containing the complete URI
