# EPUB Importer Decision (task 7.3)

## Scope and method

Task 7.1's 11 committed fixtures were validated, then exercised by the
Readium 3.1.0 control from `readium-spike/` and by the disposable
stdlib-only parser in `epub-direct-spike/`. The direct parser follows the
container rootfile, OPF manifest, declared spine, and OPF-relative hrefs. It
reports parseable content and security markers but does not enforce security
limits. The EPUB2 double `OEBPS/` target is retained as authored evidence, not
silently repaired.

## Fixture results

| Fixture | Readium 3.1.0 | Direct parser | Objective comparison |
|---|---|---|---|
| EPUB2 valid | metadata, cover, spine `zeta, alpha`, NCX `Други, Први`; XHTML reads were unavailable | same metadata/cover/spine/NCX; 0/2 XHTML targets resolve | both preserve declared order; both expose the fixture's literal OPF-relative href behavior |
| EPUB3 valid | metadata, cover, spine `b, a`, nav `Б, А`; 2/2 XHTML resources read with `<h1>`/`<p>` and expected text | same metadata/cover/spine/nav; 2/2 XHTML parse, headings and body text match | parity on requested publication fields and basic content fidelity |
| malformed content | lazy open, no warning; no content recovery claim | 1/2 spine XHTML parse; malformed item warning | direct parser provides bounded per-item diagnostic; Readium open alone does not validate the XHTML |
| malformed navigation | open fails with `Assertion failed` | spine content parses; empty TOC plus warning | direct parser has the required recoverable-content shape for this fixture |
| 7 attack fixtures | not exercised as security enforcement by the control | all 7 markers observed: Zip Slip, ratio/size, count, DTD/entity, external URI, encryption | neither experiment proves enforcement; task 7.5 remains the security gate |

The direct parser's content result includes heading, list-item, note, and
poetry counters. The EPUB2 nested heading/list/note/poetry content is
unreachable through its declared spine hrefs, so neither candidate is credited
with that content result. EPUB3 supplies the content-fidelity control with two
parsed headings and exact body text. No recovery or security behavior is
inferred from a successful Readium publication open.

## Cost and buildability

| Candidate | Added production dependency/artifact | Source/build evidence | F-Droid implication |
|---|---:|---|---|
| Direct parser experiment | 0; Python stdlib only, 8,861-byte parser script at this revision | `py_compile` and four unittest cases pass; Android port is deferred | no third-party license or network surface; platform APIs still need task 7.4/7.5 tests |
| Readium 3.1.0 | 16 runtime artifacts, 10,117,675 compressed bytes; direct AAR subtotal 1,802,707 | consumer test/build passes on compile SDK 35; 3.3.0 source build is proven but requires compile SDK 36/toolchain upgrade | BSD-3-Clause, no native code/permissions in tested AARs; transitive Koi and Kotlin reflection increase audit/size |

Readium's measured artifact numbers are copied from the task-7.2 run in
`readium-spike/README.md`. The direct parser's 8,861 bytes are disposable
source, not an Android artifact; its Android port has not been built yet.

## Selection

**Select a direct platform parser for task 7.4.** It meets the measured EPUB2/3
metadata, cover, declared spine, navigation, basic EPUB3 content, and
recoverable malformed-content needs with no production dependency, materially
less artifact size, and a simpler F-Droid audit surface. Readium's richer
general-purpose API does not offset its 10 MiB resolved classpath for this
extraction-only MVP, and its newer line requires a compile SDK/toolchain change.

This decision does not authorize production SAF import, structured IR mapping,
canonical Markdown, preview, or security enforcement. Task 7.4 should port the
minimum approach to `java.util.zip.ZipFile` and platform XML parsing. Task 7.5
must separately add and test containment, size/ratio/count, encryption, XML,
and external-resource enforcement before accepting untrusted EPUBs.
