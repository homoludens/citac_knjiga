#!/usr/bin/env python3
"""Assemble and validate the small, self-authored EPUB test corpus."""

from __future__ import annotations

import argparse
import json
import re
import struct
import sys
import zipfile
from pathlib import Path, PurePosixPath
from xml.etree import ElementTree


ROOT = Path(__file__).resolve().parent
MANIFEST = ROOT / "manifest.json"
FIXED_DATE = (2020, 1, 1, 0, 0, 0)


CONTAINER = b'''<?xml version="1.0" encoding="UTF-8"?>
<container xmlns="urn:oasis:names:tc:opendocument:xmlns:container" version="1.0">
  <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
</container>
'''

COVER = b'''<svg xmlns="http://www.w3.org/2000/svg" width="32" height="48" viewBox="0 0 32 48">
  <rect width="32" height="48" fill="#153243"/><path d="M5 7h22v34H5z" fill="#e0fbfc"/>
  <path d="M9 13h14M9 18h14M9 23h10" stroke="#153243" stroke-width="2"/>
</svg>
'''


def xhtml(title: str, body: str) -> bytes:
    return (f'''<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>{title}</title></head><body>{body}</body></html>
''').encode("utf-8")


def package_opf(version: str, navigation: str, chapters: list[tuple[str, str, str]]) -> bytes:
    nav_item = (
        '<item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>'
        if navigation == "nav"
        else '<item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>'
    )
    cover_properties = ' properties="cover-image"' if version == "3.0" else ""
    items = '\n'.join(
        f'<item id="{item_id}" href="{href}" media-type="application/xhtml+xml"/>'
        for item_id, href, _ in chapters
    )
    spine = '\n'.join(f'<itemref idref="{item_id}"/>' for item_id, _, _ in chapters)
    return (f'''<?xml version="1.0" encoding="UTF-8"?>
<package xmlns="http://www.idpf.org/2007/opf" version="{version}" unique-identifier="book-id">
  <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
    <dc:identifier id="book-id">urn:uuid:self-authored-serbian-fixture</dc:identifier>
    <dc:title>Мала књига за проверу</dc:title><dc:creator>Ана Тест</dc:creator>
    <dc:language>sr</dc:language><meta name="cover" content="cover-image"/>
  </metadata>
  <manifest>
    <item id="cover-image" href="images/cover.svg" media-type="image/svg+xml"{cover_properties}/>
    {nav_item}
    {items}
  </manifest>
  <spine{' toc="ncx"' if navigation == 'ncx' else ''}>{spine}</spine>
</package>
''').encode("utf-8")


def ncx(chapters: list[tuple[str, str, str]], malformed: bool = False) -> bytes:
    points = '\n'.join(
        f'<navPoint id="nav-{index}" playOrder="{index}"><navLabel><text>{title}</text></navLabel>'
        f'<content src="{href}"/></navPoint>'
        for index, (_, href, title) in enumerate(chapters, 1)
    )
    closing = "" if malformed else "</navMap></ncx>"
    return (f'''<?xml version="1.0" encoding="UTF-8"?>
<ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1-1">
<head><meta name="dtb:uid" content="urn:uuid:self-authored-serbian-fixture"/></head>
<docTitle><text>Мала књига за проверу</text></docTitle><navMap>{points}{closing}
''').encode("utf-8")


def nav(chapters: list[tuple[str, str, str]], malformed: bool = False) -> bytes:
    links = ''.join(f'<li><a href="{href}">{title}</a></li>' for _, href, title in chapters)
    ending = "" if malformed else "</ol></nav></body></html>"
    return (f'''<?xml version="1.0" encoding="UTF-8"?>
<html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
<head><title>Садржај</title></head><body><nav epub:type="toc" id="toc"><h1>Садржај</h1><ol>{links}{ending}
''').encode("utf-8")


