import sys
import unittest
from pathlib import Path

sys.path.insert(0, str(Path(__file__).parent))

from direct_parser_experiment import parse


ROOT = Path(__file__).parents[1] / "document-epub/src/test/resources/fixtures"


class DirectParserExperimentTest(unittest.TestCase):
    def result(self, name: str):
        return parse(ROOT / name)

    def test_valid_epub2_preserves_declared_metadata_navigation_and_content_when_resolvable(self):
        result = self.result("serbian-epub2.epub")
        self.assertEqual("Мала књига за проверу", result.title)
        self.assertEqual(["Ана Тест"], result.authors)
        self.assertEqual("sr", result.language)
        self.assertTrue(result.cover)
        self.assertEqual(
            ["OEBPS/OEBPS/chapters/zeta.xhtml", "OEBPS/OEBPS/chapters/alpha.xhtml"],
            result.spine,
        )
        self.assertEqual(["Други лист", "Први лист"], result.navigation_titles)
        self.assertEqual([False, False], [item.parsed for item in result.content])
        self.assertTrue(any("content unavailable" in warning for warning in result.warnings))

    def test_valid_epub3_preserves_metadata_navigation_and_basic_text(self):
        result = self.result("serbian-epub3.epub")
        self.assertEqual("Мала књига за проверу", result.title)
        self.assertEqual(["Ана Тест"], result.authors)
        self.assertEqual("sr", result.language)
        self.assertTrue(result.cover)
        self.assertEqual(["OEBPS/text/b.xhtml", "OEBPS/text/a.xhtml"], result.spine)
        self.assertEqual(["Поглавље Б", "Поглавље А"], result.navigation_titles)
        self.assertEqual([True, True], [item.parsed for item in result.content])
        self.assertEqual(["Поглавље Б", "Поглавље А"], [item.headings[0] for item in result.content])
        self.assertEqual(
            ["Поглавље Б Бета текст.", "Поглавље А Алфа текст."],
            [item.text for item in result.content],
        )

    def test_malformed_fixtures_recover_only_parseable_parts(self):
        content = self.result("malformed-content.epub")
        self.assertEqual([True, False], [item.parsed for item in content.content])
        self.assertTrue(any("ParseError" in warning for warning in content.warnings))

        navigation = self.result("malformed-navigation.epub")
        self.assertEqual([], navigation.navigation_titles)
        self.assertEqual([True], [item.parsed for item in navigation.content])
        self.assertTrue(any("navigation unavailable" in warning for warning in navigation.warnings))

    def test_attack_fixtures_are_inspected_not_claimed_as_enforced(self):
        expected = {
            "attack-zip-slip.epub": "zip-slip marker",
            "attack-decompression-bomb.epub": "compression-ratio threshold marker",
            "attack-oversized-entry.epub": "oversized-entry threshold marker",
            "attack-entry-count.epub": "entry-count threshold marker",
            "attack-entity-expansion.epub": "DTD/entity marker",
            "attack-external-resource.epub": "external-resource marker",
            "attack-encrypted-entry.epub": "encrypted-entry marker",
        }
        for name, marker in expected.items():
            with self.subTest(name=name):
                self.assertIn(marker, self.result(name).security_markers)


if __name__ == "__main__":
    unittest.main()
