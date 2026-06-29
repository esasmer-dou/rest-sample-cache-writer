package com.reactor.sample.cache.writer.scheduler;

import com.reactor.rust.cache.versioned.VersionedJsonCacheWriter.SnapshotResult;
import com.reactor.sample.cache.writer.cache.CustomerCacheMaterializer;
import com.reactor.sample.cache.writer.config.WriterProperties;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

public final class CacheRefreshScheduler implements AutoCloseable {

    private final CustomerCacheMaterializer materializer;
    private final ScheduledExecutorService executor;
    private final long initialDelayMillis;
    private final long intervalMillis;
    private final boolean runOnce;
    private final CountDownLatch firstRunDone = new CountDownLatch(1);
    private final AtomicBoolean closed = new AtomicBoolean();

    public CacheRefreshScheduler(CustomerCacheMaterializer materializer, WriterProperties properties) {
        this.materializer = materializer;
        this.initialDelayMillis = properties.getLong("sample.writer.initial-delay-ms");
        this.intervalMillis = properties.getLong("sample.writer.interval-ms");
        this.runOnce = properties.getBoolean("sample.writer.run-once");
        this.executor = Executors.newSingleThreadScheduledExecutor(task -> {
            Thread thread = new Thread(task, "activejdbc-cache-writer");
            thread.setDaemon(false);
            return thread;
        });
    }

    public void start() {
        if (runOnce) {
            executor.schedule(this::safeRefresh, initialDelayMillis, TimeUnit.MILLISECONDS);
            return;
        }
        executor.scheduleWithFixedDelay(this::safeRefresh, initialDelayMillis, intervalMillis, TimeUnit.MILLISECONDS);
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

    private void safeRefresh() {
        try {
            SnapshotResult result = materializer.refresh();
            if (result.skippedLocked()) {
                System.out.println("cache refresh skipped: another replica owns the Redis lock");
            } else {
                System.out.println("cache refresh published version=" + result.version()
                        + " keys=" + result.writtenKeys());
            }
        } catch (Throwable error) {
            System.err.println("cache refresh failed: " + error.getMessage());
            error.printStackTrace(System.err);
        } finally {
            firstRunDone.countDown();
        }
    }
}