def epub2_entries() -> list[tuple[str, bytes]]:
    chapters = [
        ("chapter-second", "OEBPS/chapters/zeta.xhtml", "Други лист"),
        ("chapter-first", "OEBPS/chapters/alpha.xhtml", "Први лист"),
    ]
    first_body = '''
<h1>Други лист</h1><h2>Поднаслов</h2><h3>Мала целина</h3>
<p>Ово је други део приче, намерно уписан пре првог у EPUB spine.</p>
<ul><li>прва ставка</li><li>друга ставка</li></ul>
<ol><li>један корак</li><li>други корак</li></ol>
<p>Реченица са белешком<sup><a href="#note-1" epub:type="noteref">1</a></sup>.</p>
<aside id="note-1" epub:type="footnote"><p>Белешка аутора: ово је изворна напомена.</p></aside>
<div class="poetry" epub:type="z3998:poem"><p>Тиха река<br/>носи светлост<br/>кроз град.</p></div>
'''
    second_body = '''
<h1>Први лист</h1><p>Први наслов по spine редоследу долази после другог фајла у имену.</p>
<blockquote><p>Кратак цитат који остаје у нарацији.</p></blockquote>
'''
    return [
        ("mimetype", b"application/epub+zip"),
        ("META-INF/container.xml", CONTAINER),
        ("OEBPS/content.opf", package_opf("2.0", "ncx", chapters)),
        ("OEBPS/toc.ncx", ncx(chapters)),
        ("OEBPS/images/cover.svg", COVER),
        ("OEBPS/chapters/zeta.xhtml", xhtml("Други лист", first_body)),
        ("OEBPS/chapters/alpha.xhtml", xhtml("Први лист", second_body)),
    ]


def epub3_entries() -> list[tuple[str, bytes]]:
    chapters = [
        ("chapter-b", "text/b.xhtml", "Поглавље Б"),
        ("chapter-a", "text/a.xhtml", "Поглавље А"),
    ]
    return [
        ("mimetype", b"application/epub+zip"),
        ("META-INF/container.xml", CONTAINER),
        ("OEBPS/content.opf", package_opf("3.0", "nav", chapters)),
        ("OEBPS/nav.xhtml", nav(chapters)),
        ("OEBPS/images/cover.svg", COVER),
        ("OEBPS/text/b.xhtml", xhtml("Поглавље Б", "<h1>Поглавље Б</h1><p>Бета текст.</p>")),
        ("OEBPS/text/a.xhtml", xhtml("Поглавље А", "<h1>Поглавље А</h1><p>Алфа текст.</p>")),
    ]


def simple_package(
    navigation: str = "ncx",
    extra: list[tuple[str, bytes]] | None = None,
    malformed_navigation: bool = False,
    chapters: list[tuple[str, str, str]] | None = None,
) -> list[tuple[str, bytes]]:
    chapters = chapters or [("chapter", "chapter.xhtml", "Једно поглавље")]
    entries = [
        ("mimetype", b"application/epub+zip"),
        ("META-INF/container.xml", CONTAINER),
        ("OEBPS/content.opf", package_opf("2.0", navigation, chapters)),
        ("OEBPS/images/cover.svg", COVER),
    ]
    entries.append(("OEBPS/toc.ncx", ncx(chapters, malformed_navigation)))
    entries.extend((f"OEBPS/{href}", xhtml(title, "<h1>" + title + "</h1><p>Текст за тест.</p>")) for _, href, title in chapters)
    if extra:
        entries.extend(extra)
    return entries


