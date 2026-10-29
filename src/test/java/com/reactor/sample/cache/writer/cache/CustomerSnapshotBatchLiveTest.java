package com.reactor.sample.cache.writer.cache;

import com.reactor.rust.cache.config.CacheProperties;
import com.reactor.rust.cache.config.RustCacheConfig;
import com.reactor.rust.cache.core.RustCaches;
import com.reactor.sample.cache.writer.db.PostgresCustomerRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

import java.util.UUID;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.*;

@EnabledIfSystemProperty(named = "sample.writer.jdbc.integration", matches = "true")
class CustomerSnapshotBatchLiveTest {
    @Test
    void redisScriptFlushReloadsBothScriptsWithoutReplayingPublication() throws Exception {
        redis("SCRIPT", "FLUSH");
        var values = CacheProperties.load("rest-sample-cache-writer.properties").asProperties();
        values.setProperty("sample.writer.namespace", "script-reload-" + UUID.randomUUID());
        var properties = CacheProperties.from(values);
        try (var cache = RustCaches.create(values);
             var repository = PostgresCustomerRepository.fromProperties(properties)) {
            var materializer = new CustomerCacheMaterializer(repository, cache, properties);
            cache.resetMetrics();
            var first = materializer.refresh("meta", "reload-" + UUID.randomUUID(), 30000);
            assertTrue(first.published());
            assertTrue(cache.metricsJson().contains("\"snapshot_script_cache_misses\":2,"), cache.metricsJson());
            redis("SCRIPT", "FLUSH");
            var second = materializer.refresh("meta", "reload-" + UUID.randomUUID(), 30000);
            assertTrue(second.published());
            assertNotEquals(first.version(), second.version());
            assertTrue(cache.metricsJson().contains("\"snapshot_script_cache_misses\":4,"), cache.metricsJson());
        }
    }

    @Test
    void evalOnlyAclKeepsTheExistingPublicationPathUsable() throws Exception {
        String user = "snapshot-" + UUID.randomUUID();
        String password = UUID.randomUUID().toString();
        redis("ACL", "SETUSER", user, "on", ">" + password, "~*", "+eval", "+exists", "+get", "+incr", "+psetex", "+del");
        var values = CacheProperties.load("rest-sample-cache-writer.properties").asProperties();
        values.setProperty("sample.writer.namespace", user);
        values.setProperty("reactor.cache.redis.username", user);
        values.setProperty("reactor.cache.redis.password", password);
        var properties = CacheProperties.from(values);
        try (var cache = RustCaches.create(values);
             var repository = PostgresCustomerRepository.fromProperties(properties)) {
            var materializer = new CustomerCacheMaterializer(repository, cache, properties);
            cache.resetMetrics();
            assertTrue(materializer.refresh("meta", user, 30000).published());
            assertTrue(cache.metricsJson().contains("\"snapshot_script_acl_fallbacks\":2}"), cache.metricsJson());
        } finally {
            redis("ACL", "DELUSER", user);
        }
    }

    private static void redis(String... args) throws Exception {
        try (var socket = new Socket("127.0.0.1", Integer.getInteger("reactor.cache.redis.integration.port", 6379))) {
            socket.setSoTimeout(3000);
            var output = socket.getOutputStream();
            output.write(("*" + args.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
            for (String arg : args) {
                byte[] bytes = arg.getBytes(StandardCharsets.UTF_8);
                output.write(("$" + bytes.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
                output.write(bytes);
                output.write(new byte[]{13, 10});
            }
            output.flush();
            String reply = new java.io.BufferedReader(new java.io.InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)).readLine();
            assertTrue(reply != null && (reply.startsWith("+") || reply.startsWith(":")), reply);
        }
    }

    @Test
    void realProjectionFlowUsesTwoCallsWithoutChangingKeysOrLeavingTheLease() {
        verify(true, 2);
    }

    @Test
    void explicitLegacySelectionPreservesFiveCallPublication() {
        verify(false, 5);
    }

    private static void verify(boolean batch, int expectedCalls) {
        var values = CacheProperties.load("rest-sample-cache-writer.properties").asProperties();
        String namespace = "sample-wiring-" + UUID.randomUUID();
        values.setProperty("sample.writer.namespace", namespace);
        if (!batch) values.setProperty("reactor.cache.redis.snapshot-batch-enabled", "false");
        var properties = CacheProperties.from(values);
        assertEquals(batch, RustCacheConfig.fromProperties(values).snapshotBatchEnabled());
        try (var cache = RustCaches.create(values);
             var reader = RustCaches.create(RustCacheConfig.builder().host("127.0.0.1")
                     .port(Integer.getInteger("reactor.cache.redis.integration.port", 6379)).build());
             var repository = PostgresCustomerRepository.fromProperties(properties)) {
            var materializer = new CustomerCacheMaterializer(repository, cache, properties);
            for (String projection : materializer.projectionNames()) {
                cache.resetMetrics();
                String lock = namespace + ".lock." + projection;
                var result = materializer.refresh(projection, lock, 30000);
                assertTrue(result.published(), projection);
                assertFalse(result.skippedLocked(), projection);
                assertTrue(result.writtenKeys() > 1, projection);
                assertTrue(cache.metricsJson().contains("\"write_calls\":" + expectedCalls + ","),
                        projection + ": " + cache.metricsJson());
                String pointer = reader.getString(namespace + "." + projection + ":current");
                assertNotNull(pointer, projection);
                assertTrue(pointer.endsWith("|" + result.version()), pointer);
                assertNull(reader.getString("lock:" + lock), projection);
            }
        }
    }
}
