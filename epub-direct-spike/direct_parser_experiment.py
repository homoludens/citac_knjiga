#!/usr/bin/env python3
"""Bounded, stdlib-only EPUB 2/3 comparison parser; not production code."""

from __future__ import annotations

import json
import posixpath
import re
import sys
import zipfile
from dataclasses import asdict, dataclass
from pathlib import Path
from xml.etree import ElementTree


DC = "{http://purl.org/dc/elements/1.1/}"
OPF = "{http://www.idpf.org/2007/opf}"
CONTAINER = "{urn:oasis:names:tc:opendocument:xmlns:container}"
NCX = "{http://www.daisy.org/z3986/2005/ncx/}"
EPUB = "{http://www.idpf.org/2007/ops}"
XHTML = "{http://www.w3.org/1999/xhtml}"


def local_name(element: ElementTree.Element) -> str:
    return element.tag.rsplit("}", 1)[-1]


def clean_text(element: ElementTree.Element | None) -> str:
    return " ".join(part.strip() for part in element.itertext() if part.strip()) if element is not None else ""


def xml(data: bytes) -> ElementTree.Element:
    return ElementTree.fromstring(data)


def resolve(base: str, href: str) -> str:
    return posixpath.normpath(posixpath.join(posixpath.dirname(base), href.split("#", 1)[0]))


def epub_type(element: ElementTree.Element) -> str:
    return element.attrib.get(EPUB + "type", element.attrib.get("epub:type", ""))


@dataclass
class Content:
    path: str
    parsed: bool
    headings: list[str]
    text: str
    list_items: int
    notes: int
    poetry: int


@dataclass
class Result:
    fixture: str
    title: str
    authors: list[str]
    language: str
    cover: bool
    spine: list[str]
    navigation: str
    navigation_titles: list[str]
    content: list[Content]
    warnings: list[str]
    security_markers: list[str]
    entry_count: int
    max_compression_ratio: float