def fixture_entries(name: str) -> tuple[list[tuple[str, bytes]], set[str], set[str]]:
    if name == "serbian-epub2.epub":
        return epub2_entries(), set(), set()
    if name == "serbian-epub3.epub":
        return epub3_entries(), set(), set()
    if name == "malformed-content.epub":
        chapters = [("good", "good.xhtml", "Опоравајуће поглавље"), ("bad", "bad.xhtml", "Оштећено поглавље")]
        entries = simple_package(chapters=chapters)
        entries[-1] = (
            "OEBPS/bad.xhtml",
            "<html xmlns=\"http://www.w3.org/1999/xhtml\"><body><p>Недовршен XML".encode("utf-8"),
        )
        return entries, set(), set()
    if name == "malformed-navigation.epub":
        return simple_package(malformed_navigation=True), set(), set()
    if name == "attack-zip-slip.epub":
        return simple_package(extra=[("../outside.txt", b"must not be extracted")]), set(), set()
    if name == "attack-decompression-bomb.epub":
        return simple_package(extra=[("OEBPS/bomb.txt", b"A" * 131072)]), set(), set()
    if name == "attack-oversized-entry.epub":
        return simple_package(extra=[("OEBPS/oversized.bin", b"0123456789abcdef" * 512)]), {"OEBPS/oversized.bin"}, set()
    if name == "attack-entity-expansion.epub":
        entity = b'''<?xml version="1.0"?><!DOCTYPE x [<!ENTITY a "aaaaaaaa"> <!ENTITY b "&a;&a;&a;&a;">]><html><body><p>&b;</p></body></html>'''
        return simple_package(extra=[("OEBPS/entity.xhtml", entity)]), set(), set()
    if name == "attack-external-resource.epub":
        external = xhtml("Спољни ресурс", '<img src="file:///etc/passwd"/><a href="https://example.invalid/x">ресурс</a>')
        return simple_package(extra=[("OEBPS/external.xhtml", external)]), set(), set()
    if name == "attack-encrypted-entry.epub":
        return simple_package(extra=[("OEBPS/encrypted.xhtml", xhtml("Шифровано", "<p>садржај</p>"))]), set(), {"OEBPS/encrypted.xhtml"}
    if name == "attack-entry-count.epub":
        extra = [(f"OEBPS/noise/{index:02d}.txt", b"x") for index in range(40)]
        return simple_package(extra=extra), set(), set()
    raise KeyError(name)


def write_archive(path: Path, entries: list[tuple[str, bytes]], stored: set[str], encrypted: set[str]) -> None:
    with zipfile.ZipFile(path, "w", compression=zipfile.ZIP_DEFLATED, compresslevel=9) as archive:
        for name, data in entries:
            info = zipfile.ZipInfo(name, FIXED_DATE)
            info.create_system = 3
            info.external_attr = 0o644 << 16
            info.compress_type = zipfile.ZIP_STORED if name == "mimetype" or name in stored else zipfile.ZIP_DEFLATED
            if name in encrypted:
                # Python's stdlib cannot encrypt; headers are flagged after writing.
                info.flag_bits |= 0x1
            archive.writestr(info, data)
    if encrypted:
        mark_encrypted_entries(path, encrypted)


def mark_encrypted_entries(path: Path, names: set[str]) -> None:
    data = bytearray(path.read_bytes())
    offset = 0
    while offset < len(data):
        local = data.find(b"PK\x03\x04", offset)
        central = data.find(b"PK\x01\x02", offset)
        positions = [position for position in (local, central) if position >= 0]
        if not positions:
            break
        position = min(positions)
        if position == local:
            name_length = struct.unpack_from("<H", data, position + 26)[0]
            extra_length = struct.unpack_from("<H", data, position + 28)[0]
            name_start = position + 30
            name_end = name_start + name_length
            field = position + 6
            offset = name_end + extra_length
        else:
            name_length = struct.unpack_from("<H", data, position + 28)[0]
            extra_length = struct.unpack_from("<H", data, position + 30)[0]
            comment_length = struct.unpack_from("<H", data, position + 32)[0]
            name_start = position + 46
            name_end = name_start + name_length
            field = position + 8
            offset = name_end + extra_length + comment_length
        entry_name = bytes(data[name_start:name_end]).decode("utf-8")
        if entry_name in names:
            flags = struct.unpack_from("<H", data, field)[0]
            struct.pack_into("<H", data, field, flags | 0x1)
    path.write_bytes(data)


def assemble() -> None:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    for fixture in manifest["fixtures"]:
        name = fixture["file"]
        entries, stored, encrypted = fixture_entries(name)
        write_archive(ROOT / name, entries, stored, encrypted)


def read_entry(archive: zipfile.ZipFile, name: str) -> bytes:
    return archive.read(name)


def parse_xml(data: bytes) -> ElementTree.Element:
    return ElementTree.fromstring(data)


