package com.reactor.sample.cache.writer.scheduler;

import com.reactor.sample.cache.writer.cache.CustomerCacheMaterializer;
import com.reactor.sample.cache.writer.cache.CustomerCacheMaterializer.RefreshResult;
import com.reactor.sample.cache.writer.config.CacheProjectionSettings;
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
        this(materializer, properties, CacheProjectionSettings.resolveAll(properties));
    }

    public CacheRefreshScheduler(
            CustomerCacheMaterializer materializer,
            WriterProperties properties,
            List<CacheProjectionSettings> projectionSettings) {
        this.materializer = materializer;
        this.schedules = projectionSettings.stream()
                .map(ProjectionSchedule::from)
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
        logConfigurationWarnings("startup");
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
            logConfigurationWarnings(schedule, "refresh");
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

    private void logConfigurationWarnings(String phase) {
        for (ProjectionSchedule schedule : schedules) {
            logConfigurationWarnings(schedule, phase);
        }
    }

    private static void logConfigurationWarnings(ProjectionSchedule schedule, String phase) {
        for (String warning : schedule.warnings()) {
            System.err.println("WARNING cache writer config " + warning + " phase=" + phase);
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
            long lockTtlMillis,
            List<String> warnings) {

        static ProjectionSchedule from(CacheProjectionSettings settings) {
            return new ProjectionSchedule(
                    settings.name(),
                    settings.initialDelayMillis(),
                    settings.intervalMillis(),
                    settings.lockName(),
                    settings.lockTtlMillis(),
                    settings.warnings());
        }
    }
}
