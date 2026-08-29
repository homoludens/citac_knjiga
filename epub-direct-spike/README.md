# Direct EPUB Parser Spike (task 7.3)

This disposable experiment compares a small Python standard-library parser with
the committed Readium 3.1.0 control on the task-7.1 fixtures. It is not an
Android importer and is not included by any Gradle build. It reads ZIP entries
in place, follows the OPF container/manifest/spine, parses metadata and NCX/nav,
and reports basic XHTML headings/text. It records security markers only; it
does not enforce archive or XML limits and must not be used as security evidence.

Run from the repository root:

```sh
python3 document-epub/src/test/resources/fixtures/fixture_tool.py validate
python3 -m unittest discover -s epub-direct-spike -p 'test_*.py'
python3 -m py_compile epub-direct-spike/direct_parser_experiment.py epub-direct-spike/test_direct_parser.py
python3 epub-direct-spike/direct_parser_experiment.py
```

The experiment uses only the Python standard library. The Android production
port, if retained, should use platform `java.util.zip` plus the Android XML
pull parser; hardening belongs to task 7.5, not this spike.