def validate() -> None:
    manifest = json.loads(MANIFEST.read_text(encoding="utf-8"))
    expected_cases = set(manifest["required_cases"])
    actual_cases = {case for fixture in manifest["fixtures"] for case in fixture["cases"]}
    assert expected_cases <= actual_cases, f"missing coverage: {expected_cases - actual_cases}"
    for fixture in manifest["fixtures"]:
        path = ROOT / fixture["file"]
        assert path.is_file(), f"missing {path.name}"
        with zipfile.ZipFile(path) as archive:
            infos = archive.infolist()
            names = [info.filename for info in infos]
            assert names[0] == "mimetype", f"{path.name}: mimetype is not first"
            assert infos[0].compress_type == zipfile.ZIP_STORED, f"{path.name}: mimetype is compressed"
            assert read_entry(archive, "mimetype") == b"application/epub+zip", f"{path.name}: bad mimetype"
            assert len(names) == len(set(names)), f"{path.name}: duplicate ZIP names"
            assert "META-INF/container.xml" in names, f"{path.name}: missing container"
            assert set(fixture.get("required_entries", [])) <= set(names), f"{path.name}: missing required entry"
            for name in fixture.get("forbidden_entries", []):
                assert name not in names, f"{path.name}: unexpected {name}"
            for info in infos:
                assert not PurePosixPath(info.filename).is_absolute() or "zip_slip" in fixture["cases"], f"{path.name}: absolute path"

            cases = set(fixture["cases"])
            if "zip_slip" in cases:
                assert any(".." in PurePosixPath(name).parts for name in names)
            if "encrypted" in cases:
                assert any(info.flag_bits & 0x1 for info in infos)
            if "oversized_entry" in cases:
                assert max(info.file_size for info in infos) >= fixture["threshold_bytes"]
            if "entry_count" in cases:
                assert len(infos) >= fixture["threshold_entries"]
            if "compression_ratio" in cases:
                ratio = max(info.file_size / max(info.compress_size, 1) for info in infos)
                assert ratio >= fixture["threshold_ratio"]
            if "external_resource" in cases:
                payload = b"\n".join(read_entry(archive, name) for name in names if name.endswith((".xhtml", ".html")))
                assert re.search(rb'(?:src|href)=["\'](?:https?|file):', payload)
            if "entity_expansion" in cases:
                payload = b"\n".join(read_entry(archive, name) for name in names if name.endswith((".xhtml", ".xml")))
                assert b"<!ENTITY" in payload and b"<!DOCTYPE" in payload
            if "malformed_xml" in cases:
                name = fixture["malformed_entry"]
                try:
                    parse_xml(read_entry(archive, name))
                except ElementTree.ParseError:
                    pass
                else:
                    raise AssertionError(f"{path.name}: malformed entry parses")
            if "malformed_content" in cases or "malformed_navigation" in cases:
                name = fixture["malformed_entry"]
                try:
                    parse_xml(read_entry(archive, name))
                except ElementTree.ParseError:
                    pass
                else:
                    raise AssertionError(f"{path.name}: malformed entry parses")
            if fixture["validity"] == "valid":
                container = parse_xml(read_entry(archive, "META-INF/container.xml"))
                rootfile = container.find("{urn:oasis:names:tc:opendocument:xmlns:container}rootfiles/{urn:oasis:names:tc:opendocument:xmlns:container}rootfile")
                opf_path = rootfile.attrib["full-path"]
                opf = parse_xml(read_entry(archive, opf_path))
                assert opf.attrib["version"] == fixture["epub_version"]
                assert b"cover-image" in read_entry(archive, opf_path)
                if fixture.get("navigation") == "ncx":
                    parse_xml(read_entry(archive, "OEBPS/toc.ncx"))
                if fixture.get("navigation") == "nav":
                    parse_xml(read_entry(archive, "OEBPS/nav.xhtml"))
                spine = [ref.attrib["idref"] for ref in opf.findall("{http://www.idpf.org/2007/opf}spine/{http://www.idpf.org/2007/opf}itemref")]
                assert spine == fixture["expected_spine_ids"]
                payload = b"\n".join(read_entry(archive, name) for name in names if name.endswith(".xhtml"))
                for marker in fixture.get("required_markers", []):
                    assert marker.encode("utf-8") in payload, f"{path.name}: missing marker {marker}"
    print(f"validated {len(manifest['fixtures'])} EPUB fixtures")


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("command", choices=("assemble", "validate"))
    args = parser.parse_args()
    if args.command == "assemble":
        assemble()
    else:
        validate()
    return 0


if __name__ == "__main__":
    sys.exit(main())
