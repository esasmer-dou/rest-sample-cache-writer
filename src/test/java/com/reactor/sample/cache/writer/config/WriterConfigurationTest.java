package com.reactor.sample.cache.writer.config;

import com.reactor.rust.cache.config.CacheProperties;
import com.reactor.rust.cache.projection.CacheWriterProjectionSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WriterConfigurationTest {

    private static final String CONFIG = "rest-sample-cache-writer.properties";

    @TempDir
    Path tempDir;

    @AfterEach
    void clearOverlayProperty() {
        System.clearProperty("reactor.config.file");
    }

    @Test
    void loadsMinimumClasspathDefaults() {
        CacheProperties properties = properties();

        assertEquals("crm.customer", properties.get("sample.writer.namespace"));
        assertEquals("detail,segment,status,campaign,meta", properties.get("sample.writer.projections"));
        assertEquals(2, properties.getInt("sample.writer.scheduler-threads"));
        assertEquals(600000L, properties.getLong("sample.writer.cache-ttl-ms"));
    }

    @Test
    void configuredOverlayChangesProjectionPlan() throws Exception {
        Path overlay = tempDir.resolve("production.properties");
        Files.writeString(overlay, String.join(System.lineSeparator(),
                "sample.writer.namespace=crm.customer.prod",
                "sample.writer.scheduler-threads=1",
                "sample.writer.detail.interval-ms=45000"));
        System.setProperty("reactor.config.file", overlay.toString());

        CacheProperties properties = properties();
        List<CacheWriterProjectionSettings> settings =
                CacheWriterProjectionSettings.resolveAll(properties, "sample.writer");

        assertEquals(1, properties.getInt("sample.writer.scheduler-threads"));
        assertEquals("crm.customer.prod.detail", settings.get(0).namespace());
        assertEquals(45000L, settings.get(0).intervalMillis());
    }

    private static CacheProperties properties() {
        return CacheProperties.load(CONFIG);
    }
}
