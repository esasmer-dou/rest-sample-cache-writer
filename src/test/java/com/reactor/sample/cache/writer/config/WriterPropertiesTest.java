package com.reactor.sample.cache.writer.config;

import com.reactor.rust.cache.projection.CacheWriterProjectionSettings;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

class WriterPropertiesTest {

    @TempDir
    Path tempDir;

    @AfterEach
    void clearOverlayProperty() {
        System.clearProperty("reactor.config.file");
    }

    @Test
    void loadsMinimumClasspathDefaults() {
        WriterProperties properties = WriterProperties.load();

        assertEquals("crm.customer", properties.get("sample.writer.namespace"));
        assertEquals("detail,segment,status,campaign,meta", properties.get("sample.writer.projections"));
        assertEquals(2, properties.getInt("sample.writer.scheduler-threads"));
        assertEquals(600000L, properties.getLong("sample.writer.cache-ttl-ms"));
        assertEquals(30000L, properties.getLong("sample.writer.cache-ttl-safety-margin-ms"));
    }

    @Test
    void derivesProjectionNamespacesAndLocksFromMinimumDefaults() {
        List<CacheWriterProjectionSettings> settings =
                CacheWriterProjectionSettings.resolveAll(WriterProperties.load(), "sample.writer");

        assertEquals("crm.customer.detail", settings.get(0).namespace());
        assertEquals("crm.customer.refresh.detail", settings.get(0).lockName());
        assertEquals("crm.customer.campaign", settings.get(3).namespace());
        assertEquals("crm.customer.refresh.campaign", settings.get(3).lockName());
    }

    @Test
    void configuredOverlayOverridesMinimumDefaults() throws Exception {
        Path overlay = tempDir.resolve("production.properties");
        Files.writeString(overlay, String.join(System.lineSeparator(),
                "sample.writer.namespace=crm.customer.prod",
                "sample.writer.scheduler-threads=1",
                "sample.writer.detail.interval-ms=45000"));
        System.setProperty("reactor.config.file", overlay.toString());

        WriterProperties properties = WriterProperties.load();
        List<CacheWriterProjectionSettings> settings =
                CacheWriterProjectionSettings.resolveAll(properties, "sample.writer");

        assertEquals("crm.customer.prod", properties.get("sample.writer.namespace"));
        assertEquals(1, properties.getInt("sample.writer.scheduler-threads"));
        assertEquals("crm.customer.prod.detail", settings.get(0).namespace());
        assertEquals(45000L, settings.get(0).intervalMillis());
    }
}
