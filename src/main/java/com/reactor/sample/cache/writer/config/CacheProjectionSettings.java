package com.reactor.sample.cache.writer.config;

import java.util.ArrayList;
import java.util.List;

public record CacheProjectionSettings(
        String name,
        String namespace,
        long configuredCacheTtlMillis,
        long effectiveCacheTtlMillis,
        long initialDelayMillis,
        long intervalMillis,
        String lockName,
        long lockTtlMillis,
        List<String> warnings) {

    private static final List<String> PROJECTIONS = List.of("detail", "segment", "status", "campaign", "meta");

    public CacheProjectionSettings {
        warnings = List.copyOf(warnings);
    }

    public static List<String> projectionNames() {
        return PROJECTIONS;
    }

    public static List<CacheProjectionSettings> resolveAll(WriterProperties properties) {
        List<String> globalWarnings = new ArrayList<>();
        long ttlSafetyMarginMillis = baseLong(
                properties,
                "sample.writer.cache-ttl-safety-margin-ms",
                "30000",
                true,
                globalWarnings);
        return PROJECTIONS.stream()
                .map(projection -> resolve(properties, projection, ttlSafetyMarginMillis, globalWarnings))
                .toList();
    }

    private static CacheProjectionSettings resolve(
            WriterProperties properties,
            String projection,
            long ttlSafetyMarginMillis,
            List<String> globalWarnings) {
        List<String> warnings = new ArrayList<>(globalWarnings);
        String namespace = projectionText(properties, projection, "namespace", "sample.writer.namespace", true);
        long configuredCacheTtlMillis = projectionLong(
                properties,
                projection,
                "cache-ttl-ms",
                "sample.writer.cache-ttl-ms",
                true,
                warnings);
        long initialDelayMillis = projectionLong(
                properties,
                projection,
                "initial-delay-ms",
                "sample.writer.initial-delay-ms",
                false,
                warnings);
        long intervalMillis = projectionLong(
                properties,
                projection,
                "interval-ms",
                "sample.writer.interval-ms",
                true,
                warnings);
        long lockTtlMillis = projectionLong(
                properties,
                projection,
                "lock-ttl-ms",
                "sample.writer.lock-ttl-ms",
                true,
                warnings);
        long effectiveCacheTtlMillis = effectiveCacheTtl(
                projection,
                configuredCacheTtlMillis,
                intervalMillis,
                ttlSafetyMarginMillis,
                warnings);
        return new CacheProjectionSettings(
                projection,
                namespace,
                configuredCacheTtlMillis,
                effectiveCacheTtlMillis,
                initialDelayMillis,
                intervalMillis,
                projectionText(properties, projection, "lock-name", "sample.writer.lock-name", true),
                lockTtlMillis,
                warnings);
    }

    private static long effectiveCacheTtl(
            String projection,
            long configuredCacheTtlMillis,
            long intervalMillis,
            long ttlSafetyMarginMillis,
            List<String> warnings) {
        if (configuredCacheTtlMillis > intervalMillis) {
            return configuredCacheTtlMillis;
        }
        long effective = safeAdd(intervalMillis, ttlSafetyMarginMillis);
        warnings.add("projection=" + projection
                + " property=sample.writer." + projection + ".cache-ttl-ms"
                + " configuredCacheTtlMs=" + configuredCacheTtlMillis
                + " intervalMs=" + intervalMillis
                + " effectiveCacheTtlMs=" + effective
                + " safetyMarginMs=" + ttlSafetyMarginMillis
                + " reason=cache-ttl-ms-must-be-greater-than-interval");
        return effective;
    }

    private static String projectionText(
            WriterProperties properties,
            String projection,
            String suffix,
            String baseKey,
            boolean appendProjectionForBase) {
        String specificKey = "sample.writer." + projection + "." + suffix;
        String specificRuntime = properties.getRuntimeOverride(specificKey);
        if (hasText(specificRuntime)) {
            return specificRuntime;
        }
        String baseRuntime = properties.getRuntimeOverride(baseKey);
        if (hasText(baseRuntime)) {
            return appendProjectionForBase ? baseRuntime + "." + projection : baseRuntime;
        }
        String specificFile = properties.getFileOptional(specificKey);
        if (hasText(specificFile)) {
            return specificFile;
        }
        String baseFile = properties.get(baseKey);
        return appendProjectionForBase ? baseFile + "." + projection : baseFile;
    }

    private static long projectionLong(
            WriterProperties properties,
            String projection,
            String suffix,
            String baseKey,
            boolean positive,
            List<String> warnings) {
        String specificKey = "sample.writer." + projection + "." + suffix;
        ValueCandidate[] candidates = {
                new ValueCandidate(specificKey, "runtime", properties.getRuntimeOverride(specificKey)),
                new ValueCandidate(baseKey, "runtime", properties.getRuntimeOverride(baseKey)),
                new ValueCandidate(specificKey, "file", properties.getFileOptional(specificKey)),
                new ValueCandidate(baseKey, "file", properties.getFileOptional(baseKey))
        };
        for (ValueCandidate candidate : candidates) {
            if (!hasText(candidate.value())) {
                continue;
            }
            Long parsed = tryParseMillis(candidate, positive, warnings);
            if (parsed != null) {
                return parsed;
            }
        }
        return baseLong(properties, baseKey, "1", positive, warnings);
    }

    private static long baseLong(
            WriterProperties properties,
            String key,
            String hardDefault,
            boolean positive,
            List<String> warnings) {
        ValueCandidate[] candidates = {
                new ValueCandidate(key, "runtime", properties.getRuntimeOverride(key)),
                new ValueCandidate(key, "file", properties.getFileOptional(key)),
                new ValueCandidate(key, "hard-default", hardDefault)
        };
        for (ValueCandidate candidate : candidates) {
            if (!hasText(candidate.value())) {
                continue;
            }
            Long parsed = tryParseMillis(candidate, positive, warnings);
            if (parsed != null) {
                return parsed;
            }
        }
        return 1L;
    }

    private static Long tryParseMillis(ValueCandidate candidate, boolean positive, List<String> warnings) {
        try {
            long parsed = Long.parseLong(candidate.value());
            if (positive && parsed <= 0) {
                warnings.add(invalidWarning(candidate, "must-be-positive"));
                return null;
            }
            if (!positive && parsed < 0) {
                warnings.add(invalidWarning(candidate, "must-not-be-negative"));
                return null;
            }
            return parsed;
        } catch (NumberFormatException e) {
            warnings.add(invalidWarning(candidate, "must-be-long"));
            return null;
        }
    }

    private static String invalidWarning(ValueCandidate candidate, String reason) {
        return "property=" + candidate.key()
                + " source=" + candidate.source()
                + " invalidValue=" + candidate.value()
                + " reason=" + reason
                + " action=ignored-and-fallback-used";
    }

    private static long safeAdd(long left, long right) {
        long result = left + right;
        if (((left ^ result) & (right ^ result)) < 0) {
            return Long.MAX_VALUE;
        }
        return result;
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }

    private record ValueCandidate(String key, String source, String value) {}
}
