# Readium EPUB Spike (task 7.2)

This is a disposable consumer project for the Readium Kotlin Toolkit `shared`
and `streamer` modules. It is not included in the production Gradle build and
does not implement EPUB import, SAF handling, security limits, or the direct
parser comparison from task 7.3.

## Experiment

The test uses `AssetRetriever`, `DefaultPublicationParser`, and
`PublicationOpener` to open the task-7.1 fixtures. It checks publication title,
author, language, cover relation, reading order, and NCX/nav titles. It also
observes the two malformed fixtures without treating a successful lazy open as
content recovery.

Run from the repository root:

```sh
python3 document-epub/src/test/resources/fixtures/fixture_tool.py validate
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
./gradlew -p readium-spike :app:testDebugUnitTest --tests '*ReadiumFixtureTest'
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
./gradlew -p readium-spike :app:measureReadiumArtifacts :app:assembleDebug
```

The fixture validator reported `11` archives. The Readium test passed three
tests on 2026-08-29.

## Artifact and source results

Readium Kotlin Toolkit tag `3.3.0` resolves from Maven Central. Its AAR
metadata requires `minCompileSdk=36` for both tested modules. The production
project is pinned to compile SDK 35, so a 3.3.0 consumer failed at
`checkDebugUnitTestAarMetadata` with the exact error that both dependencies
require compile SDK 36.

Readium `3.1.0` was used for the runnable compatibility control. It compiled
and ran with the production baseline of AGP 8.8.2, Kotlin 2.1.10, compile SDK
35, min SDK 30, and JDK 21. The resolved runtime classpath contained 16 unique
artifacts totalling 10,117,675 bytes (9.65 MiB by file size):

| Item | Compressed bytes |
|---|---:|
| `readium-shared:3.1.0` AAR | 1,507,246 |
| `readium-streamer:3.1.0` AAR | 295,461 |
| Readium direct subtotal | 1,802,707 |
| All unique runtime artifacts | 10,117,675 |

The total includes Kotlin reflection (3,081,304 bytes), Kotlin stdlib,
coroutines, serialization, datetime, Jsoup, Timber, and
`com.mcxiaoke.koi:core:0.5.5`; it excludes Robolectric and other test-only
artifacts. No native library was present in either Readium AAR. The assembled
spike library manifest contained no permissions, including no `INTERNET`
permission.

For source-buildability, a clean clone of the `3.3.0` tag at commit
`3bb9c88d505d43c8e9f8d3b6b30c69927071c7fe` was built with:

```sh
ANDROID_HOME=/home/homoludens/Android/Sdk \
ANDROID_SDK_ROOT=/home/homoludens/Android/Sdk \
/tmp/readium-kotlin-3.3.0/gradlew \
:readium:readium-shared:assemble :readium:readium-streamer:assemble \
--no-daemon
```

It succeeded with Readium's Gradle 9.1.0, AGP 9.0.0, Kotlin 2.3.20, and
compile SDK 36. The source-built release AARs were byte-identical to the
Maven Central 3.3.0 AARs:

| Artifact | Bytes | SHA-256 |
|---|---:|---|
| `readium-shared:3.3.0` | 1,537,837 | `fea89cc3bdae98e31154e3c659809b3682bb3a6c16e03dbdf2fc20e633aa442b` |
| `readium-streamer:3.3.0` | 302,521 | `745e6a1bf22cea7ff70fb644e724bfebdeaaa2dcbe18c293dddb32c83e3e8c76` |

## Fixture and API evidence

Both valid fixtures opened through the same minimal API. Readium returned the
expected exact metadata (`Мала књига за проверу`, `Ана Тест`, `sr`) and a cover
relation. It preserved declared spine order and navigation order:

| Fixture | Reading order observed | Navigation observed | Warnings |
|---|---|---|---:|
| EPUB 2 | `OEBPS/OEBPS/chapters/zeta.xhtml`, then `OEBPS/OEBPS/chapters/alpha.xhtml` | NCX: `Други лист`, `Први лист` | 0 |
| EPUB 3 | `OEBPS/text/b.xhtml`, then `OEBPS/text/a.xhtml` | nav: `Поглавље Б`, `Поглавље А` | 0 |

The EPUB2 double `OEBPS/` is a fixture-authoring fidelity finding: task 7.1's
OPF hrefs include `OEBPS/` even though the OPF itself is under `OEBPS/`, and
Readium resolves that href literally. The spine IDs and order are still
preserved. This spike does not modify the committed fixtures.

The malformed-content archive also opened lazily with both spine links and no
warning; Readium did not validate the malformed XHTML during publication open.
The malformed-navigation archive failed during open with `Assertion failed`.
These observations are not recovery or security claims and must not replace
the later importer diagnostics and security gates.

## F-Droid implications and disposition

- Readium is BSD-3-Clause licensed; its `shared` and `streamer` manifests are empty.
- `streamer` exposes HTTP-capable resource APIs, but the tested modules do not request network permission. The app must keep external-resource policy explicit and need not grant `INTERNET` for local EPUB import.
- The modules have no JNI or bundled native artifact in the tested AARs.
- Source builds are feasible, but current 3.3.0 requires adopting compile SDK 36, AGP 9.0.0, Gradle 9.1.0, Kotlin 2.3.20, and the corresponding SDK/toolchain in a reproducible F-Droid build.
- The transitive Koi 0.5.5 dependency is Apache-2.0 according to its POM but is old and must be included in a later dependency/license audit.
- Readium remains an EPUB candidate. It is deliberately not added to `document-epub` production dependencies; task 7.3 owns the direct-parser comparison and importer selection.