def parse(path: Path) -> Result:
    warnings: list[str] = []
    with zipfile.ZipFile(path) as archive:
        infos = archive.infolist()
        names = {info.filename for info in infos}
        security: list[str] = []
        if any(".." in Path(info.filename).parts or info.filename.startswith("/") for info in infos):
            security.append("zip-slip marker")
        if any(info.flag_bits & 0x1 for info in infos):
            security.append("encrypted-entry marker")
        ratio = max(
            (info.file_size / max(info.compress_size, 1) for info in infos),
            default=0.0,
        )
        if ratio >= 100:
            security.append("compression-ratio threshold marker")
        if len(infos) >= 40:
            security.append("entry-count threshold marker")
        if max((info.file_size for info in infos), default=0) >= 8192:
            security.append("oversized-entry threshold marker")
        xml_payload = b"\n".join(
            archive.read(info.filename)
            for info in infos
            if not info.flag_bits & 0x1 and info.filename.endswith((".xml", ".xhtml", ".html"))
        )
        if b"<!DOCTYPE" in xml_payload or b"<!ENTITY" in xml_payload:
            security.append("DTD/entity marker")
        if re.search(rb"(?:src|href)=[\"'](?:https?|file):", xml_payload):
            security.append("external-resource marker")

        container = xml(archive.read("META-INF/container.xml"))
        rootfile = container.find(CONTAINER + "rootfiles/" + CONTAINER + "rootfile")
        if rootfile is None:
            raise ValueError("container has no rootfile")
        opf_path = rootfile.attrib["full-path"]
        package = xml(archive.read(opf_path))
        metadata = package.find(OPF + "metadata")
        title = clean_text(metadata.find(DC + "title") if metadata is not None else None)
        authors = [clean_text(node) for node in package.findall(".//" + DC + "creator")]
        language = clean_text(metadata.find(DC + "language") if metadata is not None else None)
        manifest: dict[str, ElementTree.Element] = {
            item.attrib["id"]: item for item in package.findall(".//" + OPF + "manifest/" + OPF + "item")
        }
        cover_ids = {
            item.attrib["id"]
            for item in manifest.values()
            if "cover-image" in item.attrib.get("properties", "").split()
        }
        if metadata is not None:
            cover_ids.update(
                meta.attrib["content"]
                for meta in metadata.findall(OPF + "meta") + metadata.findall("meta")
                if meta.attrib.get("name") == "cover" and "content" in meta.attrib
            )
        cover = any(
            item_id in manifest and resolve(opf_path, manifest[item_id].attrib["href"]) in names
            for item_id in cover_ids
        )

        spine_node = package.find(OPF + "spine")
        spine_ids = [ref.attrib["idref"] for ref in spine_node.findall(OPF + "itemref")] if spine_node is not None else []
        spine = [resolve(opf_path, manifest[item_id].attrib["href"]) for item_id in spine_ids if item_id in manifest]

        navigation = "none"
        navigation_titles: list[str] = []
        nav_item = next(
            (item for item in manifest.values() if "nav" in item.attrib.get("properties", "").split()),
            None,
        )
        toc_id = spine_node.attrib.get("toc") if spine_node is not None else None
        toc_item = manifest.get(toc_id) if toc_id else None
        try:
            if nav_item is not None:
                navigation = "nav"
                nav = xml(archive.read(resolve(opf_path, nav_item.attrib["href"])))
                toc = next(
                    node for node in nav.iter()
                    if local_name(node) == "nav" and "toc" in epub_type(node).split()
                )
                navigation_titles = [clean_text(link) for link in toc.iter(XHTML + "a")]
            elif toc_item is not None:
                navigation = "ncx"
                ncx = xml(archive.read(resolve(opf_path, toc_item.attrib["href"])))
                navigation_titles = [
                    clean_text(point.find(NCX + "navLabel/" + NCX + "text"))
                    for point in ncx.findall(".//" + NCX + "navPoint")
                ]
        except (KeyError, ElementTree.ParseError, StopIteration) as error:
            warnings.append(f"navigation unavailable: {type(error).__name__}")

        content: list[Content] = []
        for item_id in spine_ids:
            item = manifest.get(item_id)
            if item is None:
                warnings.append(f"missing manifest item: {item_id}")
                continue
            target = resolve(opf_path, item.attrib["href"])
            try:
                document = xml(archive.read(target))
            except (KeyError, ElementTree.ParseError) as error:
                warnings.append(f"content unavailable {target}: {type(error).__name__}")
                content.append(Content(target, False, [], "", 0, 0, 0))
                continue
            headings = [
                clean_text(node)
                for node in document.iter()
                if local_name(node) in {f"h{level}" for level in range(1, 7)}
            ]
            list_items = sum(local_name(node) == "li" for node in document.iter())
            notes = sum(
                "footnote" in epub_type(node).split() or "noteref" in epub_type(node).split()
                for node in document.iter()
            )
            poetry = sum(
                "poem" in epub_type(node).split() or "poetry" in node.attrib.get("class", "").split()
                for node in document.iter()
            )
            body = next((node for node in document.iter() if local_name(node) == "body"), document)
            content.append(Content(target, True, headings, clean_text(body), list_items, notes, poetry))
        return Result(
            path.name,
            title,
            authors,
            language,
            cover,
            spine,
            navigation,
            navigation_titles,
            content,
            warnings,
            security,
            len(infos),
            round(ratio, 2),
        )


def main() -> int:
    root = Path(__file__).resolve().parents[1] / "document-epub/src/test/resources/fixtures"
    if len(sys.argv) > 1 and sys.argv[1] != "--json":
        root = Path(sys.argv[1])
    results = [parse(root / name) for name in sorted(path.name for path in root.glob("*.epub"))]
    if "--json" in sys.argv:
        print(json.dumps([asdict(result) for result in results], ensure_ascii=False, sort_keys=True))
    else:
        for result in results:
            print(
                f"DIRECT_SPIKE fixture={result.fixture} spine={len(result.spine)} "
                f"content={sum(item.parsed for item in result.content)}/{len(result.content)} "
                f"navigation={result.navigation}:{len(result.navigation_titles)} "
                f"warnings={len(result.warnings)} security={len(result.security_markers)}"
            )
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
