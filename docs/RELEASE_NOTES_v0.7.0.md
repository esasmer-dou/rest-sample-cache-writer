# rest-sample-cache-writer 0.7.0

Uses platform 4.7.0, cache/Dubbo libraries 0.8.0 and shared sample contracts 0.4.4 where applicable.
Java application responsibilities remain unchanged. Existing endpoint and provider contracts remain available.

See the [usage examples](functional-operations.md) for the opt-in freshness, error, transaction or command behavior relevant to this process. Keep features you do not need disabled. No larger heap, worker count, queue or timeout is required for this upgrade.

Use the matching packaged native runtime. Do not copy an older DLL/SO over it. Production data-source, discovery and memory limits still belong to your deployment configuration.

[Quick start](../README.md) | [Türkçe](RELEASE_NOTES_v0.7.0.tr.md)
