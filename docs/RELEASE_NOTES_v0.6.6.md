# rest-sample-cache-writer 0.6.6

[English](RELEASE_NOTES_v0.6.6.md) | [Turkish](RELEASE_NOTES_v0.6.6.tr.md)

Aligns the sample with platform 4.6.3, cache 0.7.7, Dubbo 0.7.4 and shared models/contracts 0.4.3 where used. Endpoints, properties, profiles and business behavior are unchanged. Only the selected starter dependencies are loaded; no new runtime component is enabled.

## Upgrade

The release JAR contains application classes, not a standalone executable runtime.
Use `mvn exec:java` or the documented Docker image. Do not start this thin JAR with `java -jar`.

Update the Maven dependency or parent and rebuild the application. Keep annotation processors on the aligned platform version. Use the packaged native library for your OS and CPU. Do not replace an old release's assets in place.

Java business logic stays in Java. No runtime reflection, new Java I/O stack or default telemetry activation is added. ARM64 binaries are cross/native-built in CI; runtime application checks for this release use x64. This patch does not claim a new universal RSS or throughput result.

GitHub Packages requires a valid `read:packages` token in Maven `settings.xml`, even for a public repository. Git SSH access is separate from Maven authentication.

