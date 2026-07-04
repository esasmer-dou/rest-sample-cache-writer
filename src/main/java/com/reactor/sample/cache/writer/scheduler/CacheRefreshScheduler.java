package com.reactor.sample.cache.writer.scheduler;

import com.reactor.sample.cache.writer.cache.CustomerCacheMaterializer;
import com.reactor.sample.cache.writer.cache.CustomerCacheMaterializer.RefreshResult;
import com.reactor.sample.cache.writer.config.WriterProperties;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

public final class CacheRefreshScheduler implements AutoCloseable {

    private static final AtomicInteger THREAD_SEQUENCE = new AtomicInteger();

    private final CustomerCacheMaterializer materializer;
    private final List<ProjectionSchedule> schedules;
    private final ScheduledExecutorService executor;
    private final boolean runOnce;
    private final CountDownLatch firstRunDone;
    private final AtomicBoolean closed = new AtomicBoolean();

    public CacheRefreshScheduler(CustomerCacheMaterializer materializer, WriterProperties properties) {
        this.materializer = materializer;
        this.schedules = CustomerCacheMaterializer.projectionNames().stream()
                .map(projection -> ProjectionSchedule.from(properties, projection))
                .toList();
        this.runOnce = properties.getBoolean("sample.writer.run-once");
        this.firstRunDone = new CountDownLatch(schedules.size());
        int threads = boundedThreads(properties.getInt("sample.writer.scheduler-threads"), schedules.size());
        this.executor = Executors.newScheduledThreadPool(threads, task -> {
            Thread thread = new Thread(task, "activejdbc-cache-writer-" + THREAD_SEQUENCE.incrementAndGet());
            thread.setDaemon(false);
            return thread;
        });
    }

    public void start() {
        if (closed.get()) {
            return;
        }
        if (runOnce) {
            for (ProjectionSchedule schedule : schedules) {
                executor.schedule(() -> safeRefresh(schedule), schedule.initialDelayMillis(), TimeUnit.MILLISECONDS);
            }
            return;
        }
        for (ProjectionSchedule schedule : schedules) {
            executor.scheduleWithFixedDelay(
                    () -> safeRefresh(schedule),
                    schedule.initialDelayMillis(),
                    schedule.intervalMillis(),
                    TimeUnit.MILLISECONDS);
        }
    }

    public void awaitFirstRun() {
        try {
            firstRunDone.await();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            executor.shutdownNow();
        }
    }

    private void safeRefresh(ProjectionSchedule schedule) {
        try {
            RefreshResult result = materializer.refreshProjection(
                    schedule.projection(),
                    schedule.lockName(),
                    schedule.lockTtlMillis());
            if (result.skippedLocked()) {
                System.out.println("cache refresh skipped projection=" + result.projection()
                        + " lock=" + schedule.lockName()
                        + " reason=another replica owns the Redis lock");
            } else {
                System.out.println("cache refresh published projection=" + result.projection()
                        + " version=" + result.version()
                        + " keys=" + result.writtenKeys());
            }
        } catch (Throwable error) {
            System.err.println("cache refresh failed projection=" + schedule.projection()
                    + ": " + error.getMessage());
            error.printStackTrace(System.err);
        } finally {
            firstRunDone.countDown();
        }
    }

    private static int boundedThreads(int configured, int projectionCount) {
        if (configured <= 0) {
            throw new IllegalArgumentException("sample.writer.scheduler-threads must be positive");
        }
        return Math.max(1, Math.min(configured, Math.max(1, projectionCount)));
    }

    private record ProjectionSchedule(
            String projection,
            long initialDelayMillis,
            long intervalMillis,
            String lockName,
            long lockTtlMillis) {

        static ProjectionSchedule from(WriterProperties properties, String projection) {
            long initialDelayMillis = projectionLong(
                    properties,
                    projection,
                    "initial-delay-ms",
                    "sample.writer.initial-delay-ms",
                    false);
            long intervalMillis = projectionLong(
                    properties,
                    projection,
                    "interval-ms",
                    "sample.writer.interval-ms",
                    true);
            long lockTtlMillis = projectionLong(
                    properties,
                    projection,
                    "lock-ttl-ms",
                    "sample.writer.lock-ttl-ms",
                    true);
            return new ProjectionSchedule(
                    projection,
                    initialDelayMillis,
                    intervalMillis,
                    projectionLockName(properties, projection),
                    lockTtlMillis);
        }
    }

    private static String projectionLockName(WriterProperties properties, String projection) {
        String specificKey = "sample.writer." + projection + ".lock-name";
        String specificRuntime = properties.getRuntimeOverride(specificKey);
        if (hasText(specificRuntime)) {
            return specificRuntime;
        }
        String baseRuntime = properties.getRuntimeOverride("sample.writer.lock-name");
        if (hasText(baseRuntime)) {
            return baseRuntime + "." + projection;
        }
        String specificFile = properties.getFileOptional(specificKey);
        if (hasText(specificFile)) {
            return specificFile;
        }
        return properties.get("sample.writer.lock-name") + "." + projection;
    }

    private static long projectionLong(
            WriterProperties properties,
            String projection,
            String suffix,
            String baseKey,
            boolean positive) {
        String specificKey = "sample.writer." + projection + "." + suffix;
        String specificRuntime = properties.getRuntimeOverride(specificKey);
        if (hasText(specificRuntime)) {
            return parseMillis(specificKey, specificRuntime, positive);
        }
        String baseRuntime = properties.getRuntimeOverride(baseKey);
        if (hasText(baseRuntime)) {
            return parseMillis(baseKey, baseRuntime, positive);
        }
        String specificFile = properties.getFileOptional(specificKey);
        if (hasText(specificFile)) {
            return parseMillis(specificKey, specificFile, positive);
        }
        return parseMillis(baseKey, properties.get(baseKey), positive);
    }

    private static long parseMillis(String key, String value, boolean positive) {
        try {
            long parsed = Long.parseLong(value);
            if (positive && parsed <= 0) {
                throw new IllegalArgumentException("Property must be positive: " + key + "=" + value);
            }
            if (!positive && parsed < 0) {
                throw new IllegalArgumentException("Property must not be negative: " + key + "=" + value);
            }
            return parsed;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Property must be a long: " + key + "=" + value, e);
        }
    }

    private static boolean hasText(String value) {
        return value != null && !value.isBlank();
    }
}
