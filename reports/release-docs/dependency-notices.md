# Dependency notices

The complete dependency notice data is generated once by the existing audited
inventory and bundled in both application release flavors. This bundle links to
that authoritative data rather than copying a second, potentially stale list.

| Record | Repository path | SHA-256 |
|---|---|---|
| Machine-readable inventory | `app/src/main/assets/notices/dependency-license-inventory.json` | `11bd0ca2d0c36cad72439e50dc240f3008ccb71d2847d7460ea1e0f5e8144c52` |
| Human-readable notices | `app/src/main/assets/notices/THIRD_PARTY_NOTICES.md` | `19fc75e15dac7681441200a7b95128a4f8bd4985c79bc9bdffe1f107c1c9219e` |

The inventory was generated from the locked Gradle runtime/test graph by
`scripts/audit_dependencies.py`. It covers standard and F-Droid release graphs,
module test graphs, native eSpeak-NG provenance, model/dataset provenance, and
test-only dependencies. The SBOM's Maven component set is derived from the same
inventory.

No license text, model payload, generated audio, document content, local cache
path, or legal clearance assertion is duplicated here. License status and
redistribution limitations remain those recorded in the linked inventory and
`model-attribution.md`.
