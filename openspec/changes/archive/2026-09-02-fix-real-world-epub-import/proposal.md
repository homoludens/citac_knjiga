## Why

Normal real-world EPUBs fail import because production validation uses fixture-sized defaults: 40 entries, 128 KiB total uncompressed data, 8 KiB per entry, and a 100:1 compression ratio. Import must accept ordinary books within realistic finite bounds while continuing to reject hostile archives and XML.

## What Changes

- Replace fixture-sized defaults with bounded, memory-safe production limits qualified against representative ordinary text-and-image EPUBs; define exact limits and boundary behavior in the capability specification and design rather than exposing arbitrary user controls.
- Report precise import failures and warnings, including the affected entry or publication, validation rule, observed value, allowed value, and whether the issue is a non-retryable security rejection or a recoverable compatibility issue.
- Keep path traversal, XML external entities, encrypted or DRM-protected content, and decompression bombs as mandatory hard failures under every import mode.
- Permit at most one bounded compatibility retry for explicitly classified benign legacy behavior, such as supported font obfuscation, harmless doctypes without external entities, or external hyperlinks that remain references and are never fetched.
- Do not add a global validation or security-disable setting.
- Non-goals: DRM support, network resource loading, unlimited or user-disabled safety limits, broad malformed-EPUB repair, and changes outside EPUB acceptance, safety, and diagnostics.

## Capabilities

### New Capabilities

- `epub-import`: Safe import of normal DRM-free EPUBs using realistic bounded validation, actionable diagnostics, and narrowly scoped compatibility recovery.

### Modified Capabilities

None. There are currently no capability paths under `openspec/specs` to modify.

## Impact

- Affects EPUB archive validation, XML and publication compatibility handling, import retry control, and user-visible import diagnostics.
- Requires representative normal, boundary, malformed, encrypted, traversal, external-entity, and decompression-bomb EPUB fixtures to verify acceptance and rejection behavior.
- Does not require a new subsystem, network access, or a general security configuration surface.
