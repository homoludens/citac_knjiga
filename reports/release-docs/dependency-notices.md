# Dependency notices

The complete dependency notice data is generated once by the existing audited
inventory and bundled in both application release flavors. This bundle links to
that authoritative data rather than copying a second, potentially stale list.

| Record | Repository path | SHA-256 |
|---|---|---|
| Machine-readable inventory | `app/src/main/assets/notices/dependency-license-inventory.json` | `230b6a9f5ceeab7ec08795a9d79810ca839fed2095c4e5a2f5913a5cc6f21906` |
| Human-readable notices | `app/src/main/assets/notices/THIRD_PARTY_NOTICES.md` | `35a38cd1f533bcece297992a2824880c069c31ac79cdb27f3516c1be65b31229` |

The inventory was generated from the locked Gradle runtime/test graph by
`scripts/audit_dependencies.py`. It covers standard and F-Droid release graphs,
module test graphs, native eSpeak-NG provenance, model/dataset provenance, and
test-only dependencies. The SBOM's Maven component set is derived from the same
inventory.

No license text, model payload, generated audio, document content, local cache
path, or legal clearance assertion is duplicated here. License status and
redistribution limitations remain those recorded in the linked inventory and
`model-attribution.md`.
