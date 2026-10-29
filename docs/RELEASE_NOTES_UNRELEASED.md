# Unreleased Documentation Corrections

[English](RELEASE_NOTES_UNRELEASED.md) | [Türkçe](RELEASE_NOTES_UNRELEASED.tr.md)

Documentation-only corrections for sample `0.8.0`, cache `0.9.0`, platform `4.8.0`
and optional agent/cache adapter `0.7.1`; not a new feature release.

- Remove stale unpublished snapshot-batch/prepared-JSON claims; distinguish sample settings from library and advanced-overlay defaults.
- Explain fixed-delay scheduling, data TTL, process scheduling and replica leases/fencing without promising instant failover or job cancellation.
- Add a source-backed refresh-scoped encoder example; distinguish synchronous staging/publication from missing async/outbox sample flows.
- Fix headless Glowroot troubleshooting to use the existing `-Pglowroot` entrypoint and adapter; no REST diagnostics endpoint.
- Correct Turkish navigation and the nonexistent user-guide jlink reference. PDFs remain earlier snapshots.

No runtime defaults or historical release/test claims change. No new performance
qualification is claimed.
