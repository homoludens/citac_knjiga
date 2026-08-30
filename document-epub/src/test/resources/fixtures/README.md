# EPUB Import Fixtures

This directory contains compact EPUB 2 and EPUB 3 archives for the document
importer vertical slice. They are deliberately self-authored and redistributable
under CC0-1.0. The Serbian prose, Cyrillic/Latin metadata, and 32x48 SVG cover
were created for this repository; no public book, publisher asset, or personal
document is included.

## Inventory

`manifest.json` is the machine-readable inventory and expected-behavior
contract. It records each archive's validity, covered importer/security case,
provenance, and whether import should accept, warn, recover, or reject it.

- `serbian-epub2.epub` is a valid EPUB 2 publication with metadata, cover,
  NCX, deliberately non-alphabetical spine order, nested headings, lists,
  footnote, quotation, and poetry.
- `serbian-epub3.epub` is a valid EPUB 3 publication with metadata, cover-image
  manifest property, EPUB 3 `nav`, and a spine order different from filenames.
- `malformed-content.epub` has one recoverable chapter and one malformed XHTML
  spine item. Import should retain safe content and warn about the damaged item.
- `malformed-navigation.epub` has valid spine content and malformed NCX. Import
  should recover content and report that the table of contents is unavailable.
- `attack-zip-slip.epub`, `attack-decompression-bomb.epub`,
  `attack-oversized-entry.epub`, `attack-entry-count.epub`,
  `attack-entity-expansion.epub`, `attack-external-resource.epub`, and
  `attack-encrypted-entry.epub` are isolated negative archives for importer
  security tests. They must not escape the sandbox, expand without limits,
  load external data, or replace a project. `EpubAdversarialSecurityTest` also
  generates small in-test path, count, size, ratio, DTD, and external-resource
  variants, so no large attack artifact is committed.

The attack archives are intentionally small. The bomb contains 128 KiB of
repeated text, the oversized-entry threshold is 8 KiB, and the entry-count
fixture has 40 one-byte noise entries. These are test triggers, not realistic
payloads.

## Assemble and validate

From the repository root:

```sh
python3 document-epub/src/test/resources/fixtures/fixture_tool.py assemble
python3 document-epub/src/test/resources/fixtures/fixture_tool.py validate
```

Assembly uses fixed timestamps, ZIP metadata, and compression settings so the
archives are deterministic. Validation checks the manifest coverage, EPUB ZIP
ordering and mimetype rules, container/package metadata, NCX/nav XML, declared
spine order, malformed XML, and security markers. It uses only the Python
standard library and is intentionally separate from importer implementation.

The malformed XML navigation fixture is rejected by the security boundary. The
parser test separately generates a well-formed but empty NCX map to prove that
recoverable navigation damage reports a warning while valid spine content stays
usable.
